package com.catoxide.catoxidesbattlerebuild.server.entities;

import com.catoxide.catoxidesbattlerebuild.server.collision.AdvancedCollisionDetector;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实体骨骼受击盒系统
 * 整合BoneCollection、CubeCollection和HitboxComponent的功能
 * 提供完整的受击盒管理系统
 */
public class EntityBoneHitboxSystem {
    
    private static final EntityBoneHitboxSystem INSTANCE = new EntityBoneHitboxSystem();
    
    // 实体ID -> 骨骼集合映射
    private final Map<Long, Map<String, BoneCollection>> entityBoneCollections = new ConcurrentHashMap<>();
    
    // 实体ID -> 受击盒组件映射
    private final Map<Long, Map<String, HitboxComponent>> entityHitboxComponents = new ConcurrentHashMap<>();
    
    // 活跃实体列表
    private final Set<Long> activeEntities = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    private final AdvancedCollisionDetector collisionDetector = AdvancedCollisionDetector.getInstance();
    
    private EntityBoneHitboxSystem() {}
    
    public static EntityBoneHitboxSystem getInstance() {
        return INSTANCE;
    }
    
    /**
     * 注册实体及其骨骼数据
     */
    public void registerEntity(long entityId, Map<String, BoneCollection> boneCollections) {
        entityBoneCollections.put(entityId, boneCollections);
        activeEntities.add(entityId);
        
        // 为每个骨骼创建默认的受击盒组件
        Map<String, HitboxComponent> hitboxComponents = new HashMap<>();
        for (String boneName : boneCollections.keySet()) {
            HitboxComponent component = new HitboxComponent(entityId, boneName);
            hitboxComponents.put(boneName, component);
        }
        entityHitboxComponents.put(entityId, hitboxComponents);
    }
    
    /**
     * 从模板批量注册实体受击盒组件
     */
    public void registerEntityWithTemplates(long entityId, 
                                          Map<String, BoneCollection> boneCollections,
                                          Map<String, String> boneTemplateMappings) {
        registerEntity(entityId, boneCollections);
        
        Map<String, HitboxComponent> components = entityHitboxComponents.get(entityId);
        if (components != null) {
            for (Map.Entry<String, String> entry : boneTemplateMappings.entrySet()) {
                String boneName = entry.getKey();
                String templateName = entry.getValue();
                
                if (components.containsKey(boneName)) {
                    HitboxComponent component = HitboxComponent.fromTemplate(entityId, boneName, templateName);
                    components.put(boneName, component);
                }
            }
        }
    }
    
    /**
     * 注销实体
     */
    public void unregisterEntity(long entityId) {
        entityBoneCollections.remove(entityId);
        entityHitboxComponents.remove(entityId);
        activeEntities.remove(entityId);
    }
    
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
            
            // 获取对应的受击盒组件
            Map<String, HitboxComponent> hitboxComponents = entityHitboxComponents.get(entityId);
            HitboxComponent hitboxComponent = null;
            if (hitboxComponents != null) {
                hitboxComponent = hitboxComponents.get(result.boneName());
            }
            
            // 检查受击盒是否激活
            if (hitboxComponent != null && !hitboxComponent.isValidHit()) {
                return Optional.empty();
            }
            
            return Optional.of(new EntityHitResult(
                result.entityId(),
                result.boneName(),
                result.cubeId(),
                result.hitPoint(),
                result.surfaceNormal(),
                result.distance(),
                hitboxComponent
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
    
    /**
     * 处理实体击中事件
     */
    public EntityHitResult processEntityHit(long entityId, Vec3 hitPoint, float incomingDamage) {
        Optional<EntityHitResult> hitResult = findClosestHitOnEntity(entityId, hitPoint);
        
        if (hitResult.isPresent()) {
            EntityHitResult result = hitResult.get();
            
            if (result.hitboxComponent() != null) {
                // 记录击中
                result.hitboxComponent().recordHit(incomingDamage);
                
                // 计算实际伤害
                float actualDamage = result.hitboxComponent().calculateReceivedDamage(incomingDamage);
                
                // 创建更新后的结果
                return new EntityHitResult(
                    result.entityId(),
                    result.boneName(),
                    result.cubeId(),
                    result.hitPoint(),
                    result.surfaceNormal(),
                    result.distance(),
                    result.hitboxComponent(),
                    actualDamage
                );
            }
        }
        
        return null;
    }
    
    /**
     * 查找实体上最接近指定点的击中结果
     */
    private Optional<EntityHitResult> findClosestHitOnEntity(long entityId, Vec3 hitPoint) {
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
                        
                        Map<String, HitboxComponent> hitboxComponents = entityHitboxComponents.get(entityId);
                        HitboxComponent hitboxComponent = null;
                        if (hitboxComponents != null) {
                            hitboxComponent = hitboxComponents.get(boneCollection.getBoneName());
                        }
                        
                        closestResult = new EntityHitResult(
                            entityId,
                            boneCollection.getBoneName(),
                            cubeCollection.getId(),
                            hitPoint,
                            new Vec3(0, 1, 0), // 简化的法线
                            distance,
                            hitboxComponent
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
     * 检查点是否在实体的任何受击盒内
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
     * 获取实体的受击盒组件
     */
    public Optional<HitboxComponent> getHitboxComponent(long entityId, String boneName) {
        Map<String, HitboxComponent> components = entityHitboxComponents.get(entityId);
        if (components != null) {
            return Optional.ofNullable(components.get(boneName));
        }
        return Optional.empty();
    }
    
    /**
     * 获取实体的所有受击盒组件
     */
    public Collection<HitboxComponent> getEntityHitboxComponents(long entityId) {
        Map<String, HitboxComponent> components = entityHitboxComponents.get(entityId);
        if (components != null) {
            return components.values();
        }
        return Collections.emptyList();
    }
    
    /**
     * 击中结果记录
     */
    public record EntityHitResult(
        long entityId,
        String boneName,
        String cubeId,
        Vec3 hitPoint,
        Vec3 surfaceNormal,
        double distance,
        HitboxComponent hitboxComponent
    ) {
        // 重载构造函数，包含实际伤害
        public EntityHitResult(long entityId, String boneName, String cubeId, Vec3 hitPoint,
                              Vec3 surfaceNormal, double distance, HitboxComponent hitboxComponent,
                              float actualDamage) {
            this(entityId, boneName, cubeId, hitPoint, surfaceNormal, distance, hitboxComponent);
            this.actualDamage = actualDamage;
        }
        
        private float actualDamage;
        
        public float getActualDamage() {
            return actualDamage;
        }
    }
    
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