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
 */
public class IronSwordWeapon extends Item implements ICustomModelItem {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 按 Level 缓存 ItemAnimatable 实例
     * 默认实现的缓存依赖数据组件和 level 身份比较，但 ItemStack 频繁复制导致缓存失效，
     * 每次调用都创建新实例，动画状态无法持续。
     *
     * 这里按 Level 缓存：客户端 ClientLevel 和服务端 ServerLevel 各自拥有独立的实例，
     * 避免 Render thread 和 Server thread 同时 tick 同一个状态机导致并发修改崩溃。
     * 同一 Level 内的所有调用返回同一实例，确保 tick 和渲染使用同一实例，动画状态能持续。
     */
    private static final Map<Level, ItemAnimatable> cachedAnimatables = new ConcurrentHashMap<>();

    /**
     * 跟踪每个玩家上一帧的挥动状态，用于检测挥动开始（上升沿触发）
     */
    private static final Map<UUID, Boolean> lastSwingState = new ConcurrentHashMap<>();

    public IronSwordWeapon(Properties properties) {
        super(properties);
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
     * 物品在背包中每 tick 调用
     * 在客户端检测玩家开始挥动武器时，在 MAIN layer 播放 attack 动画
     * MAIN layer 会覆盖 STATE layer 的 still 动画，attack 结束后自动回到 still
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
     * MAIN layer 的 blendMode 为 OVERRIDE，会覆盖 STATE layer 的 still 动画
     * attack 动画播放完毕后自动被 physicsTick 移除，still 动画恢复显示
     */
    private void triggerAttackAnimation(ItemStack stack, Level level) {
        ItemAnimatable animatable = getRenderInstance(stack, level, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND);
        var controller = animatable.getAnimController();

        // 检查是否已经在播放 attack 动画，避免重复触发
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
            LOGGER.info("[IronSwordWeapon] Attack animation triggered on MAIN layer");
        } catch (Exception e) {
            LOGGER.warn("[IronSwordWeapon] Failed to play attack animation: {}", e.getMessage());
        }
    }

    /**
     * 获取渲染实例
     * 按 Level 缓存，确保同一 side（客户端/服务端）内的 capability tick 和渲染使用同一实例。
     * 客户端和服务端各自有独立的 ItemAnimatable，避免跨线程并发 tick 同一状态机崩溃。
     */
    @Override
    public ItemAnimatable getRenderInstance(ItemStack itemStack, Level level, ItemDisplayContext context) {
        ItemAnimatable animatable = cachedAnimatables.get(level);
        if (animatable == null) {
            animatable = new ItemAnimatable(itemStack, level);
            ResourceLocation modelLoc = ResourceLocation.fromNamespaceAndPath(
                    CatoxidesBattleRebuildConstants.MODID, "swords");
            animatable.getModelController().setModel(new ModelIndex("item", modelLoc));
            cachedAnimatables.put(level, animatable);
        }
        return animatable;
    }

    /**
     * 获取物品的模型索引
     * 指定自定义模型的资源位置
     */
    @Override
    public ModelIndex getModelIndex(ItemStack itemStack, Level level, ItemDisplayContext context) {
        // 从 spark_models/item/catoxidesbattlerebuild/swords.json 加载模型
        // swords 模型有完整的状态机和动画配置
        ResourceLocation modelLoc = ResourceLocation.fromNamespaceAndPath(
                CatoxidesBattleRebuildConstants.MODID,
            "swords"
        );
        return new ModelIndex("item", modelLoc);
    }

    // createItemAnimatable 使用默认实现
    // 默认实现会调用 getModelIndex() 并将动画体存储到数据组件
    // 碰撞检测逻辑在服务端单独处理，不依赖渲染用的动画体

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
     */
    @Override
    public Vector3f getRenderOffset(ItemStack itemStack, Level level, ItemDisplayContext displayContext) {
        return new Vector3f(0f, 0f, 0f);
    }

    /**
     * 获取渲染旋转
     * 注意：JOML 的 rotateZYX 使用弧度，不是角度
     * 动画本身已处理骨骼旋转，这里返回零旋转
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
