package com.catoxide.catoxidesbattlerebuild.client;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.AnimationProcessor;
import software.bernie.geckolib.core.animatable.model.CoreGeoModel;
import software.bernie.geckolib.loading.object.BakedAnimations;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端实体管理器
 * 负责管理客户端实体的动画状态和骨骼矩阵
 * 
 * 核心功能：
 * 1. 管理实体集合（ClientEntityCollection）
 * 2. 管理模型集合（ClientModelCollection）
 * 3. 集成动画解算器（ClientAnimationResolver）
 * 4. 提供骨骼矩阵的存储、更新和获取
 * 5. 支持插值系统
 */
public class ClientEntityManager {
    
    private static ClientEntityManager INSTANCE;
    
    /**
     * 实体集合映射：UUID -> ClientEntityCollection
     */
    private final Map<UUID, ClientEntityCollection> entityCollections;
    
    /**
     * 模型集合映射：模型位置 -> ClientModelCollection
     */
    private final Map<ResourceLocation, ClientModelCollection> modelCollections;
    
    /**
     * 动画解算器
     */
    private final ClientAnimationResolver animationResolver;
    
    /**
     * 私有构造器（单例模式）
     */
    private ClientEntityManager() {
        this.entityCollections = new ConcurrentHashMap<>();
        this.modelCollections = new ConcurrentHashMap<>();
        this.animationResolver = new ClientAnimationResolver();
    }
    
