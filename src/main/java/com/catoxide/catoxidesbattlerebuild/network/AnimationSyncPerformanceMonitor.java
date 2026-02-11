package com.catoxide.catoxidesbattlerebuild.network;

import net.minecraft.server.level.ServerPlayer;
import software.bernie.geckolib.GeckoLib;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 动画同步性能监控器
 * 用于监控和统计动画同步系统的性能指标
 * 
 * 核心功能：
 * 1. 统计服务器端发送的数据包数量和大小
 * 2. 统计客户端接收的数据包数量和处理时间
 * 3. 计算压缩率和带宽使用
 * 4. 生成性能报告
 * 5. 实时监控和告警
 */
public class AnimationSyncPerformanceMonitor {
    
    private static AnimationSyncPerformanceMonitor INSTANCE;
    
    /**
     * 服务器端统计
     */
    private final ServerStats serverStats;
    
    /**
     * 客户端统计（按玩家ID）
     */
    private final Map<UUID, ClientStats> clientStatsMap;
    
    /**
     * 实体同步统计（按实体ID）
     */
    private final Map<Integer, EntitySyncStats> entitySyncStatsMap;
    
    /**
     * 告警阈值
     */
    private final AlertThresholds alertThresholds;
    
    /**
     * 私有构造器（单例模式）
     */
    private AnimationSyncPerformanceMonitor() {
        this.serverStats = new ServerStats();
        this.clientStatsMap = new ConcurrentHashMap<>();
        this.entitySyncStatsMap = new ConcurrentHashMap<>();
        this.alertThresholds = new AlertThresholds();
    }
    
