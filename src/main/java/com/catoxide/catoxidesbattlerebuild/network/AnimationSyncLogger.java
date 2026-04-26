package com.catoxide.catoxidesbattlerebuild.network;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.GeckoLib;

import java.util.Map;
import java.util.UUID;

/**
 * 动画同步日志记录器
 * 
 * 负责记录动画同步系统的详细日志，包括：
 * - 服务器端发送的数据包内容
 * - 客户端接收的数据包内容
 * - 性能统计信息
 * 
 * 使用独立的日志类，避免修改原有业务逻辑代码
 */
public class AnimationSyncLogger {
    
    /**
     * 记录发送动画同步数据包的详细内容
     * 
     * @param player 接收玩家
     * @param entityDataMap 实体数据映射
     * @param deltaUpdate 是否为增量同步
     * @param compressed 是否压缩
     * @param packetSize 数据包大小
     * @param processingTime 处理时间
     */
    public static void logSendPacket(ServerPlayer player, 
                                      Map<Integer, EntityBoneSyncData> entityDataMap,
                                      boolean deltaUpdate,
                                      boolean compressed,
                                      int packetSize,
                                      long processingTime) {
        if (!GeckoLib.LOGGER.isDebugEnabled()) {
            return;
        }
        
        StringBuilder contentDescription = new StringBuilder();
        contentDescription.append(String.format("发送动画同步数据包给玩家 '%s':\n", player.getName().getString()));
        contentDescription.append(String.format("  - 实体数量: %d\n", entityDataMap.size()));
        contentDescription.append(String.format("  - 数据包类型: %s\n", deltaUpdate ? "增量同步" : "完整同步"));
        contentDescription.append(String.format("  - 压缩状态: %s\n", compressed ? "已压缩" : "未压缩"));
        
        // 详细描述每个实体的数据
        for (Map.Entry<Integer, EntityBoneSyncData> entry : entityDataMap.entrySet()) {
            int entityId = entry.getKey();
            EntityBoneSyncData syncData = entry.getValue();
            
            contentDescription.append(String.format("\n  实体 [ID: %d]:\n", entityId));
            contentDescription.append(String.format("    - 首次同步: %s\n", syncData.isFirstSync ? "是" : "否"));
            
            if (syncData.isFirstSync && syncData.modelLocation != null) {
                contentDescription.append(String.format("    - 模型位置: %s\n", syncData.modelLocation));
            }
            
            contentDescription.append(String.format("    - 骨骼数量: %d\n", syncData.boneTransforms.size()));
            
            // 列出所有骨骼名称
            if (!syncData.boneTransforms.isEmpty()) {
                contentDescription.append("    - 骨骼列表: ");
                contentDescription.append(String.join(", ", syncData.boneTransforms.keySet()));
                contentDescription.append("\n");
            }
            
            // 描述动画状态
            EntityBoneSyncData.AnimationState animState = syncData.animationState;
            contentDescription.append(String.format("    - 动画状态:\n"));
            contentDescription.append(String.format("      * 动画名称: %s\n", animState.animationName));
            contentDescription.append(String.format("      * 动画时间: %.3f\n", animState.animationTime));
            contentDescription.append(String.format("      * 动画速度: %.3f\n", animState.animationSpeed));
            contentDescription.append(String.format("      * 是否循环: %s\n", animState.looping ? "是" : "否"));
            
            // 描述骨骼变换精度
            if (!syncData.boneTransforms.isEmpty()) {
                contentDescription.append("    - 骨骼变换精度:\n");
                for (Map.Entry<String, CompressedBoneTransform> boneEntry : syncData.boneTransforms.entrySet()) {
                    String boneName = boneEntry.getKey();
                    CompressedBoneTransform transform = boneEntry.getValue();
                    contentDescription.append(String.format("      * %s: 精度等级=%d, 变化标志=0x%02X\n", 
                        boneName, transform.precisionLevel, transform.changeFlags));
                }
            }
            
            contentDescription.append(String.format("    - 时间戳: %d\n", syncData.timestamp));
        }
        
        contentDescription.append(String.format("\n  数据包统计:\n"));
        contentDescription.append(String.format("    - 总骨骼数: %d\n", 
            entityDataMap.values().stream().mapToInt(d -> d.boneTransforms.size()).sum()));
        contentDescription.append(String.format("    - 估算大小: ~%d 字节\n", packetSize));
        contentDescription.append(String.format("    - 处理时间: %d ms\n", processingTime));
        
        GeckoLib.LOGGER.debug(contentDescription.toString());
    }
    
