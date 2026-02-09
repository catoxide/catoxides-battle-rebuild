package com.catoxide.catoxidesbattlerebuild.server.collision;

import com.catoxide.catoxidesbattlerebuild.server.geometry.BoneCollection;
import com.catoxide.catoxidesbattlerebuild.server.geometry.CubeCollection;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Matrix4f;

import java.util.*;

/**
 * 高级碰撞检测器
 * 整合各种碰撞检测算法，提供完整的碰撞检测功能
 */
public class AdvancedCollisionDetector {
    
    private static final AdvancedCollisionDetector INSTANCE = new AdvancedCollisionDetector();
    
    private AdvancedCollisionDetector() {}
    
    public static AdvancedCollisionDetector getInstance() {
        return INSTANCE;
    }
    //todo:碰撞检测与伤害联动
    /**
     * 检测射线与实体骨骼的碰撞
     */
    public Optional<CollisionResult> raycastEntity(long entityId, 
                                                  Collection<BoneCollection> boneCollections, 
                                                  Vec3 start, Vec3 direction, double maxDistance) {
        CollisionResult closestResult = null;
        double closestDistance = Double.MAX_VALUE;
        
        for (BoneCollection boneCollection : boneCollections) {
            for (CubeCollection cubeCollection : boneCollection.getCubeCollections()) {
                Optional<RayIntersectionResult> intersection = raycastCube(cubeCollection, start, direction, maxDistance);
                
                if (intersection.isPresent()) {
                    double distance = intersection.get().distance();
                    if (distance < closestDistance) {
                        closestDistance = distance;
                        closestResult = new CollisionResult(
                            entityId,
                            boneCollection.getBoneName(),
                            cubeCollection.getId(),
                            intersection.get().point(),
                            intersection.get().normal(),
                            distance,
                            cubeCollection
                        );
                    }
                }
            }
        }
        
        return Optional.ofNullable(closestResult);
    }
    
    /**
     * 射线与单个立方体的碰撞检测
     */
    public Optional<RayIntersectionResult> raycastCube(CubeCollection cubeCollection, 
                                                      Vec3 rayStart, Vec3 rayDirection, 
                                                      double maxDistance) {
        // 获取OBB的顶点和轴
        List<Vec3> obbVertices = cubeCollection.getWorldVertices();
        Matrix4f worldTransform = cubeCollection.getWorldTransform();
        
        // 使用射线-包围盒碰撞检测算法
        // 这里简化实现，实际应该使用更精确的算法
        
        // 通过变换矩阵反向变换射线到局部空间进行检测
        // 由于代码复杂度，这里采用简化的AABB检测作为示例
        
        // 获取AABB边界
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE, maxY = Double.MIN_VALUE, maxZ = Double.MIN_VALUE;
        
        for (Vec3 vertex : obbVertices) {
            minX = Math.min(minX, vertex.x);
            minY = Math.min(minY, vertex.y);
            minZ = Math.min(minZ, vertex.z);
            maxX = Math.max(maxX, vertex.x);
            maxY = Math.max(maxY, vertex.y);
            maxZ = Math.max(maxZ, vertex.z);
        }
        
        // 简化的射线-AABB相交检测
        Optional<Vec3> intersection = rayAABBCollision(rayStart, rayDirection, 
                                                     new Vec3(minX, minY, minZ), 
                                                     new Vec3(maxX, maxY, maxZ));
        
        if (intersection.isPresent()) {
            Vec3 hitPoint = intersection.get();
            double distance = rayStart.distanceTo(hitPoint);
            
            if (distance <= maxDistance) {
                // 计算表面法线（这是一个简化版本）
                Vec3 normal = calculateSurfaceNormal(cubeCollection, hitPoint);
                
                return Optional.of(new RayIntersectionResult(hitPoint, normal, distance));
            }
        }
        
        return Optional.empty();
    }
    
