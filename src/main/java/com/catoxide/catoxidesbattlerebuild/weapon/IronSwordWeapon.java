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
 * 铁剑武器 - 实现 ICustomModelItem 接口以支持 Spark-Core 自定义模型
 * 第一人称和第三人称使用独立的模型和动画资源（swords_fp / swords_tp）
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
        triggerAttackOnInstance(stack, level, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND);
        triggerAttackOnInstance(stack, level, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND);
    }

    private void triggerAttackOnInstance(ItemStack stack, Level level, ItemDisplayContext context) {
        ItemAnimatable animatable = getRenderInstance(stack, level, context);
        var controller = animatable.getAnimController();

        for (var layer : controller.getLayers().values()) {
            for (var anim : layer.getAnimations()) {
                if ("attack".equals(anim.getAnimIndex().getName())) {
                    return;
                }
            }
        }

        try {
            var modelIndex = animatable.getModelController().getModel().getIndex();
            AnimIndex animIndex = new AnimIndex(modelIndex, "attack");
            AnimInstance attackAnim = new AnimInstance(animatable, animIndex);
            attackAnim.setGroup(AnimGroups.MAIN);
            attackAnim.enter();
            controller.playAnimation(attackAnim);
            LOGGER.info("[IronSwordWeapon] Attack animation triggered on {} MAIN layer", context);
        } catch (Exception e) {
            LOGGER.warn("[IronSwordWeapon] Failed to play attack animation on {}: {}", context, e.getMessage());
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
            levelCache.put(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, dual);

            // 创建 tp 实例
            ItemAnimatable tp = new ItemAnimatable(itemStack, level);
            ModelIndex tpModelIndex = getModelIndex(itemStack, level, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND);
            tp.getModelController().setModel(tpModelIndex);
            dual.setTpInstance(tp);
            levelCache.put(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, tp);
        }

        if (isFirstPerson(context)) {
            return dual;
        } else {
            return dual.getTpInstance();
        }
    }

    /**
     * 获取物品的模型索引
     * 第一人称使用 swords_fp，第三人称使用 swords_tp
     */
    @Override
    public ModelIndex getModelIndex(ItemStack itemStack, Level level, ItemDisplayContext context) {
        String modelPath = isFirstPerson(context) ? "swords_fp" : "swords_tp";
        ResourceLocation modelLoc = ResourceLocation.fromNamespaceAndPath(
                CatoxidesBattleRebuildConstants.MODID, modelPath);
        return new ModelIndex("item", modelLoc);
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
     * 获取渲染偏移
     * 位置信息以第三人称动画为准，所有 context 返回相同参数
     */
    @Override
    public Vector3f getRenderOffset(ItemStack itemStack, Level level, ItemDisplayContext displayContext) {
        return new Vector3f(0f, 0f, 0f);
    }

    /**
     * 获取渲染旋转
     * 位置信息以第三人称动画为准，所有 context 返回相同参数
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