    /**
     * 获取单例实例
     */
    public static ClientEntityManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ClientEntityManager();
        }
        return INSTANCE;
    }
    
    /**
     * 获取或创建实体集合
     */
    public ClientEntityCollection getOrCreateEntityCollection(UUID entityUUID) {
        return entityCollections.computeIfAbsent(entityUUID, uuid -> {
            // 尝试从Minecraft获取实体
            Entity entity = getEntityByUUID(uuid);
            
            // 创建新的实体集合
            return new ClientEntityCollection(
                    uuid,
                    null, // modelLocation - 将在后续设置
                    null, // modelCollection - 将在后续设置
                    entity
            );
        });
    }
    
    /**
     * 获取或创建模型集合
     */
    public ClientModelCollection getOrCreateModelCollection(
            ResourceLocation modelLocation,
            CoreGeoModel<?> coreModel,
            BakedGeoModel bakedModel,
            BakedAnimations bakedAnimations,
            AnimationProcessor<?> animationProcessor
    ) {
        return modelCollections.computeIfAbsent(modelLocation, location -> {
            return new ClientModelCollection(
                    location,
                    coreModel,
                    bakedModel,
                    bakedAnimations,
                    animationProcessor
            );
        });
    }
    
    /**
     * 获取所有已加载的模型位置
     */
    public Collection<ResourceLocation> getAllModelLocations() {
        return modelCollections.keySet();
    }
    
    /**
     * 更新骨骼矩阵（接收服务端发送的矩阵）
     * 
     * @param entityUUID 实体UUID
     * @param boneName 骨骼名称
     * @param boneMatrix 骨骼矩阵
     */
    public void updateBoneMatrix(UUID entityUUID, String boneName, Matrix4f boneMatrix) {
        ClientEntityCollection collection = entityCollections.get(entityUUID);
        if (collection != null) {
            collection.setBoneMatrix(boneName, boneMatrix);
            collection.setLastUpdateTime(System.currentTimeMillis());
        }
    }
    
    /**
     * 更新骨骼矩阵（使用Entity参数）
     * 
     * @param entity 实体
     * @param boneName 骨骼名称
     * @param boneMatrix 骨骼矩阵
     */
    public void updateBoneMatrix(Entity entity, String boneName, Matrix4f boneMatrix) {
        if (entity != null) {
            updateBoneMatrix(entity.getUUID(), boneName, boneMatrix);
        }
    }
    
    /**
     * 更新动画状态（用于客户端解算）
     * 
     * @param entityUUID 实体UUID
     * @param animationName 动画名称
     * @param animationTime 动画时间
     * @param animationSpeed 动画速度
     * @param looping 是否循环
     */
    public void updateAnimationState(
            UUID entityUUID,
            String animationName,
            double animationTime,
            double animationSpeed,
            boolean looping
    ) {
        ClientEntityCollection collection = getOrCreateEntityCollection(entityUUID);
        
        // 创建动画对象
        ResourceLocation animationId = ResourceLocation.parse(animationName);
        
        // 更新动画状态
        collection.updateAnimation(
                animationId,
                animationTime,
                animationSpeed,
                looping
        );
        collection.setLastUpdateTime(System.currentTimeMillis());
    }
    
    /**
     * 解算动画（客户端解算）
     * 
     * @param entityUUID 实体UUID
     * @param partialTick 部分tick
     * @return 骨骼矩阵映射
     */
    public Map<String, Matrix4f> resolveAnimation(UUID entityUUID, float partialTick) {
        ClientEntityCollection collection = entityCollections.get(entityUUID);
        if (collection == null) {
            return new ConcurrentHashMap<>();
        }
        
        return animationResolver.resolveAnimation(collection, partialTick);
    }
    
    /**
     * 获取骨骼矩阵
     * 
     * @param entityUUID 实体UUID
     * @param boneName 骨骼名称
     * @return 骨骼矩阵，如果不存在则返回null
     */
    public Matrix4f getBoneMatrix(UUID entityUUID, String boneName) {
        ClientEntityCollection collection = entityCollections.get(entityUUID);
        if (collection == null) {
            return null;
        }
        
        return collection.getBoneMatrix(boneName);
    }
    
    /**
     * 获取所有骨骼矩阵
     * 
     * @param entityUUID 实体UUID
     * @return 骨骼矩阵映射
     */
    public Map<String, Matrix4f> getAllBoneMatrices(UUID entityUUID) {
        ClientEntityCollection collection = entityCollections.get(entityUUID);
        if (collection == null) {
            return new ConcurrentHashMap<>();
        }
        
        return collection.getAllBoneMatrices();
    }
    
    /**
     * 设置实体集合的模型集合
     * 
     * @param entityUUID 实体UUID
     * @param modelCollection 模型集合
     */
    public void setEntityModelCollection(UUID entityUUID, ClientModelCollection modelCollection) {
        ClientEntityCollection collection = entityCollections.get(entityUUID);
        if (collection != null) {
            collection.setModelCollection(modelCollection);
        }
    }
    
    /**
     * 移除实体集合
     * 
     * @param entityUUID 实体UUID
     */
    public void removeEntityCollection(UUID entityUUID) {
        entityCollections.remove(entityUUID);
    }
    
    /**
     * 清理无效实体
     */
    public void cleanupInvalidEntities() {
        Iterator<Map.Entry<UUID, ClientEntityCollection>> iterator = entityCollections.entrySet().iterator();
        
        while (iterator.hasNext()) {
            Map.Entry<UUID, ClientEntityCollection> entry = iterator.next();
            ClientEntityCollection collection = entry.getValue();
            
            // 检查实体是否有效
            if (!collection.isValid()) {
                iterator.remove();
                continue;
            }
            
            // 检查实体是否仍然存在
            Entity entity = collection.getEntity();
            if (entity != null && !entity.isAlive()) {
                iterator.remove();
            }
        }
    }
    
    /**
     * 清理所有数据
     */
    public void cleanup() {
        entityCollections.clear();
        modelCollections.clear();
        animationResolver.cleanup();
    }
    
    /**
     * 获取实体数量
     */
    public int getEntityCount() {
        return entityCollections.size();
    }
    
    /**
     * 获取模型数量
     */
    public int getModelCount() {
        return modelCollections.size();
    }
    
    /**
     * 通过UUID获取实体
     */
    private Entity getEntityByUUID(UUID uuid) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level != null) {
                // 遍历所有实体查找匹配的UUID
                for (Entity entity : minecraft.level.entitiesForRendering()) {
                    if (entity.getUUID().equals(uuid)) {
                        return entity;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error getting entity by UUID: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * 获取动画解算器
     */
    public ClientAnimationResolver getAnimationResolver() {
        return animationResolver;
    }
    
    /**
     * 更新插值（每帧调用）
     * 
     * @param partialTick 部分tick
     */
    public void updateInterpolation(float partialTick) {
        for (ClientEntityCollection collection : entityCollections.values()) {
            if (collection.isValid()) {
                collection.updateInterpolation(partialTick);
            }
        }
    }
    
    /**
     * 获取实体集合
     */
    public ClientEntityCollection getEntityCollection(UUID entityUUID) {
        return entityCollections.get(entityUUID);
    }
    
    /**
     * 获取模型集合
     */
    public ClientModelCollection getModelCollection(ResourceLocation modelLocation) {
        return modelCollections.get(modelLocation);
    }
    
    /**
     * 获取所有实体集合
     */
    public Map<UUID, ClientEntityCollection> getAllEntityCollections() {
        return new HashMap<>(entityCollections);
    }
    
    /**
     * 获取所有模型集合
     */
    public Map<ResourceLocation, ClientModelCollection> getAllModelCollections() {
        return new HashMap<>(modelCollections);
    }
    
    /**
     * 检查实体集合是否存在
     */
    public boolean hasEntityCollection(UUID entityUUID) {
        return entityCollections.containsKey(entityUUID);
    }
    
    /**
     * 检查模型集合是否存在
     */
    public boolean hasModelCollection(ResourceLocation modelLocation) {
        return modelCollections.containsKey(modelLocation);
    }
    
    /**
     * 获取实体的当前动画
     */
    public Animation getCurrentAnimation(UUID entityUUID) {
        ClientEntityCollection collection = entityCollections.get(entityUUID);
        return collection != null ? collection.getCurrentAnimation() : null;
    }
    
    /**
     * 获取实体的动画时间
     */
    public double getAnimationTime(UUID entityUUID) {
        ClientEntityCollection collection = entityCollections.get(entityUUID);
        return collection != null ? collection.getAnimationTime() : 0.0;
    }
    
    /**
     * 获取实体的动画速度
     */
    public double getAnimationSpeed(UUID entityUUID) {
        ClientEntityCollection collection = entityCollections.get(entityUUID);
        return collection != null ? collection.getAnimationSpeed() : 1.0;
    }
    
    /**
     * 获取实体的动画循环状态
     */
    public boolean isLooping(UUID entityUUID) {
        ClientEntityCollection collection = entityCollections.get(entityUUID);
        return collection != null && collection.isLooping();
    }
    
    /**
     * 获取实体的最后更新时间
     */
    public long getLastUpdateTime(UUID entityUUID) {
        ClientEntityCollection collection = entityCollections.get(entityUUID);
        return collection != null ? collection.getLastUpdateTime() : 0L;
    }
    
    /**
     * 获取实体对应的Minecraft实体
     */
    public Entity getEntity(UUID entityUUID) {
        ClientEntityCollection collection = entityCollections.get(entityUUID);
        return collection != null ? collection.getEntity() : null;
    }
    
    /**
     * 获取实体的模型位置
     */
    public ResourceLocation getModelLocation(UUID entityUUID) {
        ClientEntityCollection collection = entityCollections.get(entityUUID);
        return collection != null ? collection.getModelLocation() : null;
    }
}