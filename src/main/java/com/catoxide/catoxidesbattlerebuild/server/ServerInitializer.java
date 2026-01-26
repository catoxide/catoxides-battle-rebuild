package com.catoxide.catoxidesbattlerebuild.server;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import software.bernie.geckolib.GeckoLib;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public class ServerInitializer {

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(ServerInitializer::initializeServerComponents);
    }

    private static void initializeServerComponents() {
        try {
            // 初始化配置
            ServerConfig.initialize();

            // 初始化核心组件（单例模式会自动初始化）
            // 1. 模型管理器
            ServerGeoModelManager.getInstance();

            // 2. 动画实体表格
            AnimatableEntitiesTable.getInstance();

            // 3. 实体关联器
            EntityAssociator.getInstance();

            // 4. 实体生命周期管理器
            ServerEntityLifecycleManager.getInstance();

            // 5. 服务端执行器
            ServerSideExecutor.getInstance();

            // 6. 矩阵变换器（可选）
            MatrixTransformer.getInstance();

            GeckoLib.LOGGER.info("Server animation system initialized successfully");
        } catch (Exception e) {
            GeckoLib.LOGGER.error("Failed to initialize server animation system", e);
        }
    }

    public static boolean isInitialized() {
        try {
            // 检查核心组件
            ServerGeoModelManager.getInstance();
            AnimatableEntitiesTable.getInstance();
            EntityAssociator.getInstance();
            ServerEntityLifecycleManager.getInstance();
            ServerSideExecutor.getInstance();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}