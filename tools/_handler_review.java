package com.catoxide.catoxidesbattlerebuild.client;

import cn.solarmoon.spark_core.animation.IEntityAnimatable;
import cn.solarmoon.spark_core.animation.anim.AnimGroups;
import cn.solarmoon.spark_core.animation.anim.AnimInstance;
import cn.solarmoon.spark_core.animation.anim.AnimLayer;
import cn.solarmoon.spark_core.animation.anim.origin.AnimIndex;
import cn.solarmoon.spark_core.animation.model.ModelIndex;
import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.registry.ModWeapons;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.HumanoidArm;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.slf4j.Logger;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家剑模型处理器（客户端）。
 *
 * <p>当玩家右手持铁剑（且主手为右手）时，把该玩家从原版模型切换到
 * Spark 自定义模型 {@code (entity, <modid>:player_sword)}：
 * <ul>
 *   <li>该模型 = 原版玩家模型（player.json 基础骨）+ 嵌入的剑手臂骨骼链
 *       （right/main/shade/blade/handle，由 tools/gen_player_sword_model.js 生成，
 *       刚体变换使剑的世界姿态与物品模型逐项一致）；</li>
 *   <li>原版皮肤手臂 rightArm 被重挂到我们的 right 骨之下（绑定），
 *       因此皮肤手臂 1:1 跟随我们的手臂动画（挥剑时手跟着动）；</li>
 *   <li>状态动画（state.land.idle/move，来自 base_state 且已删除 rightArm 关键帧）
 *       驱动身体/腿/头，使持剑时行走/站立姿势自然。</li>
 * </ul>
 *
 * <p>贴图：96×64 运行时复合贴图 = 玩家皮肤 64×64 @ (0,0) + 剑贴图 32×32 @ (64,0)，
 * 模型 UV 已按此布局生成（身体骨 UV 不变，剑 cube 的 face-UV u 整体 +64）。
 * 每个玩家按 (uuid, 皮肤位置) 缓存 DynamicTexture，切换皮肤时重建。
 *
 * <p>动画分工（AnimGroups）：
 * <ul>
 *   <li>STATE(1)：state.land.idle / state.land.move（每 tick 看门狗按速度切换）</li>
 *   <li>MAIN(4)：3rd_still（循环常驻）；挥击上升沿 → stopAnimation(MAIN) + 3rd_attack（once），
 *       attack 结束后 MAIN 层空 → 看门狗恢复 3rd_still</li>
 * </ul>
 *
 * <p>切回原版：卸剑/左手持剑/离开 → stopAllAnimation + setModel(null)
 * （ModelController.model == null 时 PlayerRendererMixin 直接跳过，走原版渲染）
 * + release 复合贴图。
 */
