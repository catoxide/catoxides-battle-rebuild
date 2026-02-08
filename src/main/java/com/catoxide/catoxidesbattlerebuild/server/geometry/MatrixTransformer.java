// [file name]: MatrixTransformer.java
package com.catoxide.catoxidesbattlerebuild.server.geometry;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.*;

/**
 * 矩阵变换器，用于解析动画并计算Geocube顶点
 */
public class MatrixTransformer {
    private static final MatrixTransformer INSTANCE = new MatrixTransformer();

    private MatrixTransformer() {}

    public static MatrixTransformer getInstance() {
        return INSTANCE;
    }

    /**
     * 获取实体的骨骼变换矩阵
     */
    public Map<String, Matrix4f> getEntityBoneMatrices(Entity entity) {
        return ServerEntityManager.getInstance()
                .getEntityBoneMatrices(entity.getUUID());
    }

    /**
     * 获取实体的所有cube顶点
     */
    public Map<String, List<Vec3>> getEntityCubeVertices(Entity entity) {
        return ServerEntityManager.getInstance()
                .getEntityCubeVertices(entity.getUUID());
    }

    /**
     * 获取特定骨骼的cube顶点
     */
    public List<Vec3> getBoneCubeVertices(Entity entity, String boneName) {
        Map<String, List<Vec3>> allVertices = getEntityCubeVertices(entity);
        if (allVertices == null) return null;

        List<Vec3> result = new ArrayList<>();
        for (Map.Entry<String, List<Vec3>> entry : allVertices.entrySet()) {
            if (entry.getKey().startsWith(boneName + ":")) {
                result.addAll(entry.getValue());
            }
        }
        return result;
    }

    /**
     * 计算实体的包围盒（基于所有cube顶点）
     */
    public BoundingBox calculateEntityBoundingBox(Entity entity) {
        Map<String, List<Vec3>> allVertices = getEntityCubeVertices(entity);
        if (allVertices == null || allVertices.isEmpty()) {
            return null;
        }

        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE;
        double maxY = Double.MIN_VALUE;
        double maxZ = Double.MIN_VALUE;

        for (List<Vec3> vertices : allVertices.values()) {
            for (Vec3 vertex : vertices) {
                minX = Math.min(minX, vertex.x);
                minY = Math.min(minY, vertex.y);
                minZ = Math.min(minZ, vertex.z);
                maxX = Math.max(maxX, vertex.x);
                maxY = Math.max(maxY, vertex.y);
                maxZ = Math.max(maxZ, vertex.z);
            }
        }

        return new BoundingBox(
                new Vec3(minX, minY, minZ),
                new Vec3(maxX, maxY, maxZ)
        );
    }

    /**
     * 检测碰撞（AABB碰撞检测）
     */
    public boolean checkCollision(Entity entity1, Entity entity2) {
        BoundingBox box1 = calculateEntityBoundingBox(entity1);
        BoundingBox box2 = calculateEntityBoundingBox(entity2);

        if (box1 == null || box2 == null) return false;

        // 应用实体位置偏移
        box1 = box1.offset(entity1.getX(), entity1.getY(), entity1.getZ());
        box2 = box2.offset(entity2.getX(), entity2.getY(), entity2.getZ());

        return box1.intersects(box2);
    }

    /**
     * 检测射线与实体的碰撞
     */
    public RayHitResult raycast(Entity entity, Vec3 rayOrigin, Vec3 rayDirection) {
        Map<String, List<Vec3>> cubeVertices = getEntityCubeVertices(entity);
        if (cubeVertices == null) return null;

        RayHitResult closestHit = null;
        double closestDistance = Double.MAX_VALUE;

        // 对每个cube进行检测（简化为AABB检测）
        for (Map.Entry<String, List<Vec3>> entry : cubeVertices.entrySet()) {
            List<Vec3> vertices = entry.getValue();
            if (vertices.size() < 8) continue;

            // 计算cube的AABB
            Vec3 min = new Vec3(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);
            Vec3 max = new Vec3(Double.MIN_VALUE, Double.MIN_VALUE, Double.MIN_VALUE);

            for (Vec3 vertex : vertices) {
                min = new Vec3(Math.min(min.x, vertex.x), Math.min(min.y, vertex.y), Math.min(min.z, vertex.z));
                max = new Vec3(Math.max(max.x, vertex.x), Math.max(max.y, vertex.y), Math.max(max.z, vertex.z));
            }

            // 应用实体位置
            min = new Vec3(min.x + entity.getX(), min.y + entity.getY(), min.z + entity.getZ());
            max = new Vec3(max.x + entity.getX(), max.y + entity.getY(), max.z + entity.getZ());

            // 射线与AABB相交检测
            Double distance = rayIntersectsAABB(rayOrigin, rayDirection, min, max);
            if (distance != null && distance < closestDistance) {
                closestDistance = distance;
                closestHit = new RayHitResult(
                        entity,
                        entry.getKey(),
                        rayOrigin.add(rayDirection.multiply(distance)),
                        distance
                );
            }
        }

        return closestHit;
    }

