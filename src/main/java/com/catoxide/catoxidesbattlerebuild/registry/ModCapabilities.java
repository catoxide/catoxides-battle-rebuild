package com.catoxide.catoxidesbattlerebuild.registry;

import cn.solarmoon.spark_core.animation.ICustomModelItem;
import cn.solarmoon.spark_core.animation.ItemAnimatable;
import cn.solarmoon.spark_core.registry.common.SparkCapabilities;
import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.slf4j.Logger;

@EventBusSubscriber(modid = CatoxidesBattleRebuildConstants.MODID)
public class ModCapabilities {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static long lastLogTime = 0;

    @SubscribeEvent
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        LOGGER.info("[ModCapabilities] Registering ITEM_ANIMATABLE capability for IronSwordWeapon");
        // 注册 ITEM_ANIMATABLE capability provider
        // AnimApplier 通过此 capability 获取 ItemAnimatable 并 tick（运行状态机和更新骨骼）
        // provider 返回 DualItemAnimatable，它同时 tick 第一人称和第三人称两个实例
        event.registerItem(
            SparkCapabilities.getITEM_ANIMATABLE(),
            (ItemStack stack, Level level) -> {
                if (stack.getItem() instanceof ICustomModelItem customModelItem) {
                    // 返回第一人称实例（DualItemAnimatable），它内部会同时 tick 第三人称实例
                    ItemAnimatable animatable = customModelItem.getRenderInstance(stack, level, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND);
                    long now = System.currentTimeMillis();
                    if (animatable != null && now - lastLogTime > 1000) {
                        lastLogTime = now;
                        LOGGER.info("[ModCapabilities] ITEM_ANIMATABLE provider called, animatable={}, isPlayingAnim={}",
                            animatable.hashCode(), animatable.getAnimController().isPlayingAnim());
                    }
                    return animatable;
                }
                return null;
            },
            ModWeapons.IRON_SWORD_WEAPON.get()
        );
        LOGGER.info("[ModCapabilities] ITEM_ANIMATABLE capability registered");
    }
}