    /**
     * 记录接收动画同步数据包的详细内容
     * 
     * @param playerId 客户端玩家UUID
     * @param entityDataMap 实体数据映射
     * @param deltaUpdate 是否为增量同步
     * @param compressed 是否压缩
     * @param packetSize 数据包大小
     * @param processingTime 处理时间
     */
    public static void logReceivePacket(UUID playerId,
                                         Map<Integer, EntityBoneSyncData> entityDataMap,
                                         boolean deltaUpdate,
                                         boolean compressed,
                                         int packetSize,
                                         long processingTime) {
        if (!GeckoLib.LOGGER.isDebugEnabled()) {
            return;
        }
        
        StringBuilder contentDescription = new StringBuilder();
        contentDescription.append(String.format("接收动画同步数据包:\n"));
        contentDescription.append(String.format("  - 玩家UUID: %s\n", playerId));
        contentDescription.append(String.format("  - 实体数量: %d\n", entityDataMap.size()));
        contentDescription.append(String.format("  - 数据包类型: %s\n", deltaUpdate ? "增量同步" : "完整同步"));
        contentDescription.append(String.format("  - 压缩状态: %s\n", compressed ? "已压缩" : "未压缩"));
        
        // 详细描述每个实体的数据
        for (Map.Entry<Integer, EntityBoneSyncData> entry : entityDataMap.entrySet()) {
            int entityId = entry.getKey();
            EntityBoneSyncData syncData = entry.getValue();
            
            contentDescription.append(String.format("\n  实体 [ID: %d]:\n", entityId));
            contentDescription.append(String.format("    - 首次同步: %s\n", syncData.isFirstSync ? "是" : "否"));
            
            if (syncData.isFirstSync && syncData.modelLocation != null) {
                contentDescription.append(String.format("    - 模型位置: %s\n", syncData.modelLocation));
            }
            
            contentDescription.append(String.format("    - 骨骼数量: %d\n", syncData.boneTransforms.size()));
            
            // 列出所有骨骼名称
            if (!syncData.boneTransforms.isEmpty()) {
                contentDescription.append("    - 骨骼列表: ");
                contentDescription.append(String.join(", ", syncData.boneTransforms.keySet()));
                contentDescription.append("\n");
            }
            
            // 描述动画状态
            EntityBoneSyncData.AnimationState animState = syncData.animationState;
            contentDescription.append(String.format("    - 动画状态:\n"));
            contentDescription.append(String.format("      * 动画名称: %s\n", animState.animationName));
            contentDescription.append(String.format("      * 动画时间: %.3f\n", animState.animationTime));
            contentDescription.append(String.format("      * 动画速度: %.3f\n", animState.animationSpeed));
            contentDescription.append(String.format("      * 是否循环: %s\n", animState.looping ? "是" : "否"));
            
            // 描述骨骼变换精度
            if (!syncData.boneTransforms.isEmpty()) {
                contentDescription.append("    - 骨骼变换精度:\n");
                for (Map.Entry<String, CompressedBoneTransform> boneEntry : syncData.boneTransforms.entrySet()) {
                    String boneName = boneEntry.getKey();
                    CompressedBoneTransform transform = boneEntry.getValue();
                    contentDescription.append(String.format("      * %s: 精度等级=%d, 变化标志=0x%02X\n", 
                        boneName, transform.precisionLevel, transform.changeFlags));
                }
            }
            
            contentDescription.append(String.format("    - 时间戳: %d\n", syncData.timestamp));
        }
        
        contentDescription.append(String.format("\n  数据包统计:\n"));
        contentDescription.append(String.format("    - 总骨骼数: %d\n", 
            entityDataMap.values().stream().mapToInt(d -> d.boneTransforms.size()).sum()));
        contentDescription.append(String.format("    - 估算大小: ~%d 字节\n", packetSize));
        contentDescription.append(String.format("    - 处理时间: %d ms\n", processingTime));
        
        GeckoLib.LOGGER.debug(contentDescription.toString());
    }
    
