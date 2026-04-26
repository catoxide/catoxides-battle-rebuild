package com.catoxide.catoxidesbattlerebuild.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import software.bernie.geckolib.GeckoLib;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 动画同步数据包（基于新架构的优化版本）
 * 
 * 版本2：基于重构后的服务器多层架构
 * - 只同步骨骼变换矩阵（不同步立方体顶点）
 * - 使用压缩格式减少带宽
 * - 支持增量同步
 * - 支持优先级队列
 */
public class AnimationSyncPacket {
    
    // 协议版本
    private static final int PROTOCOL_VERSION = 2;
    
    // 同步数据：实体ID -> 骨骼变换数据
    private final Map<Integer, EntityBoneSyncData> entityDataMap;
    
    // 压缩标志
    private final boolean compressed;
    
    // 增量同步标志
    private final boolean deltaUpdate;
    
    /**
     * 构造器
     */
    public AnimationSyncPacket(Map<Integer, EntityBoneSyncData> entityDataMap, 
                             boolean compressed, boolean deltaUpdate) {
        this.entityDataMap = entityDataMap;
        this.compressed = compressed;
        this.deltaUpdate = deltaUpdate;
    }
    
    /**
     * 创建完整同步包
     */
    public static AnimationSyncPacket createFullSync(Map<Integer, EntityBoneSyncData> entityDataMap) {
        return new AnimationSyncPacket(entityDataMap, false, false);
    }
    
    /**
     * 创建压缩同步包
     */
    public static AnimationSyncPacket createCompressedSync(Map<Integer, EntityBoneSyncData> entityDataMap) {
        return new AnimationSyncPacket(entityDataMap, true, false);
    }
    
    /**
     * 创建增量同步包
     */
    public static AnimationSyncPacket createDeltaSync(Map<Integer, EntityBoneSyncData> entityDataMap) {
        return new AnimationSyncPacket(entityDataMap, false, true);
    }
    
    /**
     * 编码数据包
     */
    public void encode(FriendlyByteBuf buffer) {
        // 写入协议版本
        buffer.writeInt(PROTOCOL_VERSION);
        
        // 写入标志位
        byte flags = 0;
        if (compressed) flags |= 0x01;
        if (deltaUpdate) flags |= 0x02;
        buffer.writeByte(flags);
        
        // 写入实体数量
        buffer.writeInt(entityDataMap.size());
        
        // 写入每个实体的数据
        for (Map.Entry<Integer, EntityBoneSyncData> entry : entityDataMap.entrySet()) {
            encodeEntityData(buffer, entry.getValue());
        }
    }
    
    /**
     * 编码实体数据
     */
    private void encodeEntityData(FriendlyByteBuf buffer, EntityBoneSyncData entityData) {
        // 写入实体ID
        buffer.writeInt(entityData.entityId);
        
        // 写入首次同步标志
        buffer.writeBoolean(entityData.isFirstSync);
        
        // 如果是首次同步，写入模型位置
        if (entityData.isFirstSync && entityData.modelLocation != null) {
            buffer.writeResourceLocation(entityData.modelLocation);
        }
        
        // 写入骨骼变换数据
        buffer.writeInt(entityData.boneTransforms.size());
        for (Map.Entry<String, CompressedBoneTransform> entry : entityData.boneTransforms.entrySet()) {
            encodeBoneTransform(buffer, entry.getKey(), entry.getValue());
        }
        
        // 写入动画状态
        encodeAnimationState(buffer, entityData.animationState);
        
        // 写入时间戳
        buffer.writeLong(entityData.timestamp);
    }
    
    /**
     * 编码骨骼变换
     */
    private void encodeBoneTransform(FriendlyByteBuf buffer, String boneName, 
                                    CompressedBoneTransform transform) {
        // 写入骨骼名称
        buffer.writeUtf(boneName, 32767);
        
        // 写入压缩矩阵（16个short）
        for (int i = 0; i < 16; i++) {
            buffer.writeShort(transform.compressedMatrix[i]);
        }
        
        // 写入变化标志和精度等级
        buffer.writeByte(transform.changeFlags);
        buffer.writeByte(transform.precisionLevel);
    }
    
