package com.catoxide.catoxidesbattlerebuild.server.geometry;

import com.catoxide.catoxidesbattlerebuild.server.models.BoneModelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务器端实体管理器
 * 负责管理所有实体的骨骼动画、碰撞检测和网络同步
 */
public class ServerEntityManager {
    // 单例实例
    private static final ServerEntityManager INSTANCE = new ServerEntityManager();
    
    // 实体映射：UUID -> EntityCollection
    private final Map<UUID, EntityCollection> entityMap = new ConcurrentHashMap<>();
    
    // 实体ID映射：long -> UUID
    private final Map<Long, UUID> idMap = new ConcurrentHashMap<>();
    
    // 实体骨骼数据缓存
    private final Map<String, BoneModelData> modelBoneCache = new ConcurrentHashMap<>();
    
    private ServerEntityManager() {}
    
    public static ServerEntityManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * 注册新实体
     */
    public void registerEntity(UUID uuid, long entityId, String modelName) {
        // 从缓存获取骨骼数据
        BoneModelData boneData = modelBoneCache.get(modelName);
        if (boneData == null) {
            throw new IllegalArgumentException("Model data not found for model: " + modelName);
        }
        
        // 创建实体集合 - 使用新架构的构造函数
        EntityCollection entityCollection = EntityCollection.create(
            uuid,
            ResourceLocation.parse(modelName),
            null, // modelCollection - 暂时为null，需要从BoneModelData创建
            null  // entity - 暂时为null
        );
        
        entityMap.put(uuid, entityCollection);
        idMap.put(entityId, uuid);
    }
    
    /**
     * 更新实体骨骼变换
     */
    public void updateEntitySkeleton(UUID uuid, Map<String, Matrix4f> boneTransforms) {
        EntityCollection entity = entityMap.get(uuid);
        if (entity != null) {
            // EntityCollection是record，不可变，需要重新创建
            EntityCollection updated = entity.withDynamicData(boneTransforms, null);
            entityMap.put(uuid, updated);
        }
    }
    
    /**
     * 获取实体的骨骼集合
     */
    public List<BoneCollection> getEntityBones(UUID uuid) {
        EntityCollection entity = entityMap.get(uuid);
        if (entity == null) {
            return Collections.emptyList();
        }
        
        // 从ModelCollection中获取骨骼数据
        // 暂时返回空列表，需要实现从ModelCollection提取骨骼的逻辑
        return Collections.emptyList();
    }
    
    /**
     * 获取实体的立方体集合
     */
    public List<CubeCollection> getEntityCubes(UUID uuid) {
        EntityCollection entity = entityMap.get(uuid);
        if (entity == null) {
            return Collections.emptyList();
        }
        
        // 从ModelCollection中获取立方体数据
        // 暂时返回空列表，需要实现从ModelCollection提取立方体的逻辑
        return Collections.emptyList();
    }
    
    /**
     * 计算实体的立方体顶点
     * 这个方法根据骨骼变换计算所有立方体的世界顶点
     */
    public Map<String, List<Vec3>> getEntityCubeVertices(UUID uuid) {
        EntityCollection entity = entityMap.get(uuid);
        if (entity == null) {
            return Collections.emptyMap();
        }
        
        // 暂时返回空映射，需要实现从ModelCollection和骨骼变换计算顶点的逻辑
        return Collections.emptyMap();
    }
    
    /**
     * 获取实体的边界框
     */
    public Optional<AABB> getEntityBoundingBox(UUID uuid) {
        EntityCollection entity = entityMap.get(uuid);
        if (entity == null) {
            return Optional.empty();
        }
        
        // 暂时返回空，需要实现从ModelCollection计算边界框的逻辑
        return Optional.empty();
    }
    
    /**
     * 检查点是否在实体的碰撞箱内
     */
    public boolean isPointInEntity(UUID uuid, Vec3 point) {
        List<CubeCollection> cubes = getEntityCubes(uuid);
        for (CubeCollection cube : cubes) {
            if (cube.containsPoint(point)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 获取实体的骨骼变换矩阵
     */
    public Map<String, org.joml.Matrix4f> getEntityBoneMatrices(java.util.UUID uuid) {
        EntityCollection entity = entityMap.get(uuid);
        return entity != null ? entity.boneMatrices() : java.util.Collections.emptyMap();
    }
    
    /**
     * 移除实体
     */
    public void removeEntity(java.util.UUID uuid) {
        EntityCollection entity = entityMap.get(uuid);
        if (entity != null) {
            idMap.remove(entity.entityId());
            entityMap.remove(uuid);
        }
    }
    
    /**
     * 获取实体
     */
    public EntityCollection getEntity(java.util.UUID uuid) {
        return entityMap.get(uuid);
    }
    
    /**
     * 获取实体通过ID
     */
    public EntityCollection getEntityById(long entityId) {
        java.util.UUID uuid = idMap.get(entityId);
        return uuid != null ? entityMap.get(uuid) : null;
    }
    
    /**
     * 获取所有实体UUID
     */
    public java.util.Collection<java.util.UUID> getAllEntityUuids() {
        return entityMap.keySet();
    }
    
    /**
     * 清空所有实体
     */
    public void clearAllEntities() {
        entityMap.clear();
        idMap.clear();
    }
    
    /**
     * 清理无效实体
     */
    public void cleanupInvalidEntities() {
        // 这里可以添加清理逻辑，比如移除不存在的实体
        // 暂时留空
    }
    
    /**
     * 注册实体（重载方法，接受Entity参数）
     */
    public void registerEntity(net.minecraft.world.entity.Entity entity, net.minecraft.resources.ResourceLocation modelLocation) {
        registerEntity(entity.getUUID(), entity.getId(), modelLocation.toString());
    }
    
    /**
     * 注销实体（接受Entity参数）
     */
    public void unregisterEntity(net.minecraft.world.entity.Entity entity) {
        removeEntity(entity.getUUID());
    }
}