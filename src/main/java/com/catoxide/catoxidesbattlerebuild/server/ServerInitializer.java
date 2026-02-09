package com.catoxide.catoxidesbattlerebuild.server;

import com.catoxide.catoxidesbattlerebuild.server.geometry.EntityCollectionFactory;
import com.catoxide.catoxidesbattlerebuild.server.geometry.MatrixTransformer;
import com.catoxide.catoxidesbattlerebuild.server.geometry.ServerEntityManager;
import com.catoxide.catoxidesbattlerebuild.server.models.ModelCollectionFactory;
import com.catoxide.catoxidesbattlerebuild.server.models.ServerGeoModelManager;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import software.bernie.geckolib.GeckoLib;

/**
 * 服务器初始化器
 * 
 * 初始化顺序说明：
 * 1. ServerConfig - 配置系统（无依赖）
 * 2. ServerGeoModelManager - 模型管理器（依赖ServerConfig）
 * 3. ServerEntityManager - 实体管理器（依赖ServerGeoModelManager）
 * 4. EntityCollectionFactory - 实体集合工厂（依赖ServerGeoModelManager, ServerEntityManager）
 * 5. ServerSideExecutor - 服务端执行器（依赖上述所有）
 * 6. MatrixTransformer - 矩阵变换器（独立，可选）
 * 7. ModelCollectionFactory - 模型集合工厂（依赖ServerGeoModelManager）
 * 
 * @see ServerConfig
 * @see ServerGeoModelManager
 * @see ServerEntityManager
 * @see EntityCollectionFactory
 * @see ServerSideExecutor
 * @see MatrixTransformer
 * @see ModelCollectionFactory
 */
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public class ServerInitializer {

    /**
     * 初始化阶段枚举
     */
    public enum InitializationPhase {
        CONFIG("配置初始化"),
        MODEL_MANAGER("模型管理器"),
        ENTITY_MANAGER("实体管理器"),
        COLLECTION_FACTORY("集合工厂"),
        EXECUTOR("执行器"),
        UTILITIES("工具类"),
        COMPLETE("初始化完成");

        private final String description;

        InitializationPhase(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * 初始化监听器
     */
    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(ServerInitializer::initializeServerComponents);
    }

    /**
     * 初始化服务器组件
     * 
     * 初始化顺序严格遵循依赖关系，确保每个组件在初始化时其依赖已就绪
     */
    private static void initializeServerComponents() {
        long startTime = System.currentTimeMillis();
        GeckoLib.LOGGER.info("=== 开始初始化服务器组件 ===");

        try {
            // 阶段1: 配置初始化
            initializePhase(InitializationPhase.CONFIG, () -> {
                ServerConfig.initialize();
                GeckoLib.LOGGER.info("✓ 配置系统初始化完成");
            });

            // 阶段2: 模型管理器
            initializePhase(InitializationPhase.MODEL_MANAGER, () -> {
                ServerGeoModelManager.getInstance();
                GeckoLib.LOGGER.info("✓ 模型管理器初始化完成");
            });

            // 阶段3: 实体管理器
            initializePhase(InitializationPhase.ENTITY_MANAGER, () -> {
                ServerEntityManager.getInstance();
                GeckoLib.LOGGER.info("✓ 实体管理器初始化完成");
            });

            // 阶段4: 集合工厂
            initializePhase(InitializationPhase.COLLECTION_FACTORY, () -> {
                EntityCollectionFactory.getInstance();
                GeckoLib.LOGGER.info("✓ 实体集合工厂初始化完成");
            });

            // 阶段5: 执行器
            initializePhase(InitializationPhase.EXECUTOR, () -> {
                ServerSideExecutor.getInstance();
                GeckoLib.LOGGER.info("✓ 服务端执行器初始化完成");
            });

            // 阶段6: 工具类
            initializePhase(InitializationPhase.UTILITIES, () -> {
                MatrixTransformer.getInstance();
                new ModelCollectionFactory();
                GeckoLib.LOGGER.info("✓ 工具类初始化完成");
            });

            // 完成
            long duration = System.currentTimeMillis() - startTime;
            GeckoLib.LOGGER.info("=== 服务器组件初始化成功 (耗时: {}ms) ===", duration);

        } catch (Exception e) {
            GeckoLib.LOGGER.error("=== 服务器组件初始化失败 ===", e);
            throw new RuntimeException("Failed to initialize server components", e);
        }
    }

    /**
     * 按阶段初始化组件
     * 
     * @param phase 初始化阶段
     * @param initializer 初始化逻辑
     */
    private static void initializePhase(InitializationPhase phase, Runnable initializer) {
        GeckoLib.LOGGER.info("[{}] 开始 {}", phase.name(), phase.getDescription());
        long phaseStart = System.currentTimeMillis();

        try {
            initializer.run();
            long phaseDuration = System.currentTimeMillis() - phaseStart;
            GeckoLib.LOGGER.info("[{}] 完成 (耗时: {}ms)", phase.name(), phaseDuration);
        } catch (Exception e) {
            GeckoLib.LOGGER.error("[{}] 失败", phase.name(), e);
            throw e;
        }
    }

    /**
     * 检查系统是否已初始化
     * 
     * @return true 如果所有核心组件都已初始化
     */
    public static boolean isInitialized() {
        try {
            // 检查核心组件是否可用
            ServerGeoModelManager.getInstance();
            ServerEntityManager.getInstance();
            EntityCollectionFactory.getInstance();
            ServerSideExecutor.getInstance();
            return true;
        } catch (Exception e) {
            GeckoLib.LOGGER.warn("系统未完全初始化: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 获取初始化状态报告
     * 
     * @return 状态报告字符串
     */
    public static String getInitializationStatus() {
        StringBuilder status = new StringBuilder();
        status.append("=== 服务器组件初始化状态 ===\n");

        try {
            ServerGeoModelManager.getInstance();
            status.append("✓ ServerGeoModelManager: 已初始化\n");
        } catch (Exception e) {
            status.append("✗ ServerGeoModelManager: 未初始化\n");
        }

        try {
            ServerEntityManager.getInstance();
            status.append("✓ ServerEntityManager: 已初始化\n");
        } catch (Exception e) {
            status.append("✗ ServerEntityManager: 未初始化\n");
        }

        try {
            EntityCollectionFactory.getInstance();
            status.append("✓ EntityCollectionFactory: 已初始化\n");
        } catch (Exception e) {
            status.append("✗ EntityCollectionFactory: 未初始化\n");
        }

        try {
            ServerSideExecutor.getInstance();
            status.append("✓ ServerSideExecutor: 已初始化\n");
        } catch (Exception e) {
            status.append("✗ ServerSideExecutor: 未初始化\n");
        }

        return status.toString();
    }
}
