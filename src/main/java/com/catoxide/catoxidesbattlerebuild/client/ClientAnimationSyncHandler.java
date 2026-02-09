package com.catoxide.catoxidesbattlerebuild.client;

import com.catoxide.catoxidesbattlerebuild.network.AnimationSyncPacket;
import com.catoxide.catoxidesbattlerebuild.network.AnimationSyncPerformanceMonitor;
import com.catoxide.catoxidesbattlerebuild.network.CompressedBoneTransform;
import com.catoxide.catoxidesbattlerebuild.network.EntityBoneSyncData;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import software.bernie.geckolib.GeckoLib;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端动画同步处理器
 * 负责接收和处理服务端发送的动画同步数据
 * 
 * 核心功能：
 * 1. 接收AnimationSyncPacket
 * 2. 解压缩骨骼变换数据
 * 3. 应用骨骼矩阵到ClientEntityManager
 * 4. 处理动画状态更新
 * 5. 支持差异同步和插值
 */
public class ClientAnimationSyncHandler {
    
    private static ClientAnimationSyncHandler INSTANCE;
    
    /**
     * 上次同步的骨骼数据（用于差异检测）
     */
    private final Map<Integer, Map<String, CompressedBoneTransform>> lastSyncData;
    
    /**
     * 插值缓冲区（用于平滑动画）
     */
    private final Map<Integer, Map<String, InterpolationBuffer>> interpolationBuffers;
    
    /**
     * 性能统计
     */
    private long totalPacketsReceived = 0;
    private long totalBytesReceived = 0;
    private long totalProcessingTime = 0;
    
    /**
     * 私有构造器（单例模式）
     */
    private ClientAnimationSyncHandler() {
        this.lastSyncData = new ConcurrentHashMap<>();
        this.interpolationBuffers = new ConcurrentHashMap<>();
    }
    
