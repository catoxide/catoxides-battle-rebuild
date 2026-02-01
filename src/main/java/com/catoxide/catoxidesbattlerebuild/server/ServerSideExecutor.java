package com.catoxide.catoxidesbattlerebuild.server;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
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

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuild.MODID;

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
                ResourceManager resourceManager = level.getServer().getResourceManager();
                Executor executor = level.getServer();

                GeckoLib.LOGGER.info("=== 初始化资源管理器 ===");
                GeckoLib.LOGGER.info("Server: {}", level.getServer());
                GeckoLib.LOGGER.info("资源管理器: {}", resourceManager);


                ServerGeoModelManager.getInstance().initialize(
                        resourceManager,
                        MODEL_LOADING_EXECUTOR
                );
                initialized = true;
                GeckoLib.LOGGER.info("✅ ServerSideExecutor 初始化完成");
            } catch (Exception e) {
                GeckoLib.LOGGER.error("初始化服务端组件失败", e);
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
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGHEST)
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
    public static ResourceLocation determineModelForEntity(Entity entity) {
        // 1. 获取实体的核心标识（去除符号）
        String entityCore = getEntityCoreIdentifier(entity);

        if (entityCore.isEmpty()) {
            return null;
        }

        // 2. 获取所有已加载的模型
        Collection<ResourceLocation> loadedModels =
                ServerGeoModelManager.getInstance().getAllLoadedModelLocations();

        if (loadedModels == null || loadedModels.isEmpty()) {
            return null;
        }

        // 3. 遍历所有模型，查找完全匹配的
        for (ResourceLocation modelLocation : loadedModels) {
            String modelCore = getModelCoreIdentifier(modelLocation);

            if (entityCore.equals(modelCore)) {
                GeckoLib.LOGGER.debug("Exact match found: {} -> {}", entityCore, modelLocation);
                return modelLocation;
            }
        }

        // 4. 没有找到匹配的模型
        GeckoLib.LOGGER.warn("No exact model match for entity: {} (core identifier: {})",
                entity.getDisplayName().getString(), entityCore);
        return null;
    }

    /**
     * 获取实体的核心标识符（去除所有符号）
     */
    private static String getEntityCoreIdentifier(Entity entity) {
        // 获取实体类名（去掉"Entity"后缀）
        String className = entity.getClass().getSimpleName();
        className = removeSuffix(className, "Entity");

        // 获取实体类型注册名作为备选
        EntityType<?> entityType = entity.getType();
        String registryName = EntityType.getKey(entityType).getPath();

        // 尝试两种可能的标识符
        String[] possibleIdentifiers = {className, registryName};

        for (String identifier : possibleIdentifiers) {
            String core = normalizeIdentifier(identifier);
            if (!core.isEmpty() && core.length() >= 3) { // 至少3个字母才认为是有效的
                return core;
            }
        }

        return "";
    }

    /**
     * 获取模型的核心标识符（去除所有符号）
     */
    private static String getModelCoreIdentifier(ResourceLocation modelLocation) {
        // 获取模型路径（如 "geo/elder_guardian.geo.json"）
        String path = modelLocation.getPath();

        // 移除 "geo/" 前缀
        if (path.startsWith("geo/")) {
            path = path.substring(4);
        }

        // 移除 ".geo.json" 后缀
        if (path.endsWith(".geo.json")) {
            path = path.substring(0, path.length() - 9);
        }

        // 移除子目录（如果有）
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash != -1) {
            path = path.substring(lastSlash + 1);
        }

        // 规范化标识符
        return normalizeIdentifier(path);
    }

    /**
     * 规范化标识符：全部小写，移除所有非字母字符
     */
    private static String normalizeIdentifier(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return "";
        }

        // 1. 全部转换为小写
        identifier = identifier.toLowerCase();

        // 2. 移除所有非字母字符（只保留a-z）
        identifier = identifier.replaceAll("[^a-z]", "");

        // 3. 移除常见的无意义后缀（如复数形式）
        identifier = removeSuffix(identifier, "s");
        identifier = removeSuffix(identifier, "es");

        return identifier;
    }

    /**
     * 移除字符串的后缀（如果存在）
     */
    private static String removeSuffix(String str, String suffix) {
        if (str.endsWith(suffix)) {
            return str.substring(0, str.length() - suffix.length());
        }
        return str;
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
    public static void registerGeckoEntity(Entity entity, ResourceLocation modelLocation) {
        // 1. 类型检查
        if (!(entity instanceof GeoAnimatable)) {
            return;
        }

        try {
            // 2. 先检查是否已存在（使用 get 模式）
            EntityCollection existing = EntityCollectionFactory.getInstance()
                    .getEntityCollection(entity.getUUID());

            if (existing != null) {
                // 已存在，直接返回，避免重复注册
                GeckoLib.LOGGER.debug("Entity {} already registered, skipping", entity);
                return;
            }

            // 3. 不存在，才进行注册
            ServerEntityManager.getInstance().registerEntity(entity, modelLocation);

            GeckoLib.LOGGER.debug("Registered Gecko entity: {} with model: {}", entity, modelLocation);

        } catch (Exception e) {
            GeckoLib.LOGGER.error("Failed to register Gecko entity: {}", entity, e);
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
    private static final ExecutorService MODEL_LOADING_EXECUTOR =
            Executors.newFixedThreadPool(2, r -> {
                Thread thread = new Thread(r, "GeckoLib-Model-Loader");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY + 1);
                return thread;
            });
}