package com.catoxide.catoxidesbattlerebuild.server.entities;

import com.catoxide.catoxidesbattlerebuild.server.models.BoneModelData;
import net.minecraft.world.phys.Vec3;
import org.joml.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务器端实体管理器
 * 负责管理所有实体的骨骼动画、碰撞检测和网络同步
 */
public class ServerEntityManager {
    // 实体映射：UUID -> EntityCollection
    private final Map<UUID, EntityCollection> entityMap = new ConcurrentHashMap<>();
    
    // 实体ID映射：long -> UUID
    private final Map<Long, UUID> idMap = new ConcurrentHashMap<>();
    
    // 实体骨骼数据缓存
    private final Map<String, List<BoneModelData.BoneStaticData>> modelBoneCache = new ConcurrentHashMap<>();
    
    /**
     * 注册新实体
     */
    public void registerEntity(UUID uuid, long entityId, String modelName) {
        // 从缓存获取骨骼数据
        List<BoneModelData.BoneStaticData> boneData = modelBoneCache.computeIfAbsent(modelName, 
            k -> BoneModelData.loadModel(k).orElse(Collections.emptyList()));
        
        // 创建实体集合
        EntityCollection entityCollection = new EntityCollection(uuid, entityId, modelName, boneData);
        entityMap.put(uuid, entityCollection);
        idMap.put(entityId, uuid);
    }
    
    /**
     * 更新实体骨骼变换
     */
    public void updateEntitySkeleton(UUID uuid, Map<String, Matrix4f> boneTransforms) {
        EntityCollection entity = entityMap.get(uuid);
        if (entity != null) {
            entity.updateSkeleton(boneTransforms);
        }
    }
    
    /**
     * 获取实体的骨骼集合
     */
    public List<BoneCollection> getEntityBones(UUID uuid) {
        EntityCollection entity = entityMap.get(uuid);
        return entity != null ? entity.getBones() : Collections.emptyList();
    }
    
    /**
     * 获取实体的立方体集合
     */
    public List<CubeCollection> getEntityCubes(UUID uuid) {
        EntityCollection entity = entityMap.get(uuid);
        return entity != null ? entity.getCubes() : Collections.emptyList();
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
        
        Map<String, List<Vec3>> cubeVertices = null;
        
        // 遍历所有骨骼
        for (BoneCollection bone : entity.getBones()) {
            // 遍历骨骼上的所有立方体
            for (CubeCollection cube : bone.getCubes()) {
                if (cubeVertices == null) {
                    cubeVertices = new HashMap<>();
                }
                
                // 获取立方体的世界顶点
                List<Vec3> vertices = cube.getWorldVertices();
                cubeVertices.put(bone.getBoneName() + "_" + cube.getId(), vertices);
            }
        }
        
        return cubeVertices != null ? cubeVertices : Collections.emptyMap();
    }
    
    /**
     * 获取实体的边界框
     */
    public Optional<AABB> getEntityBoundingBox(UUID uuid) {
        EntityCollection entity = entityMap.get(uuid);
        if (entity == null) {
            return Optional.empty();
        }
        
        List<CubeCollection> allCubes = entity.getCubes();
        if (allCubes.isEmpty()) {
            return Optional.empty();
        }
        
        // 计算所有立方体的包围盒
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        
        for (CubeCollection cube : allCubes) {
            for (Vec3 vertex : cube.getWorldVertices()) {
                minX = Math.min(minX, vertex.x);
                minY = Math.min(minY, vertex.y);
                minZ = Math.min(minZ, vertex.z);
                maxX = Math.max(maxX, vertex.x);
                maxY = Math.max(maxY, vertex.y);
                maxZ = Math.max(maxZ, vertex.z);
            }
        }
        
        if (Double.isInfinite(minX)) {
            return Optional.empty();
        }
        
        return Optional.of(new AABB(minX, minY, minZ, maxX, maxY, maxZ));
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
     * 移除实体
     */
    public void removeEntity(UUID uuid) {
        entityMap.remove(uuid);
        idMap.remove(entityMap.get(uuid).getEntityId());
    }
    
    /**
     * 获取实体
     */
    public EntityCollection getEntity(UUID uuid) {
        return entityMap.get(uuid);
    }
    
    /**
     * 获取实体通过ID
     */
    public EntityCollection getEntityById(long entityId) {
        UUID uuid = idMap.get(entityId);
        return uuid != null ? entityMap.get(uuid) : null;
    }
    
    /**
     * 获取所有实体UUID
     */
    public Collection<UUID> getAllEntityUuids() {
        return entityMap.keySet();
    }
    
    /**
     * 清空所有实体
     */
    public void clearAllEntities() {
        entityMap.clear();
        idMap.clear();
    }
}