    /**
     * 射线-AABB碰撞检测
     */
    private Optional<Vec3> rayAABBCollision(Vec3 rayStart, Vec3 rayDir, Vec3 minBounds, Vec3 maxBounds) {
        double tMin = 0.0;
        double tMax = Double.MAX_VALUE;
        
        Vec3 invDir = new Vec3(1.0 / rayDir.x, 1.0 / rayDir.y, 1.0 / rayDir.z);
        
        // X轴
        double t1 = (minBounds.x - rayStart.x) * invDir.x;
        double t2 = (maxBounds.x - rayStart.x) * invDir.x;
        double tNearX = Math.min(t1, t2);
        double tFarX = Math.max(t1, t2);
        
        tMin = Math.max(tMin, tNearX);
        tMax = Math.min(tMax, tFarX);
        
        if (tMin > tMax || tMax < 0) {
            return Optional.empty();
        }
        
        // Y轴
        t1 = (minBounds.y - rayStart.y) * invDir.y;
        t2 = (maxBounds.y - rayStart.y) * invDir.y;
        double tNearY = Math.min(t1, t2);
        double tFarY = Math.max(t1, t2);
        
        tMin = Math.max(tMin, tNearY);
        tMax = Math.min(tMax, tFarY);
        
        if (tMin > tMax || tMax < 0) {
            return Optional.empty();
        }
        
        // Z轴
        t1 = (minBounds.z - rayStart.z) * invDir.z;
        t2 = (maxBounds.z - rayStart.z) * invDir.z;
        double tNearZ = Math.min(t1, t2);
        double tFarZ = Math.max(t1, t2);
        
        tMin = Math.max(tMin, tNearZ);
        tMax = Math.min(tMax, tFarZ);
        
        if (tMin > tMax || tMax < 0) {
            return Optional.empty();
        }
        
        // 返回最近的交点
        double t = (tMin >= 0) ? tMin : tMax;
        Vec3 hitPoint = rayStart.add(rayDir.multiply(t, t, t));
        
        return Optional.of(hitPoint);
    }
    
    /**
     * 计算表面法线（简化版）
     */
    private Vec3 calculateSurfaceNormal(CubeCollection cubeCollection, Vec3 hitPoint) {
        // 获取OBB中心点
        Vector3f center = new Vector3f();
        cubeCollection.getWorldTransform().transformPosition(center);
        
        // 简化的法线计算
        Vec3 centerVec = new Vec3(center.x(), center.y(), center.z());
        Vec3 toCenter = hitPoint.subtract(centerVec);
        
        // 找到最大的分量，确定哪个面被击中
        double absX = Math.abs(toCenter.x);
        double absY = Math.abs(toCenter.y);
        double absZ = Math.abs(toCenter.z);
        
        if (absX >= absY && absX >= absZ) {
            // X方向面
            return new Vec3(Math.signum(toCenter.x), 0, 0);
        } else if (absY >= absZ) {
            // Y方向面
            return new Vec3(0, Math.signum(toCenter.y), 0);
        } else {
            // Z方向面
            return new Vec3(0, 0, Math.signum(toCenter.z));
        }
    }
    
    /**
     * 实体间的碰撞检测
     */
    public List<EntityCollisionResult> detectEntityCollisions(
            long entityAId, Collection<BoneCollection> entityABones,
            long entityBId, Collection<BoneCollection> entityBBones) {
        
        List<EntityCollisionResult> collisions = new ArrayList<>();
        
        for (BoneCollection boneA : entityABones) {
            for (CubeCollection cubeA : boneA.getCubeCollections()) {
                for (BoneCollection boneB : entityBBones) {
                    for (CubeCollection cubeB : boneB.getCubeCollections()) {
                        if (testCubeCubeCollision(cubeA, cubeB)) {
                            collisions.add(new EntityCollisionResult(
                                entityAId, boneA.getBoneName(), cubeA.getId(),
                                entityBId, boneB.getBoneName(), cubeB.getId(),
                                cubeA, cubeB
                            ));
                        }
                    }
                }
            }
        }
        
        return collisions;
    }
    
