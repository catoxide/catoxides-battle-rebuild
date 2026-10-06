package com.catoxide.catoxidesbattlerebuild.client;

import cn.solarmoon.spark_core.animation.IEntityAnimatable;
import cn.solarmoon.spark_core.animation.anim.AnimGroups;
import cn.solarmoon.spark_core.animation.anim.AnimInstance;
import cn.solarmoon.spark_core.animation.anim.AnimLayer;
import cn.solarmoon.spark_core.animation.anim.KeyAnimData;
import cn.solarmoon.spark_core.animation.anim.origin.AnimIndex;
import cn.solarmoon.spark_core.animation.model.BonePose;
import cn.solarmoon.spark_core.animation.model.ModelIndex;
import cn.solarmoon.spark_core.event.BoneUpdateEvent;
import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.registry.ModWeapons;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 玩家剑模型处理器（客户端）—— 叠加层方案（v3）。
 *
 * <p>当玩家右手持铁剑（且主手为右手）时，把该玩家切换到 Spark 手腕玩家模型
 * {@code (entity, <modid>:player_wrist)}（tools/gen_wrist_player_sword_v3.js 生成）：
 * <ul>
 *   <li><b>身体层</b> = 分段身体（steve.json 1:1：3.5px 上臂+前臂+手腕、大腿+小腿+脚），
 *       花纹贴图 UV。身体<b>可换</b>：改生成器 BODY_GEO/BODY_TEX 输入再跑即可，剑嵌入不变。</li>
 *   <li><b>剑层</b> = sword.geo.json 完整骨树（hands/left/right/main/shade/blade/handle），
 *       经刚体变换 M'=A·p+B（A=Rx(90°)，锚 main 骨 pivot 到手腕 rightItem[-5.5,12.4,0]）
 *       叠加在身体上；left/right 去手臂 cube 只留骨供动画。</li>
 *   <li>为什么剑要嵌入而非独立物品：Spark 在玩家使用 Spark 几何时取消整个原版
 *       PlayerRenderer.render（含 PlayerItemInHandLayer 渲染层），独立物品模型不会渲染。</li>
 *   <li>第一人称仍走 sword.json 物品模型 + GeoItemRenderer（不受影响）。</li>
 * </ul>
 *
 * <p>贴图：复合 96×64 = 身体 64×64 @ (0,0) + 剑 32×64 @ (64,0)（含 90° 转置下区）
 * → 模型剑 cube face-UV u 已 +64。身体层<b>运行时</b>取玩家本人皮肤
 * （{@code getSkin().texture()} 资源，见 {@link WristSkinComposer}）：
 * 合成 96×64 DynamicTexture 注册进 TextureManager，Spark 渲染时玩家身体=自己的皮肤；
 * 皮肤变化自动重合成；皮肤缺失/非 64×64（legacy 64×32）→ 回退静态复合贴图
 * {@code <modid>:textures/entity/player_wrist.png}（Steve 身体，离线预生成）。
 * 停用（恢复默认模型索引 (entity, minecraft:player)）后回原版渲染，原版直接用玩家皮肤，无残留。
 * ⚠️ 绝不能 setModel(null)：Spark 内置 player_base_state 状态机对所有玩家常开、每 tick 运行，
 * 进入状态时 animInstance 按「当前模型」查动画，模型为 null 会在 Spark 代码里抛 NPE 直接崩溃。
 *
 * <p>动画分工（AnimGroups，按骨合成）：
 * <ul>
 *   <li>STATE(1)：Spark 内置 base_state.json 的 20 个状态动画原样注册到本模型
 *       （state.land.idle/move/move_back/sprint、crouch.*、fly.*、swim.*、sit、sleep、
 *       fall、fall_fly、jump、jump_land、climb.*），由 Spark 内置 PlayerBaseAnimStateMachine
 *       按玩家状态自动播放——身体/腿/头/双臂/手持位与 Spark 默认玩家模型完全一致
 *       （=「把默认动画叠上去」）。Java 只在 STATE 层空时兜底补播近似状态。</li>
 *   <li>MAIN(4)：3rd_still（loop=true 循环常驻，驱动剑骨 main/left/right/shade）；
 *       挥击上升沿 → 清空 MAIN 层 + 3rd_attack（loop=once）。
 *       存活判定<b>纯时间窗</b>（自跟踪起始 tick，10/06 v2：不读 AnimInstance 状态——
 *       跨线程读 Spark 状态机与 60Hz 物理线程竞态，误判会把刚入层的新实例误杀，
 *       动作被裁剪；也不信 isPlaying 层内检测——once 动画 animation_length 缺省
 *       9999999 时恒真，永久拦截后续挥砍）。挥砍窗口（1.0s）内上升沿忽略
 *       （= 原版物品行为，挥砍不可中断），attack 结算后看门狗恢复 3rd_still</li>
 * </ul>
 *
 * <p><b>尺寸（0.9375，2026-10-03 源码定案 / 2026-10-05 落地方式）</b>：原版玩家渲染 =
 * 32px×0.9375 = 1.875 块（PlayerRenderer.scale()，LivingEntityRenderer.render:102），
 * Spark 管线无此缩放。模型保持原版 1:1 几何（box-UV 矩形 = floor(size×16)，几何缩放会
 * 破坏 UV 采样 ⇒ 贴图放大错位），0.9375 由运行时施加：{@link #onBoneUpdate} 对本模型把
 * <b>root 骨 scale 钳到 (0.9375,0.9375,0.9375)</b>（root pivot=(0,0,0)，等价于原版
 * poseStack.scale：绕脚等比缩放全部子骨含动画位移；只改 scale 分量，root 的动画
 * position/rotation——base_state 各态的呼吸摆位——原样保留）。
 *
 * <p>切回原版：卸剑/左手持剑/离开 → stopAllAnimation + 恢复默认模型索引
 * （(entity, minecraft:player)：isPlayer()=true → PlayerRendererMixin 不接管，原版渲染；
 *  内置状态机继续对它播 base_state 动画 = 与全游戏其他玩家一致的 Spark 基线行为）。
 */
@EventBusSubscriber(modid = CatoxidesBattleRebuildConstants.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class SwordPlayerModelHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 手腕玩家模型索引（模型文件 spark_models/entity/&lt;ns&gt;/player_wrist.json，分段身体+叠加剑） */
    private static final ModelIndex PLAYER_WRIST_MODEL = new ModelIndex("entity",
            ResourceLocation.fromNamespaceAndPath(CatoxidesBattleRebuildConstants.MODID, "player_wrist"));
    // MAIN 组 = 剑图层（直接复用用户的剑物品动画 3rd_still/3rd_attack，驱动剑骨 main/left/right/shade）
    private static final AnimIndex ANIM_SWORD_IDLE = new AnimIndex(PLAYER_WRIST_MODEL, "3rd_still");
    private static final AnimIndex ANIM_SWORD_ATTACK = new AnimIndex(PLAYER_WRIST_MODEL, "3rd_attack");
    // STATE 组兜底动画 = base_state 状态动画（身体/腿/头/双臂，含 rightArm——
    // 与 Spark 默认玩家模型一致；状态机自动播放，Java 只在 STATE 层空时补播）
    private static final AnimIndex ANIM_STATE_IDLE = new AnimIndex(PLAYER_WRIST_MODEL, "state.land.idle");
    private static final AnimIndex ANIM_STATE_MOVE = new AnimIndex(PLAYER_WRIST_MODEL, "state.land.move");
    private static final AnimIndex ANIM_STATE_JUMP = new AnimIndex(PLAYER_WRIST_MODEL, "state.jump");
    private static final AnimIndex ANIM_STATE_FALL = new AnimIndex(PLAYER_WRIST_MODEL, "state.fall");

    /** 静态复合贴图 96×64 = 身体 64×64 @ (0,0) + 剑 32×64 @ (64,0)（gen_wrist_player_sword_v3.js 预生成）——动态皮肤合成失败时的回退 */
    private static final ResourceLocation WRIST_TEX =
            ResourceLocation.fromNamespaceAndPath(CatoxidesBattleRebuildConstants.MODID, "textures/entity/player_wrist.png");

    /** 当前激活（持剑叠加模型中）的玩家 */
    private static final Set<UUID> active = new HashSet<>();
    /** 挥击上升沿检测（与 IronSwordWeapon 同款逻辑，独立跟踪） */
    private static final Map<UUID, Boolean> lastSwing = new HashMap<>();
    /** 诊断：MAIN 层空区间起点 tick（节流日志用，10/05「挥砍消失」定位） */
    private static final Map<UUID, Long> mainEmptySince = new HashMap<>();
    /**
     * 当前在播的 3rd_attack 实例 + 起始 tick（10/06「只能挥砍一次」+「动作被裁剪」修复）。
     * 所有判定<b>纯时间</b>（clientTick 差值）：不读 AnimInstance.getState()——它是
     * 跨线程读 Spark 状态机，与 60Hz 物理线程的状态写入竞态，误判"不存活"会把刚入层
     * 的新实例立刻移除（=用户实测"整个动作被裁剪"的根因）；也不信 isPlaying 层内
     * 检测（once 动画 animation_length 缺省 9999999 时恒真，永久拦截后续挥砍）。
     */
    private static final Map<UUID, AnimInstance> liveAttack = new HashMap<>();
    private static final Map<UUID, Long> attackStartTick = new HashMap<>();
    /**
     * 挥砍窗口（client tick，20t/s）：20 tick = 1.0s。attack 实际总时长 ≈0.52s 真实
     * 时间——Spark 物理 TPS=60（PhysicsLevel.TPS 字节码定案），动画时间=真实时间 1:1，
     * in/out 渐入渐出已关（v2）：WORK 0.5s 后 Finish。窗口内上升沿忽略（原版物品
     * 同行为：挥砍不可中断），保证当前挥砍完整播完不被中途清层裁剪；主动交接在
     * 0.5s（ATTACK_END_TICKS）发生后，窗口实际只覆盖挥砍前半程。
     */
    private static final long SWING_WINDOW_TICKS = 20;
    /**
     * attack 总时长预算（client tick）：60 tick = 3s（0.8s 实际时长的 3.75 倍）——
     * 僵尸兜底线：once 生命周期未结算（实例超预算仍在层内）时看门狗强制清层。
     */
    private static final long ATTACK_BUDGET_TICKS = 60;
    /**
     * 挥砍结束主动交接点（client tick）：10 tick = 0.5s。in/out 渐入渐出已关（v2），
     * attack 实际总时长 ≈0.52s（60Hz 物理线程：30 个 physTick 走完 0.5s 关键帧后 Finish）；
     * 结束瞬间实例进 EXIT，weight 立即归零（Spark 字节码：outTransitionTick==0 ⇒
     * currentWeight=0）→ 剑跳到"无动画"零姿态（=模型默认位置）。0.5s 时关键帧已回到
     * 静止位，此刻先放 3rd_still 入层再移除 attack，剑全程停在原地，无缝隙。
     */
    private static final long ATTACK_END_TICKS = 10;
    // TODO（常备方案，用户 10/06 指示保留、暂不实施）：Spark 完全接管整个玩家——不做
    // 每次切物品的 setModel 切换，ModelInstance/BonePose 初始化只在实体生成时做一次
    // （发生在玩家可见前，零姿态窗口天然不可见）；剑平时挂在身上（静止位=挂位），
    // 战斗选中时用"拔剑"动画取到手里。实施后模型切换闪烁结构性消失，但仍需本文件的
    // 挥砍结束交接 + 看门狗（实例生命周期间隙与模型切换无关）。
    // 待用户决策：是否对所有玩家常备 / 挂位位置 / 拔剑触发（切热栏 or 首次攻击）/
    // 战斗后留手 or 自动收鞘 / 拔剑动画时长。

    /**
     * 每玩家的动态皮肤复合贴图（身体=玩家本人皮肤 + 剑）。
     * texture==null 表示合成失败/皮肤未就绪（用静态 Steve 贴图，每 20 tick 重试）。
     */
    private record SkinOverlay(ResourceLocation texture, ResourceLocation skin) {}
    private static final Map<UUID, SkinOverlay> overlays = new HashMap<>();
    /** 客户端 tick 计数（皮肤重合成重试节流用） */
    private static int clientTick = 0;

    /**
     * 原版渲染缩放（源码定案）：PlayerRenderer.scale() 对所有玩家渲染 ×0.9375
     * （1.21.1 反编译 L159–162，LivingEntityRenderer.render:102 调用）⇒ 原版玩家
     * 32px 模型实际渲染 30px = 1.875 块。本模型经 root 骨运行时缩放对齐（见类注释）。
     */
    private static final double VANILLA_RENDER_SCALE = 0.9375;
    private static final Vec3 ROOT_SCALE = new Vec3(VANILLA_RENDER_SCALE, VANILLA_RENDER_SCALE, VANILLA_RENDER_SCALE);

    /**
     * 0.9375 缩放钳制（2026-10-05）：Spark 每 tick 对每根骨重算 pose 并发 BoneUpdateEvent
     * （AnimController.physTick→blendBone 把未被动画层提到的骨重置为 scale=1），
     * 这里对 player_wrist 模型的 root 骨把 scale 分量钉在 0.9375——
     * 只替换 scale，position/rotation（base_state 的 root 呼吸摆位）原样保留。
     * 对所有玩家（本地/远程持剑者）生效，且对将来新增的状态/剑动画自动生效。
     */
    @SubscribeEvent
    static void onBoneUpdate(BoneUpdateEvent event) {
        var mi = event.getModel();
        if (mi == null || !PLAYER_WRIST_MODEL.equals(mi.getIndex())) {
            return;
        }
        if (!"root".equals(event.getBonePose().getName())) {
            return;
        }
        var kt = event.getNewTransform();
        event.setNewTransform(new KeyAnimData(kt.getPosition(), kt.getRotation(), ROOT_SCALE));
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        clientTick++;

        for (AbstractClientPlayer player : mc.level.players()) {
            try {
            boolean holding = player.getMainHandItem().is(ModWeapons.IRON_SWORD_WEAPON.get())
                    && player.getMainArm() == HumanoidArm.RIGHT;

            if (holding && !active.contains(player.getUUID())) {
                activate(player);
            } else if (!holding && active.contains(player.getUUID())) {
                deactivate(player);
                continue;
            }
            if (!active.contains(player.getUUID())) {
                continue;
            }
            tickAnimations(player);
            } catch (Exception e) {
                // 防御：单个玩家的处理异常不能炸掉整个客户端 tick
                LOGGER.error("[SwordPlayerModel] tick error for {}",
                        player.getGameProfile().getName(), e);
            }
        }

        // 清理已离开世界的玩家：挥击记录 + 激活状态 + 动态纹理释放
        lastSwing.keySet().removeIf(id -> {
            if (mc.level.getPlayerByUUID(id) != null) {
                return false;
            }
            releaseOverlayTexture(overlays.remove(id));
            active.remove(id);
            liveAttack.remove(id);
            attackStartTick.remove(id);
            mainEmptySince.remove(id);
            return true;
        });
    }

    @SubscribeEvent
    static void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof net.minecraft.world.level.Level lvl) || !lvl.isClientSide) {
            return;
        }
        // 实体随关卡销毁：清激活集合（新关卡的新实体对象需重新激活）
        for (SkinOverlay ov : overlays.values()) {
            releaseOverlayTexture(ov);
        }
        overlays.clear();
        active.clear();
        lastSwing.clear();
        mainEmptySince.clear();
        liveAttack.clear();
        attackStartTick.clear();
    }

    /** 释放动态复合贴图（texture==null 时 no-op） */
    private static void releaseOverlayTexture(SkinOverlay ov) {
        if (ov == null || ov.texture() == null) {
            return;
        }
        try {
            Minecraft.getInstance().getTextureManager().release(ov.texture());
        } catch (Exception ignored) {
            // 卸世界时 TextureManager 可能已重置
        }
    }

    /**
     * 激活：setModel（分段身体+叠加剑）→ setTextureLocation（动态「玩家皮肤+剑」，
     * 失败回退静态复合贴图）→ 播放 3rd_still + 状态动画
     */
    private static void activate(AbstractClientPlayer player) {
        try {
            IEntityAnimatable<?> animatable = (IEntityAnimatable<?>) player;
            animatable.getModelController().setModel(PLAYER_WRIST_MODEL);
            seedSwordRestPose(animatable);   // 10/06：首帧预置剑骨静止位（消除激活/切物品的"默认位置"闪现）
            logModelHeight(player, "叠加层");   // [测高] 叠加层模型（rest 姿态；预期 1.875 块 = vanilla 0.9375 缩放）
            ResourceLocation tex = WRIST_TEX;
            try {
                ResourceLocation dyn = WristSkinComposer.composeAndRegister(
                        Minecraft.getInstance(), player);
                if (dyn != null) {
                    tex = dyn;
                    overlays.put(player.getUUID(),
                            new SkinOverlay(dyn, player.getSkin().texture()));
                } else {
                    // 皮肤未就绪/legacy → 先用静态贴图，tick 里重试
                    overlays.put(player.getUUID(),
                            new SkinOverlay(null, player.getSkin().texture()));
                }
            } catch (Exception e) {
                LOGGER.warn("[SwordPlayerModel] 皮肤合成异常，用静态贴图: {}", e.getMessage());
                overlays.put(player.getUUID(), new SkinOverlay(null, player.getSkin().texture()));
            }
            animatable.getModelController().setTextureLocation(tex);
            play(animatable, ANIM_SWORD_IDLE, AnimGroups.MAIN);
            play(animatable, desiredStateAnim(player), AnimGroups.STATE);
            active.add(player.getUUID());
            LOGGER.info("[SwordPlayerModel] activated for {} (tex={})",
                    player.getGameProfile().getName(), tex);
            logOverlayDiag(animatable);
        } catch (Exception e) {
            LOGGER.error("[SwordPlayerModel] failed to activate for {}", player.getGameProfile().getName(), e);
        }
    }

    /**
     * 叠加层诊断（确认用）：把激活后的关键链路状态打进日志——
     * 模型索引 / 贴图路径(及 classpath 是否真有该资源) / OModel 骨数与剑 cube 数 / 贴图尺寸。
     * 若游戏里"没变化"，对照本行即可定位断在哪一环。
     */
    private static void logOverlayDiag(IEntityAnimatable<?> animatable) {
        try {
            var mi = animatable.getModelController().getModel();
            boolean res = Minecraft.getInstance().getResourceManager().getResource(WRIST_TEX).isPresent();
            if (mi == null) {
                LOGGER.warn("[SwordPlayerModel][diag] ModelInstance == null（setModel 未生效?） tex资源={}", res);
                return;
            }
            var om = mi.getOrigin();
            int swordCubes = 0;
            for (String bn : new String[]{"shade", "blade", "handle"}) {
                var b = om.getBone(bn);
                if (b != null) swordCubes += b.getCubes().size();
            }
            LOGGER.info("[SwordPlayerModel][diag] model={} tex={} 资源存在={} OModel骨数={} 剑cube数={} 贴图尺寸={}x{}",
                    mi.getIndex(), mi.getTextureLocation(), res,
                    om.getBones().size(), swordCubes, om.getTextureWidth(), om.getTextureHeight());
        } catch (Exception e) {
            LOGGER.warn("[SwordPlayerModel][diag] 诊断失败: {}", e.getMessage());
        }
    }

    /** 身体骨名（测高用；排除 leftItem/rightItem 及其下的剑骨——剑会探出头顶，不属于身体高度） */
    private static final Set<String> BODY_BONES = Set.of(
            "root", "body", "waist", "head", "leftArm", "rightArm", "leftLeg", "rightLeg");

    /**
     * 身高测量（诊断——「叠加层是否比原版大」定案实验，2026-10-03 / 10-05 修订）：
     * activate/deactivate 后自动对当前模型实例打两条日志（同一把尺，数字直接可比）。
     * OModel 数值在解析期已 ÷16（块单位；OPolygon 的 box-UV 再 ×16 floor 回像素）：
     * <ol>
     *   <li><b>几何高度</b> = 身体骨 cube 包围盒（模型空间，<b>含 inflate</b>——帽层
     *       0.5px 必须计入，否则会被误读）× root 有效缩放 = 渲染尺寸；</li>
     *   <li><b>渲染高度</b> = 脚(root 世界 pivot) → 头顶(head 顶面 cube 顶点，含帽层 inflate)
     *       × root 有效缩放。</li>
     * </ol>
     * <b>root 有效缩放</b> = root 骨当前 pose scale；player_wrist 被 {@link #onBoneUpdate}
     * 钳在 0.9375（setModel 后首 tick 前钳制未生效时按预期值 0.9375 计），其余模型 = 1。
     * <b>预期（10/05 定案）</b>：叠加层（player_wrist，1:1 几何 + root×0.9375）渲染
     * y∈[0, 1.904] 块（30.47px 含帽层）、头顶 1.875 块——与原版渲染完全一致
     * （PlayerRenderer.scale()=0.9375，LivingEntityRenderer.render:102；32px×0.9375=30px=1.875 块）。
     * 基线（Spark 内置 minecraft:player，无钳制）= 2.000 块——那是 Spark 参考模型，
     * <b>不是</b>原版实际渲染值（此前用它当标尺导致误判「两管线等大」）。
     */
    private static void logModelHeight(AbstractClientPlayer player, String label) {
        try {
            IEntityAnimatable<?> animatable = (IEntityAnimatable<?>) player;
            var mi = animatable.getModelController().getModel();
            if (mi == null) {
                LOGGER.info("[SwordPlayerModel][测高] {} 无模型实例", label);
                return;
            }
            float pt = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
            // ① 几何：身体骨 cube 包围盒（模型空间，块，含 inflate）+ 头顶（含帽层 inflate）
            double minY = Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            double headTopLocal = Double.NEGATIVE_INFINITY;
            for (var e : mi.getOrigin().getBones().entrySet()) {
                if ("head".equals(e.getKey())) {
                    for (var c : e.getValue().getCubes()) {
                        headTopLocal = Math.max(headTopLocal, c.getOriginPos().y + c.getSize().y + c.getInflate());
                    }
                }
                if (!BODY_BONES.contains(e.getKey())) {
                    continue;
                }
                for (var c : e.getValue().getCubes()) {
                    minY = Math.min(minY, c.getOriginPos().y - c.getInflate());
                    maxY = Math.max(maxY, c.getOriginPos().y + c.getSize().y + c.getInflate());
                }
            }
            // ② root 有效缩放：本模型钳制在 0.9375；setModel 后首 tick 前钳制未生效（scale 仍 1）按预期值计
            boolean ours = PLAYER_WRIST_MODEL.equals(mi.getIndex());
            var rootBP = mi.getPose().getBonePose("root");
            double rsActual = (rootBP != null) ? rootBP.getLocalScale(pt).x() : 1.0;
            double rs = (ours && rsActual > 0.99) ? VANILLA_RENDER_SCALE : rsActual;
            // ③ 渲染高度：脚(root 世界 pivot) + 头顶(模型空间 × rs)
            String rendered;
            if (rootBP == null || headTopLocal == Double.NEGATIVE_INFINITY) {
                rendered = "缺 root/head 骨，未测";
            } else {
                org.joml.Vector3f feet = rootBP.getWorldBonePivot(Vec3.ZERO, pt);
                double headTop = feet.y + headTopLocal * rs;
                rendered = String.format("脚y=%.3f 头顶y=%.3f 渲染高度=%.3f块 (root scale=%.4f)",
                        feet.y, headTop, headTop - feet.y, rs);
            }
            String geom = (minY > maxY) ? "无身体cube"
                    : String.format("身体y∈[%.3f, %.3f]块(=%.2fpx) 高=%.3f块",
                            minY * rs, maxY * rs, (maxY - minY) * 16.0 * rs, (maxY - minY) * rs);
            LOGGER.info("[SwordPlayerModel][测高] {} 模型={} | 几何(渲染尺寸): {} | {}（原版渲染=1.875块：PlayerRenderer.scale 0.9375）",
                    label, mi.getIndex(), geom, rendered);
        } catch (Exception e) {
            LOGGER.warn("[SwordPlayerModel][测高] {} 测量失败: {}", label, e.getMessage());
        }
    }

    /**
     * 停用：停动画 → 恢复默认模型索引（回到原版渲染）。
     * 注意：不能 setModel(null)——Spark 内置 player_base_state 状态机对所有玩家常开，
     * 状态切换时 animInstance 按当前模型查动画，模型为 null 会在 Spark 代码里 NPE 崩游戏
     * （crash-2026-10-01_06.56.02：卸剑后移动即触发）。
     */
    private static void deactivate(AbstractClientPlayer player) {
        try {
            IEntityAnimatable<?> animatable = (IEntityAnimatable<?>) player;
            animatable.getAnimController().stopAllAnimation();
            animatable.getModelController().setModel(animatable.getDefaultModelIndex());
            logModelHeight(player, "基线");   // [测高] Spark 内置参考模型（rest 姿态；2.000 块=Spark 自身模型，非原版渲染值）
        } catch (Exception e) {
            LOGGER.warn("[SwordPlayerModel] failed to deactivate for {}: {}",
                    player.getGameProfile().getName(), e.getMessage());
        }
        releaseOverlayTexture(overlays.remove(player.getUUID()));
        active.remove(player.getUUID());
        lastSwing.remove(player.getUUID());
        liveAttack.remove(player.getUUID());
        attackStartTick.remove(player.getUUID());
        mainEmptySince.remove(player.getUUID());
        LOGGER.info("[SwordPlayerModel] deactivated for {}", player.getGameProfile().getName());
    }

    /** 每 tick 动画维护：动态皮肤贴图维护；挥击触发 attack；MAIN 层空则恢复 still；STATE 层按速度切换 */
    private static void tickAnimations(AbstractClientPlayer player) {
        IEntityAnimatable<?> animatable = (IEntityAnimatable<?>) player;
        var controller = animatable.getAnimController();

        maintainSkinOverlay(player, animatable);

        // 挥击上升沿 → 3rd_attack（10/06 v2）：窗口判定纯时间（不读状态——跨线程
        // 读 Spark 状态机与 60Hz 物理线程竞态，误判会误杀刚入层的新实例，动作被裁剪）。
        // 窗口内上升沿忽略 = 挥砍不可中断（与原版物品行为一致），当前挥砍完整播完。
        boolean swinging = player.swinging;
        boolean was = lastSwing.getOrDefault(player.getUUID(), false);
        if (swinging && !was) {
            if (!inSwingWindow(player.getUUID())) {
                purgeMainLayer(animatable, player);   // 清层（僵尸 attack + 旧 still）
                AnimInstance inst = play(animatable, ANIM_SWORD_ATTACK, AnimGroups.MAIN);
                if (inst != null) {
                    liveAttack.put(player.getUUID(), inst);
                    attackStartTick.put(player.getUUID(), (long) clientTick);
                }
                LOGGER.info("[SwordPlayerModel][swing] {} 上升沿 → 3rd_attack（新实例）",
                        player.getGameProfile().getName());
            } else {
                LOGGER.info("[SwordPlayerModel][swing] {} 上升沿忽略（挥砍窗口内 age={} tick）",
                        player.getGameProfile().getName(),
                        clientTick - attackStartTick.getOrDefault(player.getUUID(), 0L));
            }
        }
        lastSwing.put(player.getUUID(), swinging);

        // 挥砍结束主动交接（10/06 v4）：attack 结束时 Spark weight 立即归零（EXIT 零姿态
        // = 默认位置），而 v2 看门狗还卡着 1s 挥砍窗口不许补 still ⇒ 剑在默认位置停一阵
        // 才"回到手里"（用户 07:53 实测"先回默认大概 0.2s 再回到手里"）。
        // 修法：被跟踪的 attack 到 0.5s（关键帧已回静止位）→ 先放 3rd_still 入层，再把
        // attack 从层里移除（实例不再被物理线程 tick，直接丢弃）：剑全程停在静止位，
        // 无零姿态间隙；同时清除跟踪，下一次挥砍立即可触发（与原版物品行为一致）。
        Long atkStart = attackStartTick.get(player.getUUID());
        if (atkStart != null && clientTick - atkStart >= ATTACK_END_TICKS) {
            AnimInstance atk = liveAttack.remove(player.getUUID());
            attackStartTick.remove(player.getUUID());
            AnimLayer handoffLayer = controller.getLayers().get(AnimGroups.MAIN);
            if (atk != null && handoffLayer != null) {
                boolean hasStill = false;
                for (AnimInstance a : handoffLayer.getAnimations()) {
                    if (a != atk && a.getAnimIndex() != null
                            && ANIM_SWORD_IDLE.getName().equals(a.getAnimIndex().getName())) {
                        hasStill = true;
                        break;
                    }
                }
                if (!hasStill) {
                    play(animatable, ANIM_SWORD_IDLE, AnimGroups.MAIN);
                }
                handoffLayer.getAnimations().remove(atk);
                LOGGER.info("[SwordPlayerModel][handoff] {} 挥砍结束交接：still 就位、attack 离层（age={} tick）",
                        player.getGameProfile().getName(), clientTick - atkStart);
            }
        }

        // MAIN 看门狗（10/06 v4）：⚠️ 永不移除刚入层的实例（状态读竞态误杀=裁剪根因）。
        // 层空 → 立即恢复 3rd_still（v2 的"无年轻 attack"门去掉——1s 窗口只管上升沿
        // 判定，若等窗口过才补 still，挥砍结束后剑会停在默认位置一段时间）；
        // 超总时长预算（僵尸）→ 强制清层兜底。
        AnimLayer mainLayer = controller.getLayers().get(AnimGroups.MAIN);
        if (exceedsBudget(player.getUUID())) {
            purgeMainLayer(animatable, player);
            mainLayer = controller.getLayers().get(AnimGroups.MAIN);
        }
        if (mainLayer == null || mainLayer.getAnimations().isEmpty()) {
            play(animatable, ANIM_SWORD_IDLE, AnimGroups.MAIN);
            Long since = mainEmptySince.get(player.getUUID());
            if (since == null || clientTick - since >= 20) {
                LOGGER.info("[SwordPlayerModel][watchdog] {} MAIN 空 → 恢复 3rd_still（空区间 {} tick）",
                        player.getGameProfile().getName(), since == null ? "?" : (clientTick - since));
                mainEmptySince.put(player.getUUID(), (long) clientTick);
            }
        } else {
            mainEmptySince.remove(player.getUUID());
        }

        // STATE 组由 Spark 内置 PlayerBaseAnimStateMachine 驱动：base_state.json（20 个状态动画，
        // 专为 Spark 默认玩家模型设计）已注册到 player_wrist 模型，状态机在 站立/走/跑/蹲/游/飞/跳/落
        // 等状态切换时自动播放对应 state.* 动画（=「把默认动画叠上去」）。
        // 这里只做兜底：STATE 层空时补播当前近似状态（激活初始态/状态间隙），不覆盖状态机正在播的动画。
        AnimLayer stateLayer = controller.getLayers().get(AnimGroups.STATE);
        if (stateLayer == null || !stateLayer.isPlaying()) {
            play(animatable, desiredStateAnim(player), AnimGroups.STATE);
        }
    }

    /**
     * 状态动画选择：
     * 离地 → 上升（vy>0.1）播 state.jump（一次性），下降播 state.fall（循环）；
     * 地面且有水平速度 → move，否则 idle。
     * （跳跃初速 0.42 块/tick、重力 0.08，上升约 0.26s；state.jump 长 0.375s，
     * 顶点前已被 fall 接管，不会自己播完。鞘翅/游泳/潜行未接，按 fall/idle/move 近似。）
     */
    private static AnimIndex desiredStateAnim(AbstractClientPlayer player) {
        if (!player.onGround()) {
            return player.getDeltaMovement().y > 0.1 ? ANIM_STATE_JUMP : ANIM_STATE_FALL;
        }
        if (player.getDeltaMovement().horizontalDistanceSqr() > 0.01) {
            return ANIM_STATE_MOVE;
        }
        return ANIM_STATE_IDLE;
    }

    /**
     * 动态皮肤贴图维护：
     * 皮肤位置变化（游戏内换肤/重同步）→ 释放旧纹理、重新合成、切换；
     * 之前合成失败（texture==null，皮肤未就绪/legacy 等）→ 每 20 tick 重试一次。
     */
    private static void maintainSkinOverlay(AbstractClientPlayer player, IEntityAnimatable<?> animatable) {
        SkinOverlay ov = overlays.get(player.getUUID());
        if (ov == null) {
            return;
        }
        ResourceLocation skinNow;
        try {
            skinNow = player.getSkin().texture();
        } catch (Exception e) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (ov.texture() != null) {
            if (!skinNow.equals(ov.skin())) {
                releaseOverlayTexture(ov);
                overlays.remove(player.getUUID());
                try {
                    ResourceLocation dyn = WristSkinComposer.composeAndRegister(mc, player);
                    overlays.put(player.getUUID(), new SkinOverlay(dyn, skinNow));
                    animatable.getModelController().setTextureLocation(dyn != null ? dyn : WRIST_TEX);
                } catch (Exception e) {
                    LOGGER.warn("[SwordPlayerModel] 皮肤重合成失败，回退静态贴图: {}", e.getMessage());
                    overlays.put(player.getUUID(), new SkinOverlay(null, skinNow));
                    animatable.getModelController().setTextureLocation(WRIST_TEX);
                }
            }
        } else if (clientTick % 20 == 0) {
            try {
                ResourceLocation dyn = WristSkinComposer.composeAndRegister(mc, player);
                if (dyn != null) {
                    overlays.put(player.getUUID(), new SkinOverlay(dyn, skinNow));
                    animatable.getModelController().setTextureLocation(dyn);
                }
            } catch (Exception ignored) {
                // 重试失败保持静态贴图，下轮再试
            }
        }
    }

    /** 挥砍窗口内（10/06 v2）：有被跟踪 attack 且未超窗口。纯时间判定，无跨线程状态读。 */
    private static boolean inSwingWindow(UUID id) {
        Long start = attackStartTick.get(id);
        return start != null && clientTick - start < SWING_WINDOW_TICKS;
    }

    /** 已超总时长预算（僵尸兜底线）。 */
    private static boolean exceedsBudget(UUID id) {
        Long start = attackStartTick.get(id);
        return start != null && clientTick - start >= ATTACK_BUDGET_TICKS;
    }

    /**
     * 强制清空 MAIN 层（10/06）：移除全部实例——含 Spark 未能自行移除的"僵尸"
     * （once 动画卡 WORK/EXIT 永驻层）与旧 still。正常路径下 attack 结算后 Spark
     * 会自行移除（IDLE→removeIf）；此清理是兜底，保证挥砍永不被残留实例拦截。
     */
    private static void purgeMainLayer(IEntityAnimatable<?> animatable, AbstractClientPlayer player) {
        AnimLayer layer = animatable.getAnimController().getLayers().get(AnimGroups.MAIN);
        if (layer != null) {
            layer.getAnimations().clear();
        }
        liveAttack.remove(player.getUUID());
        attackStartTick.remove(player.getUUID());
    }

    /**
     * 剑骨静止位预置表（10/06「闪现」修复 v3）：值 = 3rd_still t=0 关键帧（A=I，物品原值
     * 直接拷贝，文件系坐标）——与第一个物理 tick 的 blend 结果完全一致（blendBone 字节码：
     * weight=1 时 pos=lerp(0,t0,1)=t0、rot=slerp(I→R(t0),1)≈t0、scale=lerp(1,t0s,1)=t0s）。
     */
    private static final Map<String, KeyAnimData> SWORD_REST_POSES = Map.of(
            "main", new KeyAnimData(new Vec3(-6, -2, 12.3), new Vec3(0, 90, 0), new Vec3(1, 1, 1)),
            "left", new KeyAnimData(new Vec3(0, 0, 0), new Vec3(90, 0, 0), new Vec3(1, 1, 1)),
            "right", new KeyAnimData(new Vec3(0, 0, 0), new Vec3(60, 0, 0), new Vec3(1, 1, 1)),
            "shade", new KeyAnimData(new Vec3(0, 0, 0), new Vec3(0, 0, 0), new Vec3(0, 1, 1)));
    private static final java.util.List<java.lang.reflect.Field> bonePoseFields = new java.util.ArrayList<>();
    private static boolean bonePoseFieldsFailed = false;

    /**
     * 首帧预置剑骨 BonePose 为静止位（10/06「闪现」修复 v3）。
     *
     * <p>v2（关 in/out 渐入）后仍残留"零姿态窗口"：全新 ModelInstance 的 BonePose 初始为
     * 空 KeyAnimData（=零姿态：剑骨无动画位移/旋转，剑不在手里），要等 setModel 之后的
     * 第一个 60Hz 物理 tick 才更新到静止位 ⇒ 激活/切物品时剑先以默认姿态渲染 1–2 帧
     * （16–32ms，低帧率下更明显）再跳进手里（用户 07:06 实机："还是闪现"）。
     *
     * <p>修法：setModel 后立刻把 main/left/right/shade 四剑骨的 BonePose 三个变换字段
     * （oLocalTransform/localTransform/internalTransform）直接写成静止位 ⇒ 第一帧起
     * 剑就在手里。字段私有（updateInternal 是 internal 混淆名，外部不可调）⇒ 反射；
     * 物理线程首 tick 会以同值覆盖，无竞态。反射失败（Spark 升级改字段）→ 记一次日志
     * 回退 v2 行为（≤2 帧过渡），绝不崩。
     */
    private static void seedSwordRestPose(IEntityAnimatable<?> animatable) {
        if (bonePoseFieldsFailed) {
            return;
        }
        try {
            if (bonePoseFields.isEmpty()) {
                for (String f : new String[]{"oLocalTransform", "localTransform", "internalTransform"}) {
                    java.lang.reflect.Field field = BonePose.class.getDeclaredField(f);
                    field.setAccessible(true);
                    bonePoseFields.add(field);
                }
            }
            var mi = animatable.getModelController().getModel();
            if (mi == null) {
                return;
            }
            var pose = mi.getPose();
            for (Map.Entry<String, KeyAnimData> e : SWORD_REST_POSES.entrySet()) {
                BonePose bp = pose.getBonePoseOrCreateEmpty(e.getKey());
                for (java.lang.reflect.Field field : bonePoseFields) {
                    field.set(bp, e.getValue());
                }
            }
        } catch (Throwable ex) {
            bonePoseFieldsFailed = true;
            LOGGER.warn("[SwordPlayerModel] 剑骨静止位预置失败（回退 ≤2 帧过渡）: {}", ex.getMessage());
        }
    }

    /**
     * 创建 AnimInstance 并入组播放（enter 提交到物理层 + playAnimation 加入层）。返回实例供自跟踪；失败 null。
     *
     * <p>10/06「初始化先到默认位置、下一 tick 才移到手里」修复：AnimInstance 构造器硬编码
     * inTransitionTime=outTransitionTime=0.15f（字节码定案）——每次新实例入层都要从
     * weight=0（=模型默认姿态，剑不在手里）渐入到 weight=1（=静止位）共 0.15s，表现为
     * 剑先出现在默认位置、~3 tick 内滑进手里（激活/每次挥砍结束恢复 still 都会闪一下）。
     * 置 0 后 stepWeight 在 ENTER 首个 physTick 即把 currentWeight 跳到 1（字节码：
     * inTransitionTick==0 ⇒ currentWeight=weight 并立即 TransitionFinish），只剩渲染
     * partialTicks 插值的 ≤1 帧过渡（16ms，不可感知）。out 同理：挥砍结束实例在静止位
     * 立即离层，不再从手里滑回默认位置。
     * ⚠️ 不能用"模型默认值烘焙静止位"替代：Spark 骨变换 = T(pivot)·T(animPos)·
     * R(animRot+静态Rot)·S(animScale)·T(−pivot)（BonePose.getLocalTransformMatrix 字节码）
     * ——静态 rotation 是<b>加法</b>（烘焙 t0 会双倍旋转），OBone 根本没有 position/scale
     * 静态字段（只有 pivot+rotation），位置/缩放只能来自动画 ⇒ 数据侧无解，只能关渐入。
     */
    private static AnimInstance play(IEntityAnimatable<?> animatable, AnimIndex index, int group) {
        try {
            AnimInstance anim = new AnimInstance(animatable, index);
            anim.setGroup(group);
            anim.setInTransitionTime(0f);
            anim.setOutTransitionTime(0f);
            anim.enter();
            animatable.getAnimController().playAnimation(anim);
            return anim;
        } catch (Exception e) {
            LOGGER.warn("[SwordPlayerModel] failed to play {}: {}", index.getName(), e.getMessage());
            return null;
        }
    }
}
