package com.catoxide.catoxidesbattlerebuild.server;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import software.bernie.geckolib.GeckoLib;
import software.bernie.geckolib.core.animatable.GeoAnimatable;

/**
 * 服务端实体生命周期管理器，负责处理实体的创建、更新、销毁等生命周期事件
 */
@Mod.EventBusSubscriber
public class ServerEntityLifecycleManager {
    private static final ServerEntityLifecycleManager INSTANCE = new ServerEntityLifecycleManager();

    private ServerEntityLifecycleManager() {}

    public static ServerEntityLifecycleManager getInstance() {
        return INSTANCE;
    }
    /**
     * 实体出生时的处理
     */
    @SubscribeEvent
    private static void onEntityBorn(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof LivingEntity entity) {
            if (entity instanceof GeoAnimatable) {
                try {
                    // 获取模型的ResourceLocation
                    ResourceLocation modelLocation = EntityAssociator.getInstance().getCollection(entity.getUUID()).modelLocation();
                    if (modelLocation != null) {
                        // 注册到动画系统
                        ServerSideExecutor.registerGeckoEntity(entity, modelLocation);
                        GeckoLib.LOGGER.debug("Registered entity {} with model {}", entity, modelLocation);
                    }
                } catch (Exception e) {
                    GeckoLib.LOGGER.error("Failed to register entity {} to animation system", entity, e);
                }
            }
        }
    }

    /**
     * 实体死亡时的处理
     */
    @SubscribeEvent
    private static void onEntityDeath(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            ServerSideExecutor.unregisterGeckoEntity(event.getEntity());
        }
    }

}