    /**
     * 编码动画状态
     */
    private void encodeAnimationState(FriendlyByteBuf buffer, EntityBoneSyncData.AnimationState state) {
        buffer.writeUtf(state.animationName, 32767);
        buffer.writeDouble(state.animationTime);
        buffer.writeDouble(state.animationSpeed);
        buffer.writeBoolean(state.looping);
    }
    
    /**
     * 解码数据包
     */
    public static AnimationSyncPacket decode(FriendlyByteBuf buffer) {
        // 读取协议版本
        int version = buffer.readInt();
        if (version != PROTOCOL_VERSION) {
            throw new IllegalStateException("Protocol version mismatch: expected " + 
                                          PROTOCOL_VERSION + ", got " + version);
        }
        
        // 读取标志位
        byte flags = buffer.readByte();
        boolean compressed = (flags & 0x01) != 0;
        boolean deltaUpdate = (flags & 0x02) != 0;
        
        // 读取实体数量
        int entityCount = buffer.readInt();
        Map<Integer, EntityBoneSyncData> entityDataMap = new HashMap<>(entityCount);
        
        // 读取每个实体的数据
        for (int i = 0; i < entityCount; i++) {
            EntityBoneSyncData entityData = decodeEntityData(buffer);
            entityDataMap.put(entityData.entityId, entityData);
        }
        
        return new AnimationSyncPacket(entityDataMap, compressed, deltaUpdate);
    }
    
    /**
     * 解码实体数据
     */
    private static EntityBoneSyncData decodeEntityData(FriendlyByteBuf buffer) {
        // 读取实体ID
        int entityId = buffer.readInt();
        
        // 读取首次同步标志
        boolean isFirstSync = buffer.readBoolean();
        
        // 如果是首次同步，读取模型位置
        ResourceLocation modelLocation = null;
        if (isFirstSync) {
            modelLocation = buffer.readResourceLocation();
        }
        
        // 读取骨骼变换数据
        int boneCount = buffer.readInt();
        Map<String, CompressedBoneTransform> boneTransforms = new HashMap<>(boneCount);
        for (int i = 0; i < boneCount; i++) {
            Map.Entry<String, CompressedBoneTransform> entry = decodeBoneTransform(buffer);
            boneTransforms.put(entry.getKey(), entry.getValue());
        }
        
        // 读取动画状态
        EntityBoneSyncData.AnimationState animationState = decodeAnimationState(buffer);
        
        // 读取时间戳
        long timestamp = buffer.readLong();
        
        return new EntityBoneSyncData(
                entityId,
                modelLocation,
                boneTransforms,
                animationState,
                timestamp,
                isFirstSync
        );
    }
    
    /**
     * 解码骨骼变换
     */
    private static Map.Entry<String, CompressedBoneTransform> decodeBoneTransform(FriendlyByteBuf buffer) {
        // 读取骨骼名称
        String boneName = buffer.readUtf(32767);
        
        // 读取压缩矩阵（16个short）
        short[] compressedMatrix = new short[16];
        for (int i = 0; i < 16; i++) {
            compressedMatrix[i] = buffer.readShort();
        }
        
        // 读取变化标志和精度等级
        byte changeFlags = buffer.readByte();
        byte precisionLevel = buffer.readByte();
        
        CompressedBoneTransform transform = new CompressedBoneTransform(
                boneName, compressedMatrix, changeFlags, precisionLevel
        );
        
        return Map.entry(boneName, transform);
    }
    
    /**
     * 解码动画状态
     */
    private static EntityBoneSyncData.AnimationState decodeAnimationState(FriendlyByteBuf buffer) {
        String animationName = buffer.readUtf(32767);
        double animationTime = buffer.readDouble();
        double animationSpeed = buffer.readDouble();
        boolean looping = buffer.readBoolean();
        
        return new EntityBoneSyncData.AnimationState(
                animationName, animationTime, animationSpeed, looping
        );
    }
    
