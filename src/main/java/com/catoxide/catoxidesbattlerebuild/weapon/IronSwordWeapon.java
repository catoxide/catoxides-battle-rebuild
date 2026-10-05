package com.catoxide.catoxidesbattlerebuild.weapon;

import cn.solarmoon.spark_core.animation.ICustomModelItem;
import cn.solarmoon.spark_core.animation.ItemAnimatable;
import cn.solarmoon.spark_core.animation.anim.AnimGroups;
import cn.solarmoon.spark_core.animation.anim.AnimInstance;
import cn.solarmoon.spark_core.animation.anim.origin.AnimIndex;
import cn.solarmoon.spark_core.animation.model.ModelIndex;
import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.awt.Color;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 铁剑武器 - 实现 ICustomModelItem 接口以支持 Spark-Core 自定义模型。
 * 剑系物品共用 {@code sword} 模型（spark_models/item/&lt;modid&gt;/sword.json）与动画集
 * （spark_animations/item/&lt;modid&gt;/sword/*.json），不同材质后续用 setTextureLocation 区分纹理。
 * 第一/三人称共用同一模型，但使用不同动画：
 * <ul>
 *   <li>FP: still（循环 idle）/ attack（挥击）</li>
 *   <li>TP: 3rd_still / 3rd_attack</li>
 * </ul>
 * Spark 不会自动播放物品 idle 动画，实例创建时显式播放循环 idle。
 */
public class IronSwordWeapon extends Item implements ICustomModelItem {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 按 Level + Context 缓存 ItemAnimatable 实例
     * 第一人称（fp）和第三人称（tp）各自拥有独立的 ItemAnimatable，
     * 加载不同的模型和动画资源。
     * 按 Level 分离避免客户端/服务端跨线程并发 tick 崩溃。
     */
    private static final Map<Level, Map<ItemDisplayContext, ItemAnimatable>> cachedAnimatables = new ConcurrentHashMap<>();

    /**
     * 跟踪每个玩家上一帧的挥动状态，用于检测挥动开始（上升沿触发）
     */
    private static final Map<UUID, Boolean> lastSwingState = new ConcurrentHashMap<>();

    public IronSwordWeapon(Properties properties) {
        super(properties);
    }

    /**
     * 近战命中：扣耐久（可配置有限耐久，配合 DurabilitySupport 能力与降级系统）。
     * 伤害由攻击属性驱动（后续 weapon ③ 数据驱动化）。
     */
    @Override
    public boolean hurtEnemy(ItemStack stack, net.minecraft.world.entity.LivingEntity target,
                             net.minecraft.world.entity.LivingEntity attacker) {
        stack.hurtAndBreak(1, attacker, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
        return true;
    }

    @Override
    public boolean isEnchantable(ItemStack stack) {
        return true;
    }

    @Override
    public int getEnchantmentValue() {
        return 14;
    }

    /**
     * 判断 context 是否为第一人称视角
     */
    private static boolean isFirstPerson(ItemDisplayContext context) {
        return context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                || context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
    }

    /**
     * 物品在背包中每 tick 调用
     * 在客户端检测玩家开始挥动武器时，在 MAIN layer 播放 attack 动画
     * 同时触发第一人称和第三人称实例的 attack 动画
     */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        super.inventoryTick(stack, level, entity, slotId, isSelected);
        if (!level.isClientSide || !isSelected || !(entity instanceof Player player)) {
            return;
        }

        boolean wasSwinging = lastSwingState.getOrDefault(player.getUUID(), false);
        boolean isSwinging = player.swinging;
        if (isSwinging && !wasSwinging) {
            triggerAttackAnimation(stack, level);
        }
        lastSwingState.put(player.getUUID(), isSwinging);
    }

    /**
     * 在 MAIN layer 播放 attack 动画
     * 同时触发第一人称和第三人称实例，确保两个视角的攻击动画同步
     */
    private void triggerAttackAnimation(ItemStack stack, Level level) {
        triggerAttackOnInstance(stack, level, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, "attack");
        triggerAttackOnInstance(stack, level, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, "3rd_attack");
    }

