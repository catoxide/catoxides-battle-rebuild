package com.catoxide.catoxidesbattlerebuild.server;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import software.bernie.geckolib.GeckoLib;
import software.bernie.geckolib.core.animatable.GeoAnimatable;

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
     * 事件订阅：实体加入世界时自动追踪（如果已注册模型）
     */
    @SubscribeEvent
    public static void onEntityJoinWorld(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof GeoAnimatable) {
            Entity entity = event.getEntity();

            // 直接为所有GeckoAnimatable实体注册
            // 模型位置可以从实体本身获取，或者使用一个默认策略

            ResourceLocation modelLocation = determineModelForEntity(entity);
            if (modelLocation != null) {
                registerGeckoEntity(entity, modelLocation);
                GeckoLib.LOGGER.debug("Auto-registered Gecko entity: {}", entity);
            }
        }
    }
    private static ResourceLocation determineModelForEntity(Entity entity) {
        // 策略1：从实体的类名推断（最简单的方法）
        String className = entity.getClass().getSimpleName().toLowerCase();
        String modId = "yourmod"; // 你的Mod ID

        // 假设模型文件按照约定命名：geo/<entity_class_name>.geo.json
        return new ResourceLocation(modId, "geo/" + className + ".geo.json");

        // 策略2：从实体的注册名推断
        // ResourceLocation registryName = EntityType.getKey(entity.getType());
        // return new ResourceLocation(registryName.getNamespace(),
        //     "geo/" + registryName.getPath() + ".geo.json");

        // 策略3：如果实体实现了特定接口，从接口获取
        // if (entity instanceof IHasModelLocation modelEntity) {
        //     return modelEntity.getModelLocation();
        // }
    }

    /**
     * 事件订阅：实体离开世界时自动移除
     */
    @SubscribeEvent
    public static void onEntityLeaveWorld(net.minecraftforge.event.entity.EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            unregisterGeckoEntity(event.getEntity());
        }
    }

    /**
     * 关键：服务器每 tick 更新所有实体动画
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            try {
                // 更新所有实体动画
                ServerEntityManager.getInstance().updateAll(1.0f);

                // 清理无效实体集合
                EntityCollectionFactory.getInstance().cleanupInvalidCollections();

            } catch (Exception e) {
                GeckoLib.LOGGER.error("Error during server tick update: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 事件订阅：世界 tick 时清理无效实体
     */
    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (isServerSide(event.level) && event.phase == TickEvent.Phase.END) {
            try {
                // 清理无效的实体
                ServerEntityManager.getInstance().cleanupInvalidEntities();

            } catch (Exception e) {
                GeckoLib.LOGGER.error("Error during level tick cleanup: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 注册实体到动画系统（供其他模块调用）
     */
    public static void registerGeckoEntity(Entity entity, net.minecraft.resources.ResourceLocation modelLocation) {
        if (entity instanceof GeoAnimatable) {
            try {
                // 1. 创建EntityCollection（通过EntityCollectionFactory）
                EntityCollection collection = EntityCollectionFactory.getInstance()
                        .createEntityCollection(entity, modelLocation);

                if (collection == null) {
                    GeckoLib.LOGGER.error("Failed to create EntityCollection for entity: {}", entity);
                    return;
                }

                // 2. 注册到实体管理器
                ServerEntityManager.getInstance().registerEntity(entity, modelLocation);

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
        try {
            // 1. 从实体管理器移除
            ServerEntityManager.getInstance().unregisterEntity(entity);

            // 2. 从实体集合工厂移除
            EntityCollectionFactory.getInstance().removeEntityCollection(entity);

            GeckoLib.LOGGER.debug("Unregistered Gecko entity: {}", entity);

        } catch (Exception e) {
            GeckoLib.LOGGER.error("Failed to unregister Gecko entity: {}", entity, e);
        }
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