    /**
     * 记录性能统计信息
     * 
     * @param totalPacketsSent 总发送数据包数
     * @param totalBoneTransformsSent 总发送骨骼变换数
     * @param totalBytesSent 总发送字节数
     */
    public static void logPerformanceStats(int totalPacketsSent, 
                                           int totalBoneTransformsSent,
                                           long totalBytesSent) {
        if (!GeckoLib.LOGGER.isDebugEnabled()) {
            return;
        }
        
        GeckoLib.LOGGER.debug("=== 动画同步性能统计 ===");
        GeckoLib.LOGGER.debug("  - 总发送数据包: {}", totalPacketsSent);
        GeckoLib.LOGGER.debug("  - 总骨骼变换数: {}", totalBoneTransformsSent);
        GeckoLib.LOGGER.debug("  - 总发送字节数: {} ({:.2f} KB)", 
            totalBytesSent, totalBytesSent / 1024.0);
        GeckoLib.LOGGER.debug("  - 平均每包大小: {:.2f} 字节", 
            totalPacketsSent > 0 ? (double) totalBytesSent / totalPacketsSent : 0);
        GeckoLib.LOGGER.debug("  - 平均每包骨骼数: {:.2f}", 
            totalPacketsSent > 0 ? (double) totalBoneTransformsSent / totalPacketsSent : 0);
    }
    
    /**
     * 记录同步错误
     * 
     * @param message 错误消息
     * @param throwable 异常对象（可选）
     */
    public static void logError(String message, Throwable throwable) {
        if (throwable != null) {
            GeckoLib.LOGGER.error(message, throwable);
        } else {
            GeckoLib.LOGGER.error(message);
        }
    }
    
    /**
     * 记录同步警告
     * 
     * @param message 警告消息
     */
    public static void logWarning(String message) {
        GeckoLib.LOGGER.warn(message);
    }
    
    /**
     * 记录同步信息
     * 
     * @param message 信息消息
     */
    public static void logInfo(String message) {
        GeckoLib.LOGGER.info(message);
    }
    
    /**
     * 记录调试信息
     * 
     * @param message 调试消息
     */
    public static void logDebug(String message) {
        GeckoLib.LOGGER.debug(message);
    }
    
    // ==================== 服务器初始化日志方法 ====================
    
    /**
     * 记录服务器初始化开始
     */
    public static void logInitializationStart() {
        GeckoLib.LOGGER.info("=== 开始服务器动画同步系统初始化 ===");
    }
    
    /**
     * 记录初始化阶段
     * 
     * @param phaseName 阶段名称
     * @param description 阶段描述
     * @param success 是否成功
     */
    public static void logInitializationPhase(String phaseName, String description, boolean success) {
        if (success) {
            GeckoLib.LOGGER.info("  [{}] {} - 完成", phaseName, description);
        } else {
            GeckoLib.LOGGER.error("  [{}] {} - 失败", phaseName, description);
        }
    }
    
    /**
     * 记录初始化完成
     * 
     * @param success 是否成功
     * @param duration 持续时间（毫秒）
     */
    public static void logInitializationComplete(boolean success, long duration) {
        if (success) {
            GeckoLib.LOGGER.info("=== 服务器动画同步系统初始化完成 (耗时: {} ms) ===", duration);
        } else {
            GeckoLib.LOGGER.error("=== 服务器动画同步系统初始化失败 (耗时: {} ms) ===", duration);
        }
    }
    
    /**
     * 记录初始化阶段开始
     * 
     * @param phaseName 阶段名称
     * @param description 阶段描述
     */
    public static void logInitializationPhaseStart(String phaseName, String description) {
        GeckoLib.LOGGER.info("  [{}] 开始: {}", phaseName, description);
    }
    
    /**
     * 记录初始化阶段结束
     * 
     * @param phaseName 阶段名称
     * @param duration 持续时间（毫秒）
     */
    public static void logInitializationPhaseEnd(String phaseName, long duration) {
        GeckoLib.LOGGER.info("  [{}] 完成 (耗时: {} ms)", phaseName, duration);
    }
}