    private void triggerAttackOnInstance(ItemStack stack, Level level, ItemDisplayContext context, String animName) {
        ItemAnimatable animatable = getRenderInstance(stack, level, context);
        var controller = animatable.getAnimController();

        for (var layer : controller.getLayers().values()) {
            for (var anim : layer.getAnimations()) {
                if (animName.equals(anim.getAnimIndex().getName())) {
                    return;
                }
            }
        }

        try {
            var modelIndex = animatable.getModelController().getModel().getIndex();
            AnimIndex animIndex = new AnimIndex(modelIndex, animName);
            AnimInstance attackAnim = new AnimInstance(animatable, animIndex);
            attackAnim.setGroup(AnimGroups.MAIN);
            attackAnim.enter();
            controller.playAnimation(attackAnim);
            LOGGER.info("[IronSwordWeapon] Attack animation '{}' triggered on {} MAIN layer", animName, context);
        } catch (Exception e) {
            LOGGER.warn("[IronSwordWeapon] Failed to play attack animation '{}' on {}: {}", animName, context, e.getMessage());
        }
    }

    /**
     * 播放循环 idle 动画。
     * Spark 不会自动播放物品 idle 动画（没有动画状态机），必须显式播放。
     * still / 3rd_still 均为 loop=true，播放后持续循环。
     */
    private static void playIdle(ItemAnimatable animatable, ModelIndex modelIndex, String animName) {
        try {
            AnimIndex animIndex = new AnimIndex(modelIndex, animName);
            AnimInstance idle = new AnimInstance(animatable, animIndex);
            idle.setGroup(AnimGroups.MAIN);
            idle.enter();
            animatable.getAnimController().playAnimation(idle);
            LOGGER.info("[IronSwordWeapon] Idle animation '{}' started (loop)", animName);
        } catch (Exception e) {
            LOGGER.warn("[IronSwordWeapon] Failed to play idle animation '{}': {}", animName, e.getMessage());
        }
    }

    /**
     * 获取渲染实例
     * 按 Level 缓存 DualItemAnimatable（第一人称）和第三人称实例。
     * 第一人称 context → 返回 DualItemAnimatable（自身持有 fp 的 controller）
     * 第三人称 context → 返回 DualItemAnimatable.tpInstance
     *
     * capability provider 返回 DualItemAnimatable，AnimApplier tick 它时会同时 tick fp 和 tp。
     */
    @Override
    public ItemAnimatable getRenderInstance(ItemStack itemStack, Level level, ItemDisplayContext context) {
        Map<ItemDisplayContext, ItemAnimatable> levelCache = cachedAnimatables.computeIfAbsent(
                level, k -> new ConcurrentHashMap<>());

        // 确保 DualItemAnimatable（fp）和 tp 实例都已创建
        DualItemAnimatable dual = (DualItemAnimatable) levelCache.get(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND);
        if (dual == null) {
            dual = new DualItemAnimatable(itemStack, level);
            ModelIndex fpModelIndex = getModelIndex(itemStack, level, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND);
            dual.getModelController().setModel(fpModelIndex);
            applyTexture(dual, itemStack, level, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND);
            levelCache.put(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, dual);
            playIdle(dual, fpModelIndex, "still");

            // 创建 tp 实例
            ItemAnimatable tp = new ItemAnimatable(itemStack, level);
            ModelIndex tpModelIndex = getModelIndex(itemStack, level, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND);
            tp.getModelController().setModel(tpModelIndex);
            applyTexture(tp, itemStack, level, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND);
            dual.setTpInstance(tp);
            levelCache.put(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, tp);
            playIdle(tp, tpModelIndex, "3rd_still");
        }

        if (isFirstPerson(context)) {
            return dual;
        } else {
            return dual.getTpInstance();
        }
    }