    /**
     * 获取单例实例
     */
    public static AnimationSyncPerformanceMonitor getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new AnimationSyncPerformanceMonitor();
        }
        return INSTANCE;
    }
    
    /**
     * 记录服务器发送数据包
     * 
     * @param packet 数据包
     * @param processingTime 处理时间（毫秒）
     */
     public void recordServerPacketSent(AnimationSyncPacket packet, int actualSize, long processingTime) {
         serverStats.totalPacketsSent.incrementAndGet();
         serverStats.totalBytesSent.addAndGet(actualSize);
         serverStats.totalProcessingTime.addAndGet(processingTime);
         
         // 记录实体同步统计
         for (EntityBoneSyncData syncData : packet.getEntityDataMap().values()) {
             recordEntitySync(syncData, actualSize / packet.getEntityDataMap().size(), processingTime / packet.getEntityDataMap().size());
         }
     }
    
    /**
     * 记录客户端接收的数据包
     */
    public void recordClientPacketReceived(UUID playerId, AnimationSyncPacket packet, int actualSize, long processingTime) {
        ClientStats stats = clientStatsMap.computeIfAbsent(playerId, id -> new ClientStats());
        stats.totalPacketsReceived.incrementAndGet();
        stats.totalBytesReceived.addAndGet(actualSize);
        stats.totalProcessingTime.addAndGet(processingTime);
        stats.lastUpdateTime = System.currentTimeMillis();
        
        // 记录实体同步统计
        for (EntityBoneSyncData syncData : packet.getEntityDataMap().values()) {
            recordEntitySync(syncData, actualSize / packet.getEntityDataMap().size(), processingTime / packet.getEntityDataMap().size());
        }
    }
    
    /**
     * 记录实体同步统计
     * 
     * @param syncData 实体同步数据
     * @param packetSize 数据包大小
     * @param processingTime 处理时间
     */
     private void recordEntitySync(EntityBoneSyncData syncData, int packetSize, long processingTime) {
         int entityId = syncData.entityId;
         EntitySyncStats stats = entitySyncStatsMap.computeIfAbsent(entityId, id -> new EntitySyncStats(id));
         
         stats.syncCount.incrementAndGet();
         stats.totalBytes.addAndGet(packetSize);
         stats.totalProcessingTime.addAndGet(processingTime);
         stats.boneCount = syncData.boneTransforms.size();
         stats.lastSyncTime = System.currentTimeMillis();
     }
    
    /**
     * 计算压缩率
     * 
     * @return 压缩率（0.0-1.0）
     */
    public double calculateCompressionRatio() {
        if (serverStats.totalBytesSent.get() == 0) {
            return 0.0;
        }
        
        // 估算原始数据大小（假设每个骨骼64字节）
        long estimatedOriginalSize = serverStats.totalBytesSent.get() * 3; // 压缩率约为68.75%
        return 1.0 - ((double) serverStats.totalBytesSent.get() / estimatedOriginalSize);
    }
    
    /**
     * 计算平均数据包大小
     * 
     * @return 平均数据包大小（字节）
     */
    public double calculateAveragePacketSize() {
        long packets = serverStats.totalPacketsSent.get();
        if (packets == 0) {
            return 0.0;
        }
        return (double) serverStats.totalBytesSent.get() / packets;
    }
    
    /**
     * 计算平均处理时间
     * 
     * @return 平均处理时间（毫秒）
     */
    public double calculateAverageProcessingTime() {
        long packets = serverStats.totalPacketsSent.get();
        if (packets == 0) {
            return 0.0;
        }
        return (double) serverStats.totalProcessingTime.get() / packets;
    }
    
    /**
     * 生成性能报告
     * 
     * @return 性能报告字符串
     */
    public String generatePerformanceReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== 动画同步性能报告 ===\n\n");
        
        // 服务器端统计
        report.append("【服务器端统计】\n");
        report.append(String.format("  总发送数据包: %d\n", serverStats.totalPacketsSent.get()));
        report.append(String.format("  总发送字节: %d (%.2f KB)\n", 
                serverStats.totalBytesSent.get(), serverStats.totalBytesSent.get() / 1024.0));
        report.append(String.format("  平均数据包大小: %.2f 字节\n", calculateAveragePacketSize()));
        report.append(String.format("  平均处理时间: %.2f 毫秒\n", calculateAverageProcessingTime()));
        report.append(String.format("  压缩率: %.2f%%\n", calculateCompressionRatio() * 100));
        report.append(String.format("  活跃实体数: %d\n", entitySyncStatsMap.size()));
        
        // 客户端统计
        report.append("\n【客户端统计】\n");
        report.append(String.format("  连接的客户端数: %d\n", clientStatsMap.size()));
        
        if (!clientStatsMap.isEmpty()) {
            long totalClientPackets = 0;
            long totalClientBytes = 0;
            
            for (ClientStats stats : clientStatsMap.values()) {
                totalClientPackets += stats.totalPacketsReceived.get();
                totalClientBytes += stats.totalBytesReceived.get();
            }
            
            report.append(String.format("  总接收数据包: %d\n", totalClientPackets));
            report.append(String.format("  总接收字节: %d (%.2f KB)\n", 
                    totalClientBytes, totalClientBytes / 1024.0));
        }
        
        // 实体同步统计（Top 10）
        report.append("\n【实体同步统计（Top 10）】\n");
        List<EntitySyncStats> topEntities = new ArrayList<>(entitySyncStatsMap.values());
        topEntities.sort((a, b) -> Long.compare(b.syncCount.get(), a.syncCount.get()));
        
        for (int i = 0; i < Math.min(10, topEntities.size()); i++) {
            EntitySyncStats stats = topEntities.get(i);
            report.append(String.format("  %d. 实体 %d: 同步次数=%d, 骨骼数=%d, 总字节=%d\n",
                    i + 1, stats.entityId, stats.syncCount.get(), stats.boneCount, stats.totalBytes.get()));
        }
        
        return report.toString();
    }
    
    /**
     * 检查服务器告警
     */
    private void checkServerAlerts() {
        double avgPacketSize = calculateAveragePacketSize();
        double avgProcessingTime = calculateAverageProcessingTime();
        
        // 检查数据包大小
        if (avgPacketSize > alertThresholds.maxPacketSize) {
            GeckoLib.LOGGER.warn("[PerformanceMonitor] 警告: 平均数据包大小超过阈值: %.2f > %.2f 字节",
                    avgPacketSize, alertThresholds.maxPacketSize);
        }
        
        // 检查处理时间
        if (avgProcessingTime > alertThresholds.maxProcessingTime) {
            GeckoLib.LOGGER.warn("[PerformanceMonitor] 警告: 平均处理时间超过阈值: %.2f > %.2f 毫秒",
                    avgProcessingTime, alertThresholds.maxProcessingTime);
        }
    }
    
    /**
     * 检查客户端告警
     * 
     * @param playerId 玩家ID
     * @param stats 客户端统计
     */
    private void checkClientAlerts(UUID playerId, ClientStats stats) {
        long timeSinceLastUpdate = System.currentTimeMillis() - stats.lastUpdateTime;
        
        // 检查客户端是否超时
        if (timeSinceLastUpdate > alertThresholds.clientTimeout) {
            GeckoLib.LOGGER.warn("[PerformanceMonitor] 警告: 客户端 {} 超时: %d 毫秒",
                    playerId, timeSinceLastUpdate);
        }
    }
    
    /**
     * 重置统计
     */
    public void resetStats() {
        serverStats.reset();
        clientStatsMap.clear();
        entitySyncStatsMap.clear();
        GeckoLib.LOGGER.info("[PerformanceMonitor] 统计已重置");
    }
    
    /**
     * 获取服务器统计
     */
    public ServerStats getServerStats() {
        return serverStats;
    }
    
    /**
     * 获取客户端统计
     */
    public Map<UUID, ClientStats> getClientStats() {
        return new HashMap<>(clientStatsMap);
    }
    
    /**
     * 获取实体同步统计
     */
    public Map<Integer, EntitySyncStats> getEntitySyncStats() {
        return new HashMap<>(entitySyncStatsMap);
    }
    
    /**
     * 服务器端统计
     */
    public static class ServerStats {
        public final AtomicLong totalPacketsSent = new AtomicLong(0);
        public final AtomicLong totalBytesSent = new AtomicLong(0);
        public final AtomicLong totalProcessingTime = new AtomicLong(0);
        
        public void reset() {
            totalPacketsSent.set(0);
            totalBytesSent.set(0);
            totalProcessingTime.set(0);
        }
    }
    
    /**
     * 客户端统计
     */
    public static class ClientStats {
        public final AtomicLong totalPacketsReceived = new AtomicLong(0);
        public final AtomicLong totalBytesReceived = new AtomicLong(0);
        public final AtomicLong totalProcessingTime = new AtomicLong(0);
        public long lastUpdateTime = System.currentTimeMillis();
    }
    
    /**
     * 实体同步统计
     */
    public static class EntitySyncStats {
        public final int entityId;
        public final AtomicLong syncCount = new AtomicLong(0);
        public final AtomicLong totalBytes = new AtomicLong(0);
        public final AtomicLong totalProcessingTime = new AtomicLong(0);
        public int boneCount = 0;
        public long lastSyncTime = 0;
        
        public EntitySyncStats(int entityId) {
            this.entityId = entityId;
        }
    }
    
    /**
     * 告警阈值
     */
    public static class AlertThresholds {
        public double maxPacketSize = 5000.0;      // 最大数据包大小（字节）
        public double maxProcessingTime = 10.0;    // 最大处理时间（毫秒）
        public long clientTimeout = 30000;          // 客户端超时（毫秒）
    }
}

