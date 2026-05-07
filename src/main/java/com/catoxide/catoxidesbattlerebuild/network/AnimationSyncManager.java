package com.catoxide.catoxidesbattlerebuild.network;

import com.catoxide.catoxidesbattlerebuild.server.geometry.ServerEntityManager;
import com.catoxide.catoxidesbattlerebuild.server.geometry.EntityCollection;
import com.catoxide.catoxidesbattlerebuild.server.models.ServerGeoModelManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.PacketDistributor;
import org.joml.Matrix4f;
import software.bernie.geckolib.GeckoLib;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动画同步管理器（简化版 - 每tick更新所有实体）
 * 
 * 功能：
 * 1. 从ServerEntityManager获取骨骼变换矩阵
 * 2. 压缩数据并实现差异同步
 * 3. 每tick同步所有实体到客户端
 * 4. 优化带宽使用
 */
public class AnimationSyncManager {
    
    private static final AnimationSyncManager INSTANCE = new AnimationSyncManager();
    
    // 同步配置
    private static final int SYNC_INTERVAL = 1;  // 每tick同步一次
    private static final double MAX_SYNC_DISTANCE = 64.0; // 64格内同步
    private static final byte PRECISION_LEVEL = 2; // 高精度压缩
    
    // 变化阈值（用于差异同步）
    private static final float MIN_CHANGE_THRESHOLD = 0.01f;
    
    // Tick计数器
    private int tickCounter = 0;
    
    // 上次同步的数据（用于差异检测）
    private final Map<Integer, Map<String, CompressedBoneTransform>> lastSyncData = new ConcurrentHashMap<>();
    
    // 实体首次同步标志
    private final Set<Integer> firstSyncedEntities = ConcurrentHashMap.newKeySet();
    
    // 性能统计
    private int totalPacketsSent = 0;
    private int totalBoneTransformsSent = 0;
    private long totalBytesSent = 0;
    
    private AnimationSyncManager() {}
    
    public static AnimationSyncManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * 服务器tick时调用
     */
    public void onServerTick() {
        tickCounter++;
        
        // 每SYNC_INTERVAL tick同步一次
        if (tickCounter % SYNC_INTERVAL == 0) {
            syncAllEntities();
        }
    }
    
