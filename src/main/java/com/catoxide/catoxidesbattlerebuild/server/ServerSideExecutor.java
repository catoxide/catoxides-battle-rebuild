package com.catoxide.catoxidesbattlerebuild.server;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import software.bernie.geckolib.GeckoLib;
import software.bernie.geckolib.core.animatable.GeoAnimatable;

import java.util.ArrayList;
import java.util.List;

@Mod.EventBusSubscriber
public class ServerSideExecutor {
    private static final ServerSideExecutor INSTANCE = new ServerSideExecutor();
    private boolean initialized = false;

    private ServerSideExecutor() {}

    public static ServerSideExecutor getInstance() {
        return INSTANCE;
    }

    public static boolean isServerSide(Level level) {
        return !level.isClientSide();
    }

    /**
     * 初始化服务端组件
     */
    public void initializeServerComponents(ServerLevel level) {
        if (!initialized) {
            try {
                // 初始化模型管理器
                ServerGeoModelManager.getInstance().initialize(
                        level.getServer().getResourceManager(),
                        level.getServer()
                );
                initialized = true;
                GeckoLib.LOGGER.info("ServerSideExecutor initialized for level: {}", level.dimension().location());
            } catch (Exception e) {
                GeckoLib.LOGGER.error("Failed to initialize server components", e);
            }
        }
    }

    /**
     * 事件订阅：服务器世界加载时初始化
     */
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (isServerSide((Level) event.getLevel()) && event.getLevel() instanceof ServerLevel serverLevel) {
            ServerSideExecutor.getInstance().initializeServerComponents(serverLevel);
        }
    }

    /**
     * 关键：服务器每 tick 更新所有实体动画
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            // 更新 AnimatableEntitiesTable 中的所有实体
            AnimatableEntitiesTable.getInstance().updateAll(1.0f);

            // 更新 EntityAssociator 中的动画（如果需要）
            EntityAssociator.getInstance().updateAllAnimations(1.0f);
        }
    }

    /**
     * 事件订阅：世界 tick 时处理实体生命周期
     */
    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (isServerSide(event.level) && event.phase == TickEvent.Phase.END) {
            // 清理无效的关联
            AnimatableEntitiesTable.getInstance().cleanupInvalidEntities();
            EntityAssociator.getInstance().cleanupInvalidAssociations();
        }
    }

    /**
     * 注册实体到动画系统（供其他模块调用）
     */
    public static void registerGeckoEntity(Entity entity, net.minecraft.resources.ResourceLocation modelLocation) {
        if (entity instanceof GeoAnimatable) {
            try {
                // 1. 注册到 AnimatableEntitiesTable（用于批量更新）
                AnimatableEntitiesTable.getInstance().registerEntity(entity, modelLocation);

                // 2. 注册到 EntityAssociator（用于简单关联）
                EntityAssociator.getInstance().associate(entity, modelLocation);

                GeckoLib.LOGGER.debug("Registered Gecko entity: {} with model: {}", entity, modelLocation);
            } catch (Exception e) {
                GeckoLib.LOGGER.error("Failed to register Gecko entity: {}", entity, e);
            }
        }
    }

    /**
     * 从动画系统注销实体
     */
    public static void unregisterGeckoEntity(Entity entity) {
        // 1. 从 AnimatableEntitiesTable 移除
        AnimatableEntitiesTable.getInstance().unregisterEntity(entity);

        // 2. 从 EntityAssociator 移除
        EntityAssociator.getInstance().disassociate(entity);

        GeckoLib.LOGGER.debug("Unregistered Gecko entity: {}", entity);
    }

    /**
     * 安全地在服务端执行操作
     */
    public static void executeOnServer(Level level, Runnable action) {
        if (isServerSide(level)) {
            try {
                action.run();
            } catch (Exception e) {
                GeckoLib.LOGGER.error("Error executing server-side action", e);
            }
        }
    }

    public static <T> T executeOnServer(Level level, java.util.function.Supplier<T> action, T defaultValue) {
        if (isServerSide(level)) {
            try {
                return action.get();
            } catch (Exception e) {
                GeckoLib.LOGGER.error("Error executing server-side action", e);
            }
        }
        return defaultValue;
    }
}