@EventBusSubscriber(modid = CatoxidesBattleRebuildConstants.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class SwordPlayerModelHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 自定义玩家模型索引（模型文件 models/&lt;ns&gt;/entity/player_sword.json） */
    private static final ModelIndex PLAYER_SWORD_MODEL = new ModelIndex("entity",
            ResourceLocation.fromNamespaceAndPath(CatoxidesBattleRebuildConstants.MODID, "player_sword"));
    private static final AnimIndex ANIM_3RD_STILL = new AnimIndex(PLAYER_SWORD_MODEL, "3rd_still");
    private static final AnimIndex ANIM_3RD_ATTACK = new AnimIndex(PLAYER_SWORD_MODEL, "3rd_attack");
    private static final AnimIndex ANIM_STATE_IDLE = new AnimIndex(PLAYER_SWORD_MODEL, "state.land.idle");
    private static final AnimIndex ANIM_STATE_MOVE = new AnimIndex(PLAYER_SWORD_MODEL, "state.land.move");

    /** 复合贴图尺寸：皮肤 64×64 @ (0,0)，剑 32×32 @ (64,0)，(64,32)-(96,64) 透明 */
    private static final int COMPOSITE_W = 96;
    private static final int COMPOSITE_H = 64;

    private static final ResourceLocation SWORD_TEX = ResourceLocation.fromNamespaceAndPath(
            CatoxidesBattleRebuildConstants.MODID, "textures/item/sword.png");

    private static final class PlayerSwordState {
        ResourceLocation textureLoc;
        String skinLoc;

        PlayerSwordState(String skinLoc) {
            this.skinLoc = skinLoc;
        }
    }

    /** 当前激活（持剑自定义模型中）的玩家及其复合贴图 */
    private static final Map<UUID, PlayerSwordState> active = new HashMap<>();
    /** 挥击上升沿检测（与 IronSwordWeapon 同款逻辑，独立跟踪） */
    private static final Map<UUID, Boolean> lastSwing = new HashMap<>();

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }

        for (AbstractClientPlayer player : mc.level.players()) {
            try {
            boolean holding = player.getMainHandItem().is(ModWeapons.IRON_SWORD_WEAPON.get())
                    && player.getMainArm() == HumanoidArm.RIGHT;
            PlayerSwordState state = active.get(player.getUUID());

            if (holding && state == null) {
                if (!activate(player)) {
                    // 皮肤尚未就绪（远程玩家下载中）→ 下一 tick 重试
                    continue;
                }
                // activate 成功后重新获取（循环开头的局部变量 state 仍是旧值 null）
                state = active.get(player.getUUID());
            } else if (!holding && state != null) {
                deactivate(player);
                continue;
            }
            if (!active.containsKey(player.getUUID())) {
                continue;
            }

            // 皮肤变化（换皮肤）→ 重建复合贴图
            PlayerSkin skin = player.getSkin();
            String skinKey = skin != null ? skin.texture().toString() : "";
            if (!skinKey.equals(state.skinLoc)) {
                if (!rebuildTexture(player, state)) {
                    deactivate(player);
                    continue;
                }
            }

            tickAnimations(player);
            } catch (Exception e) {
                // 防御：单个玩家的处理异常不能炸掉整个客户端 tick
                LOGGER.error("[SwordPlayerModel] tick error for {}",
                        player.getGameProfile().getName(), e);
            }
        }

        // 清理已离开世界的玩家的挥击记录
        lastSwing.keySet().removeIf(id -> mc.level == null || mc.level.getPlayerByUUID(id) == null);
    }

    @SubscribeEvent
    static void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof net.minecraft.world.level.Level lvl) || !lvl.isClientSide) {
            return;
        }
        for (PlayerSwordState state : active.values()) {
            releaseTexture(state);
        }
        active.clear();
        lastSwing.clear();
    }

    /**
     * 激活：构建复合贴图 → setModel → setTextureLocation → 播放 3rd_still + 状态动画。
     *
     * @return false 表示皮肤资源尚未就绪，稍后重试（不激活）
     */
    private static boolean activate(AbstractClientPlayer player) {
        Minecraft mc = Minecraft.getInstance();
        PlayerSkin skin = player.getSkin();
        ResourceLocation skinLoc = skin != null ? skin.texture() : null;
        if (skinLoc == null || !mc.getResourceManager().getResource(skinLoc).isPresent()) {
            return false;
        }
        try {
            IEntityAnimatable<?> animatable = (IEntityAnimatable<?>) player;
            PlayerSwordState state = new PlayerSwordState(skinLoc.toString());
            state.textureLoc = buildCompositeTexture(mc, skin, state);
            if (state.textureLoc == null) {
                return false;
            }
            active.put(player.getUUID(), state);
            animatable.getModelController().setModel(PLAYER_SWORD_MODEL);
            animatable.getModelController().setTextureLocation(state.textureLoc);
            play(animatable, ANIM_3RD_STILL, AnimGroups.MAIN);
            play(animatable, desiredStateAnim(player), AnimGroups.STATE);
            LOGGER.info("[SwordPlayerModel] activated for {} (texture {})", player.getGameProfile().getName(), state.textureLoc);
            return true;
        } catch (Exception e) {
            LOGGER.error("[SwordPlayerModel] failed to activate for {}", player.getGameProfile().getName(), e);
            return false;
        }
    }

    /** 停用：停动画 → setModel(null)（回到原版渲染）→ 释放贴图 */
    private static void deactivate(AbstractClientPlayer player) {
        try {
            IEntityAnimatable<?> animatable = (IEntityAnimatable<?>) player;
            animatable.getAnimController().stopAllAnimation();
            animatable.getModelController().setModel((ModelIndex) null);
        } catch (Exception e) {
            LOGGER.warn("[SwordPlayerModel] failed to deactivate for {}: {}",
                    player.getGameProfile().getName(), e.getMessage());
        }
        PlayerSwordState state = active.remove(player.getUUID());
        if (state != null) {
            releaseTexture(state);
        }
        lastSwing.remove(player.getUUID());
        LOGGER.info("[SwordPlayerModel] deactivated for {}", player.getGameProfile().getName());
    }

    /** 每 tick 动画维护：挥击触发 attack；MAIN 层空则恢复 still；STATE 层按速度切换 */
    private static void tickAnimations(AbstractClientPlayer player) {
        IEntityAnimatable<?> animatable = (IEntityAnimatable<?>) player;
        var controller = animatable.getAnimController();

        // 挥击上升沿 → 3rd_attack（先停 MAIN 避免与 still 50/50 混合）
        boolean swinging = player.swinging;
        boolean was = lastSwing.getOrDefault(player.getUUID(), false);
        if (swinging && !was) {
            if (!isPlaying(animatable, AnimGroups.MAIN, ANIM_3RD_ATTACK.getName())) {
                controller.stopAnimation(AnimGroups.MAIN);
                play(animatable, ANIM_3RD_ATTACK, AnimGroups.MAIN);
            }
        }
        lastSwing.put(player.getUUID(), swinging);

        // MAIN 看门狗：attack（once）播完层空 → 恢复 3rd_still 循环
        AnimLayer mainLayer = controller.getLayers().get(AnimGroups.MAIN);
        if (mainLayer == null || !mainLayer.isPlaying()) {
            play(animatable, ANIM_3RD_STILL, AnimGroups.MAIN);
        }

        // STATE 看门狗：idle / move 切换
        AnimIndex want = desiredStateAnim(player);
        if (!isPlaying(animatable, AnimGroups.STATE, want.getName())) {
            controller.stopAnimation(AnimGroups.STATE);
            play(animatable, want, AnimGroups.STATE);
        }
    }

    /** 状态动画选择：地面且有水平速度 → move，否则 idle（v1 不含蹲伏/飞行） */
    private static AnimIndex desiredStateAnim(AbstractClientPlayer player) {
        if (player.onGround() && player.getDeltaMovement().horizontalDistanceSqr() > 0.01) {
            return ANIM_STATE_MOVE;
        }
        return ANIM_STATE_IDLE;
    }

    /** 指定分组中是否正在播放名为 name 的动画 */
    private static boolean isPlaying(IEntityAnimatable<?> animatable, int group, String name) {
        AnimLayer layer = animatable.getAnimController().getLayers().get(group);
        if (layer == null) {
            return false;
        }
        for (AnimInstance anim : layer.getAnimations()) {
            if (name.equals(anim.getAnimIndex().getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 合成 96×64 贴图并注册为动态纹理：
     * 皮肤 64×64 @ (0,0) + 剑 32×32 @ (64,0)，其余保持透明（新建 NativeImage 全 0）。
     */
    private static ResourceLocation buildCompositeTexture(Minecraft mc, PlayerSkin skin, PlayerSwordState state) {
        try {
            NativeImage img = new NativeImage(COMPOSITE_W, COMPOSITE_H, false);

            // 1) 皮肤（现代皮肤 64×64；64×32 老格式只复制上半部，腿部 UV 透明——v1 可接受）
            var skinOpt = mc.getResourceManager().getResource(skin.texture());
            if (!skinOpt.isPresent()) {
                img.close();
                return null;
            }
            try (InputStream in = skinOpt.get().open()) {
                NativeImage s = NativeImage.read(in);
                int w = Math.min(COMPOSITE_W, s.getWidth());
                int h = Math.min(COMPOSITE_H, s.getHeight());
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        img.setPixelRGBA(x, y, s.getPixelRGBA(x, y));
                    }
                }
                s.close();
            }

            // 2) 剑贴图 @ (64,0)
            var swordOpt = mc.getResourceManager().getResource(SWORD_TEX);
            if (swordOpt.isPresent()) {
                try (InputStream in = swordOpt.get().open()) {
                    NativeImage s = NativeImage.read(in);
                    int w = Math.min(32, s.getWidth());
                    int h = Math.min(32, s.getHeight());
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            img.setPixelRGBA(64 + x, y, s.getPixelRGBA(x, y));
                        }
                    }
                    s.close();
                }
            } else {
                LOGGER.warn("[SwordPlayerModel] sword texture {} not found", SWORD_TEX);
            }

            state.skinLoc = skin.texture().toString();
            DynamicTexture dt = new DynamicTexture(img);
            return mc.getTextureManager().register("sword_player", dt);
        } catch (Exception e) {
            LOGGER.error("[SwordPlayerModel] failed to build composite texture", e);
            return null;
        }
    }

    /** 皮肤变化后重建复合贴图；失败返回 false（调用方负责回退原版） */
    private static boolean rebuildTexture(AbstractClientPlayer player, PlayerSwordState state) {
        Minecraft mc = Minecraft.getInstance();
        PlayerSkin skin = player.getSkin();
        if (skin == null) {
            return false;
        }
        releaseTexture(state);
        ResourceLocation loc = buildCompositeTexture(mc, skin, state);
        if (loc == null) {
            return false;
        }
        state.textureLoc = loc;
        try {
            IEntityAnimatable<?> animatable = (IEntityAnimatable<?>) player;
            animatable.getModelController().setTextureLocation(loc);
        } catch (Exception e) {
            LOGGER.warn("[SwordPlayerModel] failed to apply rebuilt texture: {}", e.getMessage());
        }
        LOGGER.info("[SwordPlayerModel] texture rebuilt for {} ({})", player.getGameProfile().getName(), loc);
        return true;
    }

    private static void releaseTexture(PlayerSwordState state) {
        if (state.textureLoc == null) {
            return;
        }
        try {
            Minecraft.getInstance().getTextureManager().release(state.textureLoc);
        } catch (Exception e) {
            // 纹理可能已被清理，忽略
        }
        state.textureLoc = null;
    }

    /** 创建 AnimInstance 并入组播放（enter 提交到物理层 + playAnimation 加入层） */
    private static void play(IEntityAnimatable<?> animatable, AnimIndex index, int group) {
        try {
            AnimInstance anim = new AnimInstance(animatable, index);
            anim.setGroup(group);
            anim.enter();
            animatable.getAnimController().playAnimation(anim);
        } catch (Exception e) {
            LOGGER.warn("[SwordPlayerModel] failed to play {}: {}", index.getName(), e.getMessage());
        }
    }
}