    /**
     * 同步所有实体
     */
    private void syncAllEntities() {
        try {
            net.minecraft.server.MinecraftServer server =
                net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) return;

            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            if (players.isEmpty()) return;

            // 获取所有注册的实体UUID
            Collection<java.util.UUID> entityUuids =
                ServerEntityManager.getInstance().getAllEntityUuids();

            GeckoLib.LOGGER.debug("[AnimationSyncManager] syncAllEntities called, players={}, registeredEntities={}",
                players.size(), entityUuids.size());

            if (entityUuids.isEmpty()) {
                return;
            }

            // 更新所有实体的动画（关键：这一步之前缺失！）
            ServerGeoModelManager modelManager = ServerGeoModelManager.getInstance();
            for (java.util.UUID uuid : entityUuids) {
                EntityCollection entityCollection = 
                    ServerEntityManager.getInstance().getEntity(uuid);
                if (entityCollection != null && entityCollection.isValid()) {
                    Entity entity = entityCollection.entity();
                    if (entity != null && entity instanceof software.bernie.geckolib.core.animatable.GeoAnimatable) {
                        net.minecraft.resources.ResourceLocation modelLocation = entityCollection.modelLocation();
                        modelManager.updateAnimation(modelLocation, (software.bernie.geckolib.core.animatable.GeoAnimatable) entity, 0.0f);
                    }
                }
            }

            // 为每个玩家准备同步数据
            Map<ServerPlayer, Map<Integer, EntityBoneSyncData>> playerSyncData = new HashMap<>();

            for (ServerPlayer player : players) {
                Map<Integer, EntityBoneSyncData> entityDataMap = new HashMap<>();

                // 获取所有实体
                for (java.util.UUID uuid : entityUuids) {
                    EntityCollection entityCollection = 
                        ServerEntityManager.getInstance().getEntity(uuid);
                    
                    if (entityCollection == null || !entityCollection.isValid()) {
                        continue;
                    }
                    
                    Entity entity = entityCollection.entity();
                    if (entity == null) continue;
                    
                    // 距离检查
                    double distance = player.distanceTo(entity);
                    if (distance > MAX_SYNC_DISTANCE) continue;
                    
                    // 获取实体ID
                    int entityId = entity.getId();
                    
                    // 准备骨骼变换数据
                    Map<String, Matrix4f> boneMatrices = 
                        entityCollection.boneMatrices();
                    
                    if (boneMatrices == null || boneMatrices.isEmpty()) {
                        continue;
                    }
                    
                    // 压缩骨骼变换
                    Map<String, CompressedBoneTransform> compressedTransforms = 
                        compressBoneTransforms(boneMatrices, PRECISION_LEVEL);
                    
                    // 差异检测
                    Map<String, CompressedBoneTransform> deltaTransforms = 
                        calculateDelta(entityId, compressedTransforms);
                    
                    if (deltaTransforms.isEmpty()) {
                        continue; // 没有变化，跳过
                    }
                    
                    // 检查是否为首次同步
                    boolean isFirstSync = !firstSyncedEntities.contains(entityId);
                    
                    // 创建实体同步数据
                    EntityBoneSyncData entityData;
                    if (isFirstSync) {
                        entityData = EntityBoneSyncData.createFirstSync(
                            entityId,
                            entityCollection.modelLocation(),
                            deltaTransforms,
                            EntityBoneSyncData.AnimationState.getDefault()
                        );
                        firstSyncedEntities.add(entityId);
                    } else {
                        entityData = EntityBoneSyncData.createDeltaSync(
                            entityId,
                            deltaTransforms,
                            EntityBoneSyncData.AnimationState.getDefault()
                        );
                    }
                    
                    entityDataMap.put(entityId, entityData);
                    
                    // 更新上次同步数据
                    lastSyncData.put(entityId, new HashMap<>(compressedTransforms));
                }
                
                if (!entityDataMap.isEmpty()) {
                    playerSyncData.put(player, entityDataMap);
                }
            }
            
            // 发送数据包
            for (Map.Entry<ServerPlayer, Map<Integer, EntityBoneSyncData>> entry :
                 playerSyncData.entrySet()) {
                sendAnimationSyncPacket(entry.getKey(), entry.getValue());
            }

            GeckoLib.LOGGER.debug("[AnimationSyncManager] Sent animation sync packets to {} players, total entities synced: {}",
                playerSyncData.size(),
                playerSyncData.values().stream().mapToInt(Map::size).sum());

        } catch (Exception e) {
            GeckoLib.LOGGER.error("Error syncing animations: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 压缩骨骼变换
     */
    private Map<String, CompressedBoneTransform> compressBoneTransforms(
            Map<String, Matrix4f> boneMatrices, 
            byte precisionLevel) {
        
        Map<String, CompressedBoneTransform> result = new HashMap<>();
        
        for (Map.Entry<String, Matrix4f> entry : boneMatrices.entrySet()) {
            String boneName = entry.getKey();
            Matrix4f matrix = entry.getValue();
            
            CompressedBoneTransform compressed = 
                new CompressedBoneTransform(boneName, matrix, precisionLevel);
            
            result.put(boneName, compressed);
        }
        
        return result;
    }
    
    /**
     * 计算差异（只返回有变化的骨骼）
     */
    private Map<String, CompressedBoneTransform> calculateDelta(
            int entityId,
            Map<String, CompressedBoneTransform> currentTransforms) {
        
        // 检查断点：差异检测
        NetworkDebugHelper.getInstance().checkBreakpoint(NetworkDebugHelper.BreakpointType.DELTA_DETECTION, entityId);
        
        Map<String, CompressedBoneTransform> delta = new HashMap<>();
        Map<String, CompressedBoneTransform> lastTransforms = 
            lastSyncData.get(entityId);
        
        if (lastTransforms == null) {
            // 首次同步，返回所有数据
            return currentTransforms;
        }
        
        // 比较每个骨骼的变换
        for (Map.Entry<String, CompressedBoneTransform> entry : 
             currentTransforms.entrySet()) {
            String boneName = entry.getKey();
            CompressedBoneTransform current = entry.getValue();
            CompressedBoneTransform last = lastTransforms.get(boneName);
            
            if (last == null) {
                // 新骨骼，添加到差异
                delta.put(boneName, current);
            } else {
                // 计算差异
                float diff = current.calculateDifference(last);
                if (diff > MIN_CHANGE_THRESHOLD) {
                    delta.put(boneName, current);
                }
            }
        }
        
        return delta;
    }
    
    /**
     * 发送动画同步数据包
     */
    private void sendAnimationSyncPacket(ServerPlayer player, 
                                        Map<Integer, EntityBoneSyncData> entityDataMap) {
        if (entityDataMap.isEmpty()) return;
        
        try {
            // 检查断点：发送前
            NetworkDebugHelper debugHelper = NetworkDebugHelper.getInstance();
            for (int entityId : entityDataMap.keySet()) {
                debugHelper.checkBreakpoint(NetworkDebugHelper.BreakpointType.BEFORE_SEND, entityId);
            }
            
            // 创建数据包
            AnimationSyncPacket packet = AnimationSyncPacket.createDeltaSync(entityDataMap);
            
            // 记录开始时间
            long startTime = System.currentTimeMillis();
            
            // 计算数据包大小
            int packetSize = estimatePacketSize(packet);
            
            // 发送给玩家
            NetworkHandler.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                packet
            );
            
            // 记录发送统计
            long processingTime = System.currentTimeMillis() - startTime;
            AnimationSyncPerformanceMonitor.getInstance().recordServerPacketSent(packet, packetSize, processingTime);
            
            // 更新统计
            totalPacketsSent++;
            int boneCount = entityDataMap.values().stream()
                .mapToInt(data -> data.boneTransforms.size())
                .sum();
            totalBoneTransformsSent += boneCount;
            totalBytesSent += packetSize;
            
            // 使用独立的日志类记录发送内容
            AnimationSyncLogger.logSendPacket(
                player,
                entityDataMap,
                packet.isDeltaUpdate(),
                packet.isCompressed(),
                packetSize,
                processingTime
            );
            
            // 检查断点：发送后
            for (int entityId : entityDataMap.keySet()) {
                debugHelper.checkBreakpoint(NetworkDebugHelper.BreakpointType.AFTER_SEND, entityId);
            }
            
        } catch (Exception e) {
            AnimationSyncLogger.logError("发送动画同步数据包失败", e);
        }
    }
    
    /**
     * 估算数据包大小
     */
    private int estimatePacketSize(AnimationSyncPacket packet) {
        int size = 0;
        
        // 协议版本（int）
        size += 4;
        
        // 标志位（byte）
        size += 1;
        
        // 实体数量（int）
        size += 4;
        
        // 实体同步数据
        for (EntityBoneSyncData syncData : packet.getEntityDataMap().values()) {
            // 实体ID（int）
            size += 4;
            
            // 首次同步标志（boolean）
            size += 1;
            
            // 模型位置（如果是首次同步）
            if (syncData.isFirstSync && syncData.modelLocation != null) {
                size += 30; // ResourceLocation平均大小
            }
            
            // 骨骼数量（int）
            size += 4;
            
            // 骨骼变换数据
            for (CompressedBoneTransform transform : syncData.boneTransforms.values()) {
                // 骨骼名称（字符串）
                size += transform.boneName.length() * 2; // UTF-16编码
                
                // 压缩的骨骼变换（16个short + 2个byte）
                size += 32 + 2;
            }
            
            // 动画状态
            size += syncData.animationState.animationName.length() * 2; // 动画名称
            size += 8 + 8 + 1; // 时间、速度、循环标志
            
            // 时间戳（long）
            size += 8;
        }
        
        return size;
    }
    
    /**
     * 立即同步单个实体（用于调试等特殊需求）
     */
    public void syncEntityImmediately(Entity entity) {
        if (entity == null) return;
        
        EntityCollection entityCollection = 
            ServerEntityManager.getInstance().getEntity(entity.getUUID());
        
        if (entityCollection == null) return;
        
        Map<String, Matrix4f> boneMatrices = entityCollection.boneMatrices();
        if (boneMatrices == null || boneMatrices.isEmpty()) return;
        
        // 压缩骨骼变换（使用高精度）
        Map<String, CompressedBoneTransform> compressedTransforms = 
            compressBoneTransforms(boneMatrices, PRECISION_LEVEL);
        
        // 创建实体同步数据
        EntityBoneSyncData entityData = EntityBoneSyncData.createDeltaSync(
            entity.getId(),
            compressedTransforms,
            EntityBoneSyncData.AnimationState.getDefault()
        );
        
        Map<Integer, EntityBoneSyncData> entityDataMap = new HashMap<>();
        entityDataMap.put(entity.getId(), entityData);
        
        // 发送给所有能看到这个实体的玩家
        entity.level().players().forEach(player -> {
            if (player.distanceTo(entity) <= MAX_SYNC_DISTANCE) {
                sendAnimationSyncPacket((ServerPlayer) player, entityDataMap);
            }
        });
    }
    
    /**
     * 清理实体数据
     */
    public void cleanupEntity(int entityId) {
        lastSyncData.remove(entityId);
        firstSyncedEntities.remove(entityId);
    }
    
    /**
     * 获取统计信息
     */
    public SyncStats getStats() {
        return new SyncStats(
            totalPacketsSent,
            totalBoneTransformsSent,
            totalBytesSent
        );
    }
    
    /**
     * 重置统计信息
     */
    public void resetStats() {
        totalPacketsSent = 0;
        totalBoneTransformsSent = 0;
        totalBytesSent = 0;
    }
    
    /**
     * 同步统计信息
     */
    public static class SyncStats {
        public final int totalPacketsSent;
        public final int totalBoneTransformsSent;
        public final long totalBytesSent;
        
        public SyncStats(int totalPacketsSent, int totalBoneTransformsSent, long totalBytesSent) {
            this.totalPacketsSent = totalPacketsSent;
            this.totalBoneTransformsSent = totalBoneTransformsSent;
            this.totalBytesSent = totalBytesSent;
        }
        
        @Override
        public String toString() {
            return String.format("Packets: %d, Bones: %d, Bytes: %d (~%.2f KB)",
                totalPacketsSent,
                totalBoneTransformsSent,
                totalBytesSent,
                totalBytesSent / 1024.0
            );
        }
    }
}




