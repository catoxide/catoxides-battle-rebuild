package com.catoxide.catoxidesbattlerebuild.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import software.bernie.geckolib.core.animation.Animation;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端实体集合
 * 管理单个实体的动画状态、骨骼矩阵和解算数据
 * 
 * 与服务端的EntityCollection类似，但专注于客户端的动画解算和插值
 */
public class ClientEntityCollection {
    private final UUID entityId;
    private final ResourceLocation modelLocation;
    private final ClientModelCollection modelCollection;
    private final Entity entity;
    
    // 动画状态
    private ResourceLocation currentAnimationId;
    private Animation currentAnimation;
    private double animationTime;
    private double animationSpeed;
    private boolean animationLooping;
    
    // 骨骼矩阵（当前帧）
    private final Map<String, Matrix4f> boneMatrices;
    
    // 骨骼矩阵（上一帧，用于插值）
    private final Map<String, Matrix4f> previousBoneMatrices;
    
    // 插值相关
    private float interpolationFactor;
    private long lastUpdateTime;
    private long updateInterval;
    
    // 实体变换（用于世界坐标计算）
    private final Matrix4f entityTransform;
    
    // 性能统计
    private int updateCount;
    private long totalUpdateTime;
    
    /**
     * 构造器
     */
    public ClientEntityCollection(
            UUID entityId,
            ResourceLocation modelLocation,
            ClientModelCollection modelCollection,
            Entity entity
    ) {
        this.entityId = entityId;
        this.modelLocation = modelLocation;
        this.modelCollection = modelCollection;
        this.entity = entity;
        
        // 初始化动画状态
        this.currentAnimationId = null;
        this.currentAnimation = null;
        this.animationTime = 0.0;
        this.animationSpeed = 1.0;
        this.animationLooping = false;
        
        // 初始化骨骼矩阵
        this.boneMatrices = new ConcurrentHashMap<>();
        this.previousBoneMatrices = new ConcurrentHashMap<>();
        
        // 初始化插值参数
        this.interpolationFactor = 0.0f;
        this.lastUpdateTime = System.currentTimeMillis();
        this.updateInterval = 50; // 默认20fps更新
        
        // 初始化实体变换
        this.entityTransform = new Matrix4f();
        
        // 初始化性能统计
        this.updateCount = 0;
        this.totalUpdateTime = 0;
    }
    
    /**
     * 更新动画状态
     */
    public void updateAnimation(
            ResourceLocation animationId,
            double animationTime,
            double animationSpeed,
            boolean looping
    ) {
        // 保存当前状态为上一帧
        this.previousBoneMatrices.clear();
        this.previousBoneMatrices.putAll(this.boneMatrices);
        
        // 更新动画状态
        this.currentAnimationId = animationId;
        this.animationTime = animationTime;
        this.animationSpeed = animationSpeed;
        this.animationLooping = looping;
        
        // 查找动画
        if (modelCollection != null) {
            this.currentAnimation = modelCollection.findAnimation(animationId);
        }
        
        // 重置插值因子
        this.interpolationFactor = 0.0f;
        this.lastUpdateTime = System.currentTimeMillis();
    }
    
    /**
     * 获取当前骨骼矩阵（带插值）
     */
    public Matrix4f getBoneMatrix(String boneName, float partialTick) {
        Matrix4f current = boneMatrices.get(boneName);
        Matrix4f previous = previousBoneMatrices.get(boneName);
        
        if (current == null) {
            return null;
        }
        
        if (previous == null || partialTick >= 1.0f) {
            return new Matrix4f(current);
        }
        
        // 线性插值
        Matrix4f result = new Matrix4f();
        result.lerp(previous, current, partialTick);
        return result;
    }
    
    /**
     * 获取当前骨骼矩阵（不带插值）
     */
    public Matrix4f getBoneMatrix(String boneName) {
        Matrix4f matrix = boneMatrices.get(boneName);
        return matrix != null ? new Matrix4f(matrix) : null;
    }
    
    /**
     * 获取所有骨骼矩阵
     */
    public Map<String, Matrix4f> getAllBoneMatrices() {
        return new ConcurrentHashMap<>(boneMatrices);
    }
    
