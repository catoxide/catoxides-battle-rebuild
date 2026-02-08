package com.catoxide.catoxidesbattlerebuild.server.geometry;

import com.catoxide.catoxidesbattlerebuild.server.collision.AdvancedCollisionDetector;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实体骨骼几何系统
 * 负责处理实体的骨骼集合和碰撞检测（几何层）
 * 游戏性逻辑已迁移至bodypart包中的EntityBoneHitboxSystem
 */
public class EntityBoneHitboxSystem {
    
    private static final EntityBoneHitboxSystem INSTANCE = new EntityBoneHitboxSystem();
    
    // 实体ID -> 骨骼集合映射
    private final Map<Long, Map<String, BoneCollection>> entityBoneCollections = new ConcurrentHashMap<>();
    
    // 活跃实体列表
    private final Set<Long> activeEntities = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    private final AdvancedCollisionDetector collisionDetector = AdvancedCollisionDetector.getInstance();
    
    private EntityBoneHitboxSystem() {}
    
    public static EntityBoneHitboxSystem getInstance() {
        return INSTANCE;
    }
    
    // ==================== 实体注册管理 ====================
    
    /**
     * 注册实体及其骨骼数据
     */
    public void registerEntity(long entityId, Map<String, BoneCollection> boneCollections) {
        entityBoneCollections.put(entityId, boneCollections);
        activeEntities.add(entityId);
    }
    
    /**
     * 注销实体
     */
    public void unregisterEntity(long entityId) {
        entityBoneCollections.remove(entityId);
        activeEntities.remove(entityId);
    }
    
    /**
     * 检查实体是否已注册
     */
    public boolean isEntityRegistered(long entityId) {
        return activeEntities.contains(entityId);
    }
    
    /**
     * 获取所有活跃实体ID
     */
    public Set<Long> getActiveEntities() {
        return Collections.unmodifiableSet(activeEntities);
    }
    
    // ==================== 骨骼集合管理 ====================
    
    /**
     * 获取实体的骨骼集合
     */
    public Map<String, BoneCollection> getEntityBoneCollections(long entityId) {
        Map<String, BoneCollection> boneCollections = entityBoneCollections.get(entityId);
        return boneCollections != null ? Collections.unmodifiableMap(boneCollections) : Collections.emptyMap();
    }
    
    /**
     * 获取指定骨骼的集合
     */
    public Optional<BoneCollection> getBoneCollection(long entityId, String boneName) {
        Map<String, BoneCollection> boneCollections = entityBoneCollections.get(entityId);
        if (boneCollections != null) {
            return Optional.ofNullable(boneCollections.get(boneName));
        }
        return Optional.empty();
    }
    
    /**
     * 添加骨骼集合到实体
     */
    public void addBoneCollection(long entityId, String boneName, BoneCollection boneCollection) {
        if (!entityBoneCollections.containsKey(entityId)) {
            entityBoneCollections.put(entityId, new HashMap<>());
            activeEntities.add(entityId);
        }
        entityBoneCollections.get(entityId).put(boneName, boneCollection);
    }
    
    /**
     * 移除实体的骨骼集合
     */
    public void removeBoneCollection(long entityId, String boneName) {
        Map<String, BoneCollection> boneCollections = entityBoneCollections.get(entityId);
        if (boneCollections != null) {
            boneCollections.remove(boneName);
        }
    }
    
    // ==================== 变换更新 ====================
    
    /**
     * 更新实体骨骼变换
     */
    public void updateEntityTransforms(long entityId, float partialTicks) {
        Map<String, BoneCollection> boneCollections = entityBoneCollections.get(entityId);
        if (boneCollections != null) {
            for (BoneCollection boneCollection : boneCollections.values()) {
                boneCollection.updateWorldTransform(partialTicks);
            }
        }
    }
    
    /**
     * 更新所有活跃实体的骨骼变换
     */
    public void updateAllEntityTransforms(float partialTicks) {
        for (Long entityId : activeEntities) {
            updateEntityTransforms(entityId, partialTicks);
        }
    }
    
    // ==================== 碰撞检测 ====================
    
    /**
     * 检测射线与实体的碰撞
     */
    public Optional<EntityHitResult> raycastEntity(long entityId, Vec3 start, Vec3 direction, double maxDistance) {
        Map<String, BoneCollection> boneCollections = entityBoneCollections.get(entityId);
        if (boneCollections == null || !activeEntities.contains(entityId)) {
            return Optional.empty();
        }
        
        Optional<AdvancedCollisionDetector.CollisionResult> collisionResult = 
            collisionDetector.raycastEntity(entityId, boneCollections.values(), start, direction, maxDistance);
        
        if (collisionResult.isPresent()) {
            AdvancedCollisionDetector.CollisionResult result = collisionResult.get();
            
            return Optional.of(new EntityHitResult(
                result.entityId(),
                result.boneName(),
                result.cubeId(),
                result.hitPoint(),
                result.surfaceNormal(),
                result.distance()
            ));
        }
        
        return Optional.empty();
    }
    