    /**
     * 获取单例实例
     */
    public static ClientAnimationSyncHandler getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ClientAnimationSyncHandler();
        }
        return INSTANCE;
    }
    
    /**
     * 处理动画同步数据包
     * 
     * @param packet 动画同步数据包
     */
    public void handleAnimationSync(AnimationSyncPacket packet) {
        long startTime = System.currentTimeMillis();
        totalPacketsReceived++;
        
        GeckoLib.LOGGER.debug("[ClientAnimationSync] Handling packet for {} entities, compressed: {}, delta: {}",
                packet.getEntitySyncData().size(), packet.isCompressed(), packet.isDeltaSync());
        
        if (Minecraft.getInstance().level == null) {
            GeckoLib.LOGGER.warn("[ClientAnimationSync] Level is null, skipping packet");
            return;
        }
        
        // 处理每个实体的同步数据
        for (EntityBoneSyncData syncData : packet.getEntitySyncData()) {
            processEntitySyncData(syncData);
        }
        
        // 更新插值
        updateInterpolation();
        
        long processingTime = System.currentTimeMillis() - startTime;
        totalProcessingTime += processingTime;
        
        GeckoLib.LOGGER.debug("[ClientAnimationSync] Packet processed in {} ms (total: {} packets, {} ms avg)",
                processingTime, totalPacketsReceived, totalProcessingTime / totalPacketsReceived);
    }
    
    /**
     * 处理单个实体的同步数据
     * 
     * @param syncData 实体骨骼同步数据
     */
    private void processEntitySyncData(EntityBoneSyncData syncData) {
        int entityId = syncData.getEntityId();
        Entity entity = Minecraft.getInstance().level.getEntity(entityId);
        
        if (entity == null) {
            GeckoLib.LOGGER.debug("[ClientAnimationSync] Entity {} not found, skipping", entityId);
            return;
        }
        
        GeckoLib.LOGGER.trace("[ClientAnimationSync] Processing entity {} ({}) with {} bones",
                entityId, entity.getName().getString(), syncData.getBoneTransforms().size());
        
        // 更新动画状态
        if (syncData.getAnimationState() != null) {
            updateAnimationState(entity, syncData.getAnimationState());
        }
        
        // 处理骨骼变换数据
        Map<String, CompressedBoneTransform> compressedTransforms = syncData.getBoneTransforms();
        
        // 解压缩并应用骨骼变换
        for (Map.Entry<String, CompressedBoneTransform> entry : compressedTransforms.entrySet()) {
            String boneName = entry.getKey();
            CompressedBoneTransform compressed = entry.getValue();
            
            // 解压缩骨骼矩阵
            Matrix4f boneMatrix = decompressBoneMatrix(compressed);
            
            // 应用到客户端实体管理器
            ClientEntityManager.getInstance().updateBoneMatrix(entity, boneName, boneMatrix);
            
            // 更新插值缓冲区
            updateInterpolationBuffer(entityId, boneName, boneMatrix, syncData.getTimestamp());
            
            // 保存到上次同步数据（用于差异检测）
            saveLastSyncData(entityId, boneName, compressed);
        }
        
        // 如果是首次同步，标记为已同步
        if (syncData.isFirstSync()) {
            GeckoLib.LOGGER.debug("[ClientAnimationSync] First sync completed for entity {}", entityId);
        }
    }
    
    /**
     * 解压缩骨骼矩阵
     * 
     * @param compressed 压缩的骨骼变换
     * @return 解压缩后的骨骼矩阵
     */
    private Matrix4f decompressBoneMatrix(CompressedBoneTransform compressed) {
        Matrix4f matrix = new Matrix4f();
        
        // 解压缩旋转（四元数）
        float qx = compressed.getRotationX();
        float qy = compressed.getRotationY();
        float qz = compressed.getRotationZ();
        float qw = compressed.getRotationW();
        
        // 解压缩位置
        float px = compressed.getPositionX();
        float py = compressed.getPositionY();
        float pz = compressed.getPositionZ();
        
        // 解压缩缩放
        float sx = compressed.getScaleX();
        float sy = compressed.getScaleY();
        float sz = compressed.getScaleZ();
        
        // 构建变换矩阵
        matrix.identity();
        matrix.translate(px, py, pz);
        matrix.rotateXYZ(qx, qy, qz);
        matrix.scale(sx, sy, sz);
        
        return matrix;
    }
    
    /**
     * 更新动画状态
     * 
     * @param entity 实体
     * @param animState 动画状态
     */
    private void updateAnimationState(Entity entity, EntityBoneSyncData.AnimationState animState) {
        if (animState == null) {
            return;
        }
        
        GeckoLib.LOGGER.trace("[ClientAnimationSync] Updating animation state for entity {}: {} (time: {}, speed: {}, loop: {})",
                entity.getId(), animState.animationName, animState.animationTime, animState.animationSpeed, animState.looping);
        
        // 更新客户端实体管理器的动画状态
        ClientEntityManager.getInstance().updateAnimationState(
                entity.getUUID(),
                animState.animationName,
                animState.animationTime,
                animState.animationSpeed,
                animState.looping
        );
    }
    
    /**
     * 更新插值缓冲区
     * 
     * @param entityId 实体ID
     * @param boneName 骨骼名称
     * @param boneMatrix 骨骼矩阵
     * @param timestamp 时间戳
     */
    private void updateInterpolationBuffer(int entityId, String boneName, Matrix4f boneMatrix, long timestamp) {
        Map<String, InterpolationBuffer> entityBuffers = interpolationBuffers.computeIfAbsent(
                entityId, id -> new ConcurrentHashMap<>()
        );
        
        InterpolationBuffer buffer = entityBuffers.computeIfAbsent(
                boneName, name -> new InterpolationBuffer()
        );
        
        buffer.update(boneMatrix, timestamp);
    }
    
    /**
     * 更新所有插值缓冲区
     */
    private void updateInterpolation() {
        float partialTick = Minecraft.getInstance().getPartialTick();
        
        for (Map<String, InterpolationBuffer> entityBuffers : interpolationBuffers.values()) {
            for (InterpolationBuffer buffer : entityBuffers.values()) {
                buffer.updateInterpolation(partialTick);
            }
        }
    }
    
    /**
     * 保存上次同步数据
     * 
     * @param entityId 实体ID
     * @param boneName 骨骼名称
     * @param compressed 压缩的骨骼变换
     */
    private void saveLastSyncData(int entityId, String boneName, CompressedBoneTransform compressed) {
        Map<String, CompressedBoneTransform> entityData = lastSyncData.computeIfAbsent(
                entityId, id -> new ConcurrentHashMap<>()
        );
        
        entityData.put(boneName, compressed);
    }
    
    /**
     * 获取上次同步数据
     * 
     * @param entityId 实体ID
     * @param boneName 骨骼名称
     * @return 上次同步的压缩骨骼变换，如果不存在则返回null
     */
    public CompressedBoneTransform getLastSyncData(int entityId, String boneName) {
        Map<String, CompressedBoneTransform> entityData = lastSyncData.get(entityId);
        return entityData != null ? entityData.get(boneName) : null;
    }
    
    /**
     * 清理无效实体数据
     */
    public void cleanupInvalidEntities() {
        if (Minecraft.getInstance().level == null) {
            // 清空所有数据
            lastSyncData.clear();
            interpolationBuffers.clear();
            return;
        }
        
        // 移除不存在的实体
        Iterator<Integer> iterator = lastSyncData.keySet().iterator();
        while (iterator.hasNext()) {
            int entityId = iterator.next();
            if (Minecraft.getInstance().level.getEntity(entityId) == null) {
                iterator.remove();
                interpolationBuffers.remove(entityId);
            }
        }
    }
    
    /**
     * 清理所有数据
     */
    public void cleanup() {
        lastSyncData.clear();
        interpolationBuffers.clear();
        totalPacketsReceived = 0;
        totalBytesReceived = 0;
        totalProcessingTime = 0;
    }
    
    /**
     * 获取性能统计
     */
    public PerformanceStats getPerformanceStats() {
        return new PerformanceStats(
                totalPacketsReceived,
                totalBytesReceived,
                totalPacketsReceived > 0 ? totalProcessingTime / totalPacketsReceived : 0
        );
    }
    
    /**
     * 插值缓冲区
     */
    private static class InterpolationBuffer {
        private Matrix4f previousMatrix;
        private Matrix4f currentMatrix;
        private long previousTimestamp;
        private long currentTimestamp;
        
        public InterpolationBuffer() {
            this.previousMatrix = new Matrix4f();
            this.currentMatrix = new Matrix4f();
            this.previousTimestamp = 0;
            this.currentTimestamp = 0;
        }
        
        /**
         * 更新缓冲区
         * 
         * @param matrix 新的骨骼矩阵
         * @param timestamp 时间戳
         */
        public void update(Matrix4f matrix, long timestamp) {
            // 将当前矩阵移动到上一个
            previousMatrix.set(currentMatrix);
            previousTimestamp = currentTimestamp;
            
            // 设置新的当前矩阵
            currentMatrix.set(matrix);
            currentTimestamp = timestamp;
        }
        
        /**
         * 更新插值
         * 
         * @param partialTick 部分tick
         */
        public void updateInterpolation(float partialTick) {
            if (previousTimestamp == 0 || currentTimestamp == 0) {
                return;
            }
            
            // 计算插值因子
            long timeDiff = currentTimestamp - previousTimestamp;
            float alpha = Math.min(partialTick, 1.0f);
            
            // 线性插值
            previousMatrix.lerp(currentMatrix, alpha);
        }
        
        /**
         * 获取插值后的矩阵
         * 
         * @return 插值后的矩阵
         */
        public Matrix4f getInterpolatedMatrix() {
            return new Matrix4f(previousMatrix);
        }
    }
    
    /**
     * 处理接收到的动画同步数据包
     * 
     * @param packet 动画同步数据包
     */
    public void handleAnimationSyncPacket(AnimationSyncPacket packet) {
        try {
            // 记录开始时间
            long startTime = System.currentTimeMillis();
            
            // 记录接收统计
            int packetSize = estimatePacketSize(packet);
            
            GeckoLib.LOGGER.trace("[ClientAnimationSync] Received packet for {} entities, protocol: {}, compressed: {}, delta: {}",
                    packet.getEntitySyncData().size(),
                    packet.getProtocolVersion(),
                    packet.isCompressed(),
                    packet.isDeltaSync());
            
            // 处理每个实体的同步数据
            for (EntityBoneSyncData syncData : packet.getEntitySyncData()) {
                processEntitySyncData(syncData);
            }
            
            // 记录处理统计
            long processingTime = System.currentTimeMillis() - startTime;
            UUID playerId = net.minecraft.client.Minecraft.getInstance().player.getUUID();
            AnimationSyncPerformanceMonitor.getInstance().recordClientPacketReceived(playerId, packetSize, processingTime);
            
            GeckoLib.LOGGER.trace("[ClientAnimationSync] Processed packet in {} ms", processingTime);
            
        } catch (Exception e) {
            GeckoLib.LOGGER.error("[ClientAnimationSync] Error handling packet: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 估算数据包大小
     * 
     * @param packet 动画同步数据包
     * @return 估算的数据包大小（字节）
     */
    private int estimatePacketSize(AnimationSyncPacket packet) {
        int size = 0;
        
        // 协议版本（字符串）
        size += packet.getProtocolVersion().length() * 2; // UTF-16编码
        
        // 压缩标志和差异同步标志
        size += 2;
        
        // 实体同步数据
        for (EntityBoneSyncData syncData : packet.getEntitySyncData()) {
            // 实体ID
            size += 4;
            
            // 模型位置（3个float）
            size += 12;
            
            // 骨骼变换数据
            for (CompressedBoneTransform transform : syncData.getBoneTransforms().values()) {
                // 骨骼名称（字符串）
                size += 20; // 平均长度
                
                // 压缩的骨骼变换（20字节）
                size += 20;
            }
            
            // 动画状态（如果存在）
            if (syncData.getAnimationState() != null) {
                // 动画名称（字符串）
                size += 30; // 平均长度
                
                // 动画时间、速度、循环标志
                size += 8 + 8 + 1;
            }
            
            // 时间戳和首次同步标志
            size += 8 + 1;
        }
        
        return size;
    }
    
    /**
     * 性能统计
     */
    public static class PerformanceStats {
        public final long totalPacketsReceived;
        public final long totalBytesReceived;
        public final long averageProcessingTime;
        
        public PerformanceStats(long totalPacketsReceived, long totalBytesReceived, long averageProcessingTime) {
            this.totalPacketsReceived = totalPacketsReceived;
            this.totalBytesReceived = totalBytesReceived;
            this.averageProcessingTime = averageProcessingTime;
        }
        
        @Override
        public String toString() {
            return String.format("PerformanceStats{packets=%d, bytes=%d, avgTime=%dms}",
                    totalPacketsReceived, totalBytesReceived, averageProcessingTime);
        }
    }
}