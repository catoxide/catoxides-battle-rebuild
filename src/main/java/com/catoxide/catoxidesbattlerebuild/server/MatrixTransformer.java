// [file name]: MatrixTransformer.java
package com.catoxide.catoxidesbattlerebuild.server;

import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.joml.Vector3f;

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
    public Map<String, List<Vector3f>> getEntityCubeVertices(Entity entity) {
        return ServerEntityManager.getInstance()
                .getEntityCubeVertices(entity.getUUID());
    }

    /**
     * 获取特定骨骼的cube顶点
     */
    public List<Vector3f> getBoneCubeVertices(Entity entity, String boneName) {
        Map<String, List<Vector3f>> allVertices = getEntityCubeVertices(entity);
        if (allVertices == null) return null;

        List<Vector3f> result = new ArrayList<>();
        for (Map.Entry<String, List<Vector3f>> entry : allVertices.entrySet()) {
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
        Map<String, List<Vector3f>> allVertices = getEntityCubeVertices(entity);
        if (allVertices == null || allVertices.isEmpty()) {
            return null;
        }

        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE;
        float maxY = Float.MIN_VALUE;
        float maxZ = Float.MIN_VALUE;

        for (List<Vector3f> vertices : allVertices.values()) {
            for (Vector3f vertex : vertices) {
                minX = Math.min(minX, vertex.x());
                minY = Math.min(minY, vertex.y());
                minZ = Math.min(minZ, vertex.z());
                maxX = Math.max(maxX, vertex.x());
                maxY = Math.max(maxY, vertex.y());
                maxZ = Math.max(maxZ, vertex.z());
            }
        }

        return new BoundingBox(
                new Vector3f(minX, minY, minZ),
                new Vector3f(maxX, maxY, maxZ)
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
    public RayHitResult raycast(Entity entity, Vector3f rayOrigin, Vector3f rayDirection) {
        Map<String, List<Vector3f>> cubeVertices = getEntityCubeVertices(entity);
        if (cubeVertices == null) return null;

        RayHitResult closestHit = null;
        float closestDistance = Float.MAX_VALUE;

        // 对每个cube进行检测（简化为AABB检测）
        for (Map.Entry<String, List<Vector3f>> entry : cubeVertices.entrySet()) {
            List<Vector3f> vertices = entry.getValue();
            if (vertices.size() < 8) continue;

            // 计算cube的AABB
            Vector3f min = new Vector3f(Float.MAX_VALUE);
            Vector3f max = new Vector3f(Float.MIN_VALUE);

            for (Vector3f vertex : vertices) {
                min.x = Math.min(min.x, vertex.x);
                min.y = Math.min(min.y, vertex.y);
                min.z = Math.min(min.z, vertex.z);
                max.x = Math.max(max.x, vertex.x);
                max.y = Math.max(max.y, vertex.y);
                max.z = Math.max(max.z, vertex.z);
            }

            // 应用实体位置
            min.add((float)entity.getX(), (float)entity.getY(), (float)entity.getZ());
            max.add((float)entity.getX(), (float)entity.getY(), (float)entity.getZ());

            // 射线与AABB相交检测
            Float distance = rayIntersectsAABB(rayOrigin, rayDirection, min, max);
            if (distance != null && distance < closestDistance) {
                closestDistance = distance;
                closestHit = new RayHitResult(
                        entity,
                        entry.getKey(),
                        rayOrigin.add(rayDirection.mul(distance, new Vector3f())),
                        distance
                );
            }
        }

        return closestHit;
    }

    private Float rayIntersectsAABB(Vector3f origin, Vector3f dir,
                                    Vector3f min, Vector3f max) {
        float tmin = 0.0f;
        float tmax = Float.MAX_VALUE;

        for (int i = 0; i < 3; i++) {
            if (Math.abs(dir.get(i)) < 1E-6) {
                // 射线平行于轴对齐平面
                if (origin.get(i) < min.get(i) || origin.get(i) > max.get(i)) {
                    return null;
                }
            } else {
                float ood = 1.0f / dir.get(i);
                float t1 = (min.get(i) - origin.get(i)) * ood;
                float t2 = (max.get(i) - origin.get(i)) * ood;

                if (t1 > t2) {
                    float temp = t1;
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
    public Vector3f toWorldCoordinates(Entity entity, Vector3f localVertex) {
        return new Vector3f(
                (float)(entity.getX() + localVertex.x),
                (float)(entity.getY() + localVertex.y),
                (float)(entity.getZ() + localVertex.z)
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
        public final Vector3f min;
        public final Vector3f max;

        public BoundingBox(Vector3f min, Vector3f max) {
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
                    new Vector3f(min).add((float)x, (float)y, (float)z),
                    new Vector3f(max).add((float)x, (float)y, (float)z)
            );
        }

        public Vector3f getCenter() {
            return new Vector3f(
                    (min.x + max.x) * 0.5f,
                    (min.y + max.y) * 0.5f,
                    (min.z + max.z) * 0.5f
            );
        }

        public Vector3f getSize() {
            return new Vector3f(
                    max.x - min.x,
                    max.y - min.y,
                    max.z - min.z
            );
        }
    }

    /**
     * 射线命中结果
     */
    public static class RayHitResult {
        public final Entity entity;
        public final String cubeKey;
        public final Vector3f hitPoint;
        public final float distance;

        public RayHitResult(Entity entity, String cubeKey, Vector3f hitPoint, float distance) {
            this.entity = entity;
            this.cubeKey = cubeKey;
            this.hitPoint = hitPoint;
            this.distance = distance;
        }

        public String getBoneName() {
            return cubeKey.split(":")[0];
        }
    }
}