    /**
     * 设置骨骼矩阵
     */
    public void setBoneMatrix(String boneName, Matrix4f matrix) {
        if (matrix != null) {
            boneMatrices.put(boneName, new Matrix4f(matrix));
        }
    }
    
    /**
     * 设置所有骨骼矩阵
     */
    public void setAllBoneMatrices(Map<String, Matrix4f> matrices) {
        boneMatrices.clear();
        if (matrices != null) {
            matrices.forEach((boneName, matrix) -> {
                if (matrix != null) {
                    boneMatrices.put(boneName, new Matrix4f(matrix));
                }
            });
        }
    }
    
    /**
     * 更新实体变换（基于实体位置和旋转）
     */
    public void updateEntityTransform() {
        if (entity == null) {
            return;
        }
        
        entityTransform.identity();
        
        // 应用实体位置
        entityTransform.translate(
                (float) entity.getX(),
                (float) entity.getY(),
                (float) entity.getZ()
        );
        
        // 应用实体旋转（Y轴）
        entityTransform.rotateY((float) Math.toRadians(-entity.getYRot()));
        
        // 应用实体旋转（X轴，用于头部等）
        entityTransform.rotateX((float) Math.toRadians(entity.getXRot()));
    }
    
    /**
     * 获取实体变换
     */
    public Matrix4f getEntityTransform() {
        return new Matrix4f(entityTransform);
    }
    
    /**
     * 更新插值因子
     */
    public void updateInterpolation() {
        long currentTime = System.currentTimeMillis();
        long deltaTime = currentTime - lastUpdateTime;
        
        if (updateInterval > 0) {
            interpolationFactor = Math.min(1.0f, (float) deltaTime / updateInterval);
        } else {
            interpolationFactor = 1.0f;
        }
    }
    
    /**
     * 获取插值因子
     */
    public float getInterpolationFactor() {
        return interpolationFactor;
    }
    
    /**
     * 设置更新间隔
     */
    public void setUpdateInterval(long intervalMs) {
        this.updateInterval = intervalMs;
    }
    
    /**
     * 获取当前动画
     */
    public Animation getCurrentAnimation() {
        return currentAnimation;
    }
    
    /**
     * 获取当前动画ID
     */
    public ResourceLocation getCurrentAnimationId() {
        return currentAnimationId;
    }
    
    /**
     * 获取动画时间
     */
    public double getAnimationTime() {
        return animationTime;
    }
    
    /**
     * 获取动画速度
     */
    public double getAnimationSpeed() {
        return animationSpeed;
    }
    
    /**
     * 是否循环播放
     */
    public boolean isAnimationLooping() {
        return animationLooping;
    }
    
    /**
     * 获取实体ID
     */
    public UUID getEntityId() {
        return entityId;
    }
    
    /**
     * 获取模型位置
     */
    public ResourceLocation getModelLocation() {
        return modelLocation;
    }
    
    /**
     * 获取模型集合
     */
    public ClientModelCollection getModelCollection() {
        return modelCollection;
    }
    
    /**
     * 获取实体
     */
    public Entity getEntity() {
        return entity;
    }
    
    /**
     * 检查实体是否有效
     */
    public boolean isValid() {
        return entity != null &&
                !entity.isRemoved() &&
                entity.isAlive() &&
                entity.level() != null;
    }
    
    /**
     * 记录更新统计
     */
    public void recordUpdate(long updateTime) {
        updateCount++;
        totalUpdateTime += updateTime;
    }
    
    /**
     * 获取平均更新时间
     */
    public double getAverageUpdateTime() {
        return updateCount > 0 ? (double) totalUpdateTime / updateCount : 0.0;
    }
    
    /**
     * 获取更新次数
     */
    public int getUpdateCount() {
        return updateCount;
    }
    
    @Override
    public String toString() {
        return String.format("ClientEntityCollection[entityId=%s, model=%s, animation=%s, bones=%d, updates=%d]",
                entityId, modelLocation, currentAnimationId, boneMatrices.size(), updateCount);
    }
}
