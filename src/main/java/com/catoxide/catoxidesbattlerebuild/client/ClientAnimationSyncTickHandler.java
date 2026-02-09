package com.catoxide.catoxidesbattlerebuild.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import software.bernie.geckolib.GeckoLib;

/**
 * 客户端动画同步Tick处理器
 * 负责在客户端tick中定期清理无效数据和更新插值
 * 
 * 核心功能：
 * 1. 定期清理无效实体数据
 * 2. 更新插值系统
 * 3. 监控性能统计
 * 4. 日志输出调试信息
 */
@Mod.EventBusSubscriber(modid = "catoxidesbattlerebuild", bus = Mod.EventBusSubscriber.Bus.FORGE, value = net.minecraftforge.api.distmarker.Dist.CLIENT)
public class ClientAnimationSyncTickHandler {
    
    /**
     * 清理间隔（tick）
     */
    private static final int CLEANUP_INTERVAL = 200; // 10秒
    
    /**
     * 性能统计间隔（tick）
     */
    private static final int STATS_INTERVAL = 600; // 30秒
    
    /**
     * 当前tick计数
     */
    private static int tickCounter = 0;
    
    /**
     * 客户端tick事件处理
     * 
     * @param event 客户端tick事件
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        // 只在tick结束时处理
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        
        tickCounter++;
        
        // 定期清理无效实体数据
        if (tickCounter % CLEANUP_INTERVAL == 0) {
            cleanupInvalidEntities();
        }
        
        // 定期输出性能统计
        if (tickCounter % STATS_INTERVAL == 0) {
            logPerformanceStats();
        }
        
        // 每tick更新插值
        updateInterpolation();
    }
    
    /**
     * 清理无效实体数据
     */
    private static void cleanupInvalidEntities() {
        try {
            // 清理动画同步处理器
            ClientAnimationSyncHandler.getInstance().cleanupInvalidEntities();
            
            // 清理实体管理器
            ClientEntityManager.getInstance().cleanupInvalidEntities();
            
            // 清理旧的受击盒处理器
            ClientHitboxHandler.cleanup();
            
            GeckoLib.LOGGER.debug("[ClientAnimationSync] Cleanup completed");
        } catch (Exception e) {
            GeckoLib.LOGGER.error("[ClientAnimationSync] Cleanup failed: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 更新插值
     */
    private static void updateInterpolation() {
        try {
            float partialTick = Minecraft.getInstance().getPartialTick();
            ClientEntityManager.getInstance().updateInterpolation(partialTick);
        } catch (Exception e) {
            GeckoLib.LOGGER.error("[ClientAnimationSync] Interpolation update failed: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 日志输出性能统计
     */
    private static void logPerformanceStats() {
        try {
            ClientAnimationSyncHandler.PerformanceStats stats = 
                    ClientAnimationSyncHandler.getInstance().getPerformanceStats();
            
            GeckoLib.LOGGER.info("[ClientAnimationSync] Performance stats: {}", stats);
            
            // 输出实体和模型数量
            int entityCount = ClientEntityManager.getInstance().getEntityCount();
            int modelCount = ClientEntityManager.getInstance().getModelCount();
            
            GeckoLib.LOGGER.info("[ClientAnimationSync] Active entities: {}, models: {}", entityCount, modelCount);
        } catch (Exception e) {
            GeckoLib.LOGGER.error("[ClientAnimationSync] Failed to log performance stats: {}", e.getMessage(), e);
        }
    }
}