    /**
     * 处理数据包（客户端执行）
     */
    /**
     * 处理接收到的数据包
     */
    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            try {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.player == null) return;
                
                // 记录开始时间
                long startTime = System.currentTimeMillis();
                
                // 记录接收到的实体数量
                System.out.println("Received animation sync packet for " + entityDataMap.size() + " entities");
                
                // 处理每个实体的同步数据
                for (EntityBoneSyncData syncData : entityDataMap.values()) {
                    int entityId = syncData.entityId;
                    
                    // 获取客户端实体
                    net.minecraft.world.entity.Entity entity = mc.level.getEntity(entityId);
                    if (entity == null) {
                        GeckoLib.LOGGER.warn("Entity {} not found on client", entityId);
                        continue;
                    }
                    
                    // 应用骨骼变换
                    applyBoneTransforms(entity, syncData);
                    
                    // 应用动画状态
                    applyAnimationState(entity, syncData.animationState);
                }
                
                // 记录性能数据
                long processingTime = System.currentTimeMillis() - startTime;
                int packetSize = estimatePacketSize();
                AnimationSyncPerformanceMonitor.getInstance().recordClientPacketReceived(
                    mc.player.getUUID(), 
                    this, 
                    packetSize, 
                    processingTime
                );
                
                // 使用独立的日志类记录接收内容
                AnimationSyncLogger.logReceivePacket(
                    mc.player.getUUID(),
                    entityDataMap,
                    deltaUpdate,
                    compressed,
                    packetSize,
                    processingTime
                );
                
            } catch (Exception e) {
                AnimationSyncLogger.logError("处理动画同步数据包失败", e);
            }
        });
        
        context.get().setPacketHandled(true);
    }
    
    /**
     * 应用骨骼变换到实体
     */
    private void applyBoneTransforms(net.minecraft.world.entity.Entity entity, EntityBoneSyncData syncData) {
        // TODO: 实现骨骼变换应用逻辑
        // 这里需要将压缩的骨骼变换应用到客户端实体的骨骼上
    }
    
    /**
     * 应用动画状态到实体
     */
    private void applyAnimationState(net.minecraft.world.entity.Entity entity, EntityBoneSyncData.AnimationState animationState) {
        // TODO: 实现动画状态应用逻辑
        // 这里需要将动画状态应用到客户端实体的动画系统上
    }
    
    /**
     * 估算数据包大小
     */
    private int estimatePacketSize() {
        int size = 4; // protocol version (int)
        size += 1; // flags (byte)
        size += 4; // entity count (int)
        
        for (EntityBoneSyncData entityData : entityDataMap.values()) {
            size += 4; // entity ID (int)
            size += 1; // first sync flag (boolean)
            
            if (entityData.isFirstSync && entityData.modelLocation != null) {
                size += 30; // ResourceLocation average size
            }
            
            size += 4; // bone count (int)
            
            for (CompressedBoneTransform transform : entityData.boneTransforms.values()) {
                size += transform.boneName.length() * 2; // UTF-16 encoding
                size += 32 + 2; // compressed matrix (16 shorts + 2 bytes)
            }
            
            size += entityData.animationState.animationName.length() * 2; // animation name
            size += 8 + 8 + 1; // time, speed, looping
            size += 8; // timestamp (long)
        }
        
        return size;
    }

    /**
     * 获取实体数据映射
     */
    public Map<Integer, EntityBoneSyncData> getEntityDataMap() {
        return entityDataMap;
    }
    
    /**
     * 获取估算的数据大小（字节）
     */
    public int getEstimatedSize() {
        int size = 4; // protocol version (int)
        size += 1; // flags (byte)
        size += 4; // entity count (int)
        
        for (EntityBoneSyncData entityData : entityDataMap.values()) {
            size += entityData.getEstimatedSize();
        }
        
        return size;
    }
    
    /**
     * 是否为压缩包
     */
    public boolean isCompressed() {
        return compressed;
    }
    
    /**
     * 是否为增量更新
     */
    public boolean isDeltaUpdate() {
        return deltaUpdate;
    }
}