    /**
     * 检测实体间的碰撞
     */
    public List<EntityCollisionResult> detectEntityCollisions(long entityAId, long entityBId) {
        Map<String, BoneCollection> boneCollectionsA = entityBoneCollections.get(entityAId);
        Map<String, BoneCollection> boneCollectionsB = entityBoneCollections.get(entityBId);
        
        if (boneCollectionsA == null || boneCollectionsB == null ||
            !activeEntities.contains(entityAId) || !activeEntities.contains(entityBId)) {
            return Collections.emptyList();
        }
        
        return collisionDetector.detectEntityCollisions(
            entityAId, boneCollectionsA.values(),
            entityBId, boneCollectionsB.values()
        );
    }
    
    // ==================== 几何查询 ====================
    
    /**
     * 查找实体上最接近指定点的骨骼和立方体
     */
    public Optional<EntityHitResult> findClosestHitOnEntity(long entityId, Vec3 hitPoint) {
        Map<String, BoneCollection> boneCollections = entityBoneCollections.get(entityId);
        if (boneCollections == null || !activeEntities.contains(entityId)) {
            return Optional.empty();
        }
        
        EntityHitResult closestResult = null;
        double closestDistance = Double.MAX_VALUE;
        
        for (BoneCollection boneCollection : boneCollections.values()) {
            for (CubeCollection cubeCollection : boneCollection.getCubeCollections()) {
                if (cubeCollection.containsPoint(hitPoint)) {
                    double distance = hitPoint.distanceTo(new Vec3(
                        cubeCollection.getWorldTransform().m30(),
                        cubeCollection.getWorldTransform().m31(),
                        cubeCollection.getWorldTransform().m32()
                    ));
                    
                    if (distance < closestDistance) {
                        closestDistance = distance;
                        
                        closestResult = new EntityHitResult(
                            entityId,
                            boneCollection.getBoneName(),
                            cubeCollection.getId(),
                            hitPoint,
                            new Vec3(0, 1, 0), // 简化的法线
                            distance
                        );
                    }
                }
            }
        }
        
        return Optional.ofNullable(closestResult);
    }
    
    /**
     * 获取实体的边界框
     */
    public Optional<Vec3[]> getEntityBoundingBox(long entityId) {
        Map<String, BoneCollection> boneCollections = entityBoneCollections.get(entityId);
        if (boneCollections == null || !activeEntities.contains(entityId)) {
            return Optional.empty();
        }
        
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE, maxY = Double.MIN_VALUE, maxZ = Double.MIN_VALUE;
        
        for (BoneCollection boneCollection : boneCollections.values()) {
            for (CubeCollection cubeCollection : boneCollection.getCubeCollections()) {
                List<Vec3> vertices = cubeCollection.getWorldVertices();
                for (Vec3 vertex : vertices) {
                    minX = Math.min(minX, vertex.x);
                    minY = Math.min(minY, vertex.y);
                    minZ = Math.min(minZ, vertex.z);
                    maxX = Math.max(maxX, vertex.x);
                    maxY = Math.max(maxY, vertex.y);
                    maxZ = Math.max(maxZ, vertex.z);
                }
            }
        }
        
        if (Double.isInfinite(minX)) {
            return Optional.empty();
        }
        
        return Optional.of(new Vec3[]{
            new Vec3(minX, minY, minZ),
            new Vec3(maxX, maxY, maxZ)
        });
    }
    
    /**
     * 检查点是否在实体的任何骨骼集合内
     */
    public boolean isPointInEntityHitbox(long entityId, Vec3 point) {
        Map<String, BoneCollection> boneCollections = entityBoneCollections.get(entityId);
        if (boneCollections == null || !activeEntities.contains(entityId)) {
            return false;
        }
        
        for (BoneCollection boneCollection : boneCollections.values()) {
            if (boneCollection.containsPoint(point)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 检查点是否在指定的骨骼集合内
     */
    public boolean isPointInBoneHitbox(long entityId, String boneName, Vec3 point) {
        Optional<BoneCollection> boneCollection = getBoneCollection(entityId, boneName);
        if (boneCollection.isPresent()) {
            return boneCollection.get().containsPoint(point);
        }
        return false;
    }
    
    // ==================== 清理 ====================
    
    /**
     * 清空所有数据
     */
    public void clear() {
        entityBoneCollections.clear();
        activeEntities.clear();
    }
    
    // ==================== 结果记录 ====================
    
    /**
     * 击中结果记录
     */
    public record EntityHitResult(
        long entityId,
        String boneName,
        String cubeId,
        Vec3 hitPoint,
        Vec3 surfaceNormal,
        double distance
    ) {}
    
    /**
     * 实体碰撞结果记录
     */
    public record EntityCollisionResult(
        long entityAId,
        String boneAName,
        String cubeAId,
        long entityBId,
        String boneBName,
        String cubeBId,
        CubeCollection cubeA,
        CubeCollection cubeB
    ) {}
}