    /**
     * 获取物品的模型索引
     * 剑系共用 sword 模型（fp/tp 同一模型，动画按视角区分）
     * 纹理默认取 ModelIndex 推导的 textures/item/sword.png（铁材质）；
     * 后续其他材质用 setTextureLocation 覆盖
     */
    @Override
    public ModelIndex getModelIndex(ItemStack itemStack, Level level, ItemDisplayContext context) {
        // 模型文件: spark_models/item/catoxidesbattlerebuild/sword.json
        // 动画文件: spark_animations/item/catoxidesbattlerebuild/sword/*.json
        ResourceLocation modelLoc = ResourceLocation.fromNamespaceAndPath(
                CatoxidesBattleRebuildConstants.MODID, "sword");
        return new ModelIndex("item", modelLoc);
    }

    /**
     * 单独设置该物品 3D 模型使用的贴图（第③项机制）。
     *
     * <p>Spark 默认贴图由 ModelIndex 推导为 {@code <ns>:textures/item/sword.png}（铁材质）。
     * 剑系 7 材质共用同一 {@code sword} 模型、不同贴图，靠此钩子按物品覆盖：
     * <pre>{@code
     *   // 例：金剑用 golden_sword.png
     *   public ResourceLocation getTextureLocation(ItemStack s, Level l, ItemDisplayContext c) {
     *       return ResourceLocation.fromNamespaceAndPath(MODID, "textures/item/golden_sword");
     *   }
     * }</pre>
     *
     * <p>返回 {@code null} 表示用 ModelIndex 推导的默认贴图（当前铁剑）。
     * 自定义 2D 贴图本身由后续提供，这里只保证"能按物品单独设置"。
     */
    public ResourceLocation getTextureLocation(ItemStack itemStack, Level level, ItemDisplayContext displayContext) {
        return null; // 默认：textures/item/sword.png
    }

    /**
     * 在实例创建后按 getTextureLocation 覆盖贴图。null 则保持 ModelIndex 推导的默认贴图。
     */
    private void applyTexture(ItemAnimatable animatable, ItemStack stack, Level level,
                              ItemDisplayContext context) {
        ResourceLocation tex = getTextureLocation(stack, level, context);
        if (tex != null) {
            animatable.getModelController().setTextureLocation(tex);
        }
    }

    /**
     * 是否在特定场景使用 2D 模型
     * GUI 和掉落物使用 2D 模型
     */
    @Override
    public boolean use2dModel(ItemStack itemStack, Level level, ItemDisplayContext displayContext) {
        return switch (displayContext) {
            case GUI, GROUND, FIXED -> true;
            default -> false;
        };
    }

    /**
     * 获取渲染偏移（单位：格；作用于模型根空间，见 GeoItemRenderer 矩阵顺序）。
     *
     * <p>方案B（叠加层）：玩家模型 = player_wrist（分节手臂/腿），剑 = 纯剑物品模型
     * （sword.json 已去手臂），经 in-hand/Spark 机制锚到 player_wrist 的 rightItem（手腕）。
     * 手臂由挥剑图层（sword_arm_idle/attack）驱动，剑跟着手腕走。
     *
     * <p>offset 初版归零（让机制定位），进游戏看效果后微调：
     * 前/后调 z，上/下调 y，左/右调 x（值越大向该方向推得越多）。
     */
    @Override
    public Vector3f getRenderOffset(ItemStack itemStack, Level level, ItemDisplayContext displayContext) {
        // 方案B：初版归零，进游戏微调
        return new Vector3f(0f, 0f, 0f);
    }

    /**
     * 获取渲染旋转
     * 初始 (0,0,0)，与 offset 一同按游戏内效果迭代。
     */
    @Override
    public Vector3f getRenderRotation(ItemStack itemStack, Level level, ItemDisplayContext displayContext) {
        return new Vector3f(0f, 0f, 0f);
    }

    /**
     * 获取渲染缩放
     */
    @Override
    public Vector3f getRenderScale(ItemStack itemStack, Level level, ItemDisplayContext displayContext) {
        return new Vector3f(1f, 1f, 1f);
    }

    /**
     * 获取渲染颜色
     */
    @Override
    public Color getColor(ItemStack itemStack, Level level, ItemDisplayContext displayContext) {
        return Color.WHITE;
    }
}