    private Double rayIntersectsAABB(Vec3 origin, Vec3 dir,
                                    Vec3 min, Vec3 max) {
        double tmin = 0.0;
        double tmax = Double.MAX_VALUE;

        for (int i = 0; i < 3; i++) {
            double dirComponent = i == 0 ? dir.x : (i == 1 ? dir.y : dir.z);
            double originComponent = i == 0 ? origin.x : (i == 1 ? origin.y : origin.z);
            double minComponent = i == 0 ? min.x : (i == 1 ? min.y : min.z);
            double maxComponent = i == 0 ? max.x : (i == 1 ? max.y : max.z);

            if (Math.abs(dirComponent) < 1E-6) {
                // 射线平行于轴对齐平面
                if (originComponent < minComponent || originComponent > maxComponent) {
                    return null;
                }
            } else {
                double ood = 1.0 / dirComponent;
                double t1 = (minComponent - originComponent) * ood;
                double t2 = (maxComponent - originComponent) * ood;

                if (t1 > t2) {
                    double temp = t1;
                    t1 = t2;
                    t2 = temp;
                }

                tmin = Math.max(tmin, t1);
                tmax = Math.min(tmax, t2);

                if (tmin > tmax) {
                    return null;
                }
            }
        }

        return tmin;
    }

    /**
     * 计算顶点的世界坐标
     */
    public Vec3 toWorldCoordinates(Entity entity, Vec3 localVertex) {
        return new Vec3(
                entity.getX() + localVertex.x,
                entity.getY() + localVertex.y,
                entity.getZ() + localVertex.z
        );
    }

    /**
     * 获取实体动画的变换矩阵
     */
    public Matrix4f getBoneWorldMatrix(Entity entity, String boneName) {
        Map<String, Matrix4f> matrices = getEntityBoneMatrices(entity);
        if (matrices == null) return null;

        Matrix4f boneMatrix = matrices.get(boneName);
        if (boneMatrix == null) return null;

        // 应用实体位置变换
        Matrix4f entityMatrix = new Matrix4f()
                .translate((float)entity.getX(), (float)entity.getY(), (float)entity.getZ())
                .rotateY((float)Math.toRadians(-entity.getYRot())); // Minecraft的Y旋转是逆时针

        return entityMatrix.mul(boneMatrix, new Matrix4f());
    }

    /**
     * 包围盒类
     */
    public static class BoundingBox {
        public final Vec3 min;
        public final Vec3 max;

        public BoundingBox(Vec3 min, Vec3 max) {
            this.min = min;
            this.max = max;
        }

        public boolean intersects(BoundingBox other) {
            return (min.x <= other.max.x && max.x >= other.min.x) &&
                    (min.y <= other.max.y && max.y >= other.min.y) &&
                    (min.z <= other.max.z && max.z >= other.min.z);
        }

        public BoundingBox offset(double x, double y, double z) {
            return new BoundingBox(
                    new Vec3(min.x + x, min.y + y, min.z + z),
                    new Vec3(max.x + x, max.y + y, max.z + z)
            );
        }
    }

    /**
     * 射线命中结果类
     */
    public static class RayHitResult {
        public final Entity entity;
        public final String cubeKey;
        public final Vec3 hitPoint;
        public final double distance;

        public RayHitResult(Entity entity, String cubeKey, Vec3 hitPoint, double distance) {
            this.entity = entity;
            this.cubeKey = cubeKey;
            this.hitPoint = hitPoint;
            this.distance = distance;
        }
    }
}