    /**
     * 两个立方体之间的碰撞检测
     */
    public boolean testCubeCubeCollision(CubeCollection cubeA, CubeCollection cubeB) {
        // 获取两个OBB的顶点
        List<Vec3> verticesA = cubeA.getWorldVertices();
        List<Vec3> verticesB = cubeB.getWorldVertices();
        
        // 使用分离轴定理进行OBB-OBB碰撞检测
        // 这里简化实现，实际应使用完整的SAT算法
        
        // 检查是否有重叠（简化版）
        // 获取AABB边界
        double minXA = Double.MAX_VALUE, minYA = Double.MAX_VALUE, minZA = Double.MAX_VALUE;
        double maxXA = Double.MIN_VALUE, maxYA = Double.MIN_VALUE, maxZA = Double.MIN_VALUE;
        
        for (Vec3 vertex : verticesA) {
            minXA = Math.min(minXA, vertex.x);
            minYA = Math.min(minYA, vertex.y);
            minZA = Math.min(minZA, vertex.z);
            maxXA = Math.max(maxXA, vertex.x);
            maxYA = Math.max(maxYA, vertex.y);
            maxZA = Math.max(maxZA, vertex.z);
        }
        
        double minXB = Double.MAX_VALUE, minYB = Double.MAX_VALUE, minZB = Double.MAX_VALUE;
        double maxXB = Double.MIN_VALUE, maxYB = Double.MIN_VALUE, maxZB = Double.MIN_VALUE;
        
        for (Vec3 vertex : verticesB) {
            minXB = Math.min(minXB, vertex.x);
            minYB = Math.min(minYB, vertex.y);
            minZB = Math.min(minZB, vertex.z);
            maxXB = Math.max(maxXB, vertex.x);
            maxYB = Math.max(maxYB, vertex.y);
            maxZB = Math.max(maxZB, vertex.z);
        }
        
        // 检查AABB是否重叠
        boolean overlapsX = minXA <= maxXB && minXB <= maxXA;
        boolean overlapsY = minYA <= maxYB && minYB <= maxYA;
        boolean overlapsZ = minZA <= maxZB && minZB <= maxZA;
        
        return overlapsX && overlapsY && overlapsZ;
    }
    
    /**
     * 球体与OBB的碰撞检测
     */
    public boolean testSphereOBBCollision(Vec3 sphereCenter, double sphereRadius, CubeCollection obb) {
        // 获取OBB的中心点和变换
        Vector3f obbCenter = new Vector3f();
        obb.getWorldTransform().transformPosition(obbCenter);
        
        // 将球体中心转换到OBB的局部坐标系
        Matrix4f inverseTransform = new Matrix4f(obb.getWorldTransform()).invert();
        Vector3f localSphereCenter = new Vector3f((float)sphereCenter.x, (float)sphereCenter.y, (float)sphereCenter.z);
        inverseTransform.transformPosition(localSphereCenter);
        
        // 获取OBB的半尺寸（在局部空间）
        Vector3f halfSize = new Vector3f(obb.getSize().x() / 2.0f, obb.getSize().y() / 2.0f, obb.getSize().z() / 2.0f);
        
        // 将局部球心约束到OBB内部
        Vector3f clampedCenter = new Vector3f(
            Math.max(-halfSize.x(), Math.min(halfSize.x(), localSphereCenter.x())),
            Math.max(-halfSize.y(), Math.min(halfSize.y(), localSphereCenter.y())),
            Math.max(-halfSize.z(), Math.min(halfSize.z(), localSphereCenter.z()))
        );
        
        // 计算约束点到球心的距离
        float distanceSquared = new Vector3f(localSphereCenter).sub(clampedCenter).lengthSquared();
        
        return distanceSquared <= (sphereRadius * sphereRadius);
    }
    
    /**
     * 碰撞结果记录
     */
    public record CollisionResult(
        long entityId,
        String boneName,
        String cubeId,
        Vec3 hitPoint,
        Vec3 surfaceNormal,
        double distance,
        CubeCollection cubeCollection
    ) {}
    
    /**
     * 射线相交结果记录
     */
    public record RayIntersectionResult(
        Vec3 point,
        Vec3 normal,
        double distance
    ) {}
    
    /**
     * 实体间碰撞结果记录
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