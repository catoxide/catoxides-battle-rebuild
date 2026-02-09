package com.catoxide.catoxidesbattlerebuild.network;

import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * 实体骨骼同步数据
 * 
 * 包含单个实体的所有骨骼变换信息
 */
public class EntityBoneSyncData {
    
    // 实体ID
    public final int entityId;
    
    // 模型位置（用于客户端加载模型，仅首次同步）
    public final ResourceLocation modelLocation;
    
    // 骨骼变换数据（压缩格式）
    public final Map<String, CompressedBoneTransform> boneTransforms;
    
    // 动画状态（简化版）
    public final AnimationState animationState;
    
    // 更新时间戳
    public final long timestamp;
    
    // 是否为首次同步（需要发送模型位置）
    public final boolean isFirstSync;
    
    /**
     * 构造器
     */
    public EntityBoneSyncData(int entityId, ResourceLocation modelLocation,
                             Map<String, CompressedBoneTransform> boneTransforms,
                             AnimationState animationState, long timestamp, boolean isFirstSync) {
        this.entityId = entityId;
        this.modelLocation = modelLocation;
        this.boneTransforms = boneTransforms;
        this.animationState = animationState;
        this.timestamp = timestamp;
        this.isFirstSync = isFirstSync;
    }
    
    /**
     * 创建首次同步数据
     */
    public static EntityBoneSyncData createFirstSync(int entityId, ResourceLocation modelLocation,
                                                     Map<String, CompressedBoneTransform> boneTransforms,
                                                     AnimationState animationState) {
        return new EntityBoneSyncData(
                entityId,
                modelLocation,
                boneTransforms,
                animationState,
                System.currentTimeMillis(),
                true
        );
    }
    
    /**
     * 创建增量同步数据
     */
    public static EntityBoneSyncData createDeltaSync(int entityId,
                                                     Map<String, CompressedBoneTransform> boneTransforms,
                                                     AnimationState animationState) {
        return new EntityBoneSyncData(
                entityId,
                null, // 增量同步不发送模型位置
                boneTransforms,
                animationState,
                System.currentTimeMillis(),
                false
        );
    }
    
    /**
     * 获取估算的数据大小（字节）
     */
    public int getEstimatedSize() {
        int size = 4; // entityId (int)
        
        if (isFirstSync && modelLocation != null) {
            // 模型位置（假设平均30字节）
            size += 30;
        }
        
        // 骨骼变换数据
        size += 4; // map size (int)
        for (CompressedBoneTransform transform : boneTransforms.values()) {
            size += transform.getEstimatedSize();
        }
        
        // 动画状态（假设平均20字节）
        size += 20;
        
        // 时间戳（8字节）
        size += 8;
        
        return size;
    }
    
    /**
     * 动画状态（简化版）
     */
    public static class AnimationState {
        public final String animationName;
        public final double animationTime;
        public final double animationSpeed;
        public final boolean looping;
        
        public AnimationState(String animationName, double animationTime,
                            double animationSpeed, boolean looping) {
            this.animationName = animationName;
            this.animationTime = animationTime;
            this.animationSpeed = animationSpeed;
            this.looping = looping;
        }
        
        /**
         * 获取默认动画状态
         */
        public static AnimationState getDefault() {
            return new AnimationState("", 0.0, 1.0, false);
        }
        
        /**
         * 检查是否为默认状态
         */
        public boolean isDefault() {
            return animationName.isEmpty() && 
                   animationTime == 0.0 && 
                   animationSpeed == 1.0 && 
                   !looping;
        }
    }
}
