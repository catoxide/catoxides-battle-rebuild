package com.catoxide.catoxidesbattlerebuild.server.temp;

import org.joml.Vector3f;
import org.joml.Matrix4f;
import java.util.*;
import com.catoxide.catoxidesbattlerebuild.server.temp.components.CubeCollection;

/**
 * OBB-OBB 碰撞检测器 (使用分离轴定理 SAT)
 */
public class OBBCollisionDetector {
    private static final OBBCollisionDetector INSTANCE = new OBBCollisionDetector();

    private OBBCollisionDetector() {}

    public static OBBCollisionDetector getInstance() {
        return INSTANCE;
    }

    /**
     * 检测两个OBB是否相交
     */
    public boolean testOBBOBB(BoneHitboxComponent obb1, BoneHitboxComponent obb2) {
        if (!obb1.isActive() || !obb2.isActive()) return false;

        // 检查obb1的每个cube与obb2的每个cube是否相交
        for (CubeCollection cube1 : obb1.getCubeHitboxes().values()) {
            if (!cube1.isActive()) continue;

            for (CubeCollection cube2 : obb2.getCubeHitboxes().values()) {
                if (!cube2.isActive()) continue;

                if (testCubeCubeCollision(cube1, cube2)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 检测两个CubeHitboxComponent是否相交
     */
    private boolean testCubeCubeCollision(CubeCollection cube1, CubeCollection cube2) {
        Vector3f[] axes1 = getCubeAxes(cube1);
        Vector3f[] axes2 = getCubeAxes(cube2);

        // 测试15个分离轴
        // Cube1的3个轴
        for (int i = 0; i < 3; i++) {
            if (!overlapOnAxis(cube1, cube2, axes1[i])) return false;
        }

        // Cube2的3个轴
        for (int i = 0; i < 3; i++) {
            if (!overlapOnAxis(cube1, cube2, axes2[i])) return false;
        }

        // 9个叉积轴
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                Vector3f axis = axes1[i].cross(axes2[j], new Vector3f());
                if (axis.lengthSquared() < 1e-6f) continue; // 平行轴
                axis.normalize();
                if (!overlapOnAxis(cube1, cube2, axis)) return false;
            }
        }

        return true;
    }

    /**
     * 检测射线与OBB的相交
     */
    public Float raycastOBB(Vector3f rayOrigin, Vector3f rayDirection,
                            BoneHitboxComponent obb) {
        if (!obb.isActive()) return null;

        Float closestHit = null;

        // 检查射线与每个cube的相交
        for (CubeCollection cube : obb.getCubeHitboxes().values()) {
            if (!cube.isActive()) continue;

            Float hitDistance = raycastCube(rayOrigin, rayDirection, cube);
            if (hitDistance != null) {
                if (closestHit == null || hitDistance < closestHit) {
                    closestHit = hitDistance;
                }
            }
        }

        return closestHit;
    }

    /**
     * 检测射线与单个Cube的相交
     */
    private Float raycastCube(Vector3f rayOrigin, Vector3f rayDirection,
                              CubeCollection cube) {
        if (!cube.isActive()) return null;

        Vector3f center = cube.getWorldCenter();
        Vector3f[] axes = getCubeAxes(cube);
        Vector3f halfExtents = cube.getHalfExtents();

        // 转换到Cube局部空间
        Matrix4f transform = createCubeToWorldTransform(cube);
        Matrix4f inverseTransform = new Matrix4f(transform).invert();

        Vector3f localOrigin = inverseTransform.transformPosition(rayOrigin, new Vector3f());
        Vector3f localDir = inverseTransform.transformDirection(rayDirection, new Vector3f());
        localDir.normalize();

        // AABB射线检测 (在局部空间)
        float tMin = Float.NEGATIVE_INFINITY;
        float tMax = Float.POSITIVE_INFINITY;

        for (int i = 0; i < 3; i++) {
            if (Math.abs(localDir.get(i)) < 1e-6f) {
                // 射线平行于这个轴
                if (localOrigin.get(i) < -halfExtents.get(i) ||
                        localOrigin.get(i) > halfExtents.get(i)) {
                    return null; // 不相交
                }
            } else {
                float invD = 1.0f / localDir.get(i);
                float t1 = (-halfExtents.get(i) - localOrigin.get(i)) * invD;
                float t2 = (halfExtents.get(i) - localOrigin.get(i)) * invD;

                if (t1 > t2) {
                    float temp = t1;
                    t1 = t2;
                    t2 = temp;
                }

                tMin = Math.max(tMin, t1);
                tMax = Math.min(tMax, t2);

                if (tMin > tMax) return null;
            }
        }

        return tMin > 0 ? tMin : tMax > 0 ? tMax : null;
    }

    /**
     * 检测球形与OBB的相交
     */
    public boolean testSphereOBB(Vector3f sphereCenter, float radius,
                                 BoneHitboxComponent obb) {
        if (!obb.isActive()) return false;

        // 检查球与每个cube的相交
        for (CubeCollection cube : obb.getCubeHitboxes().values()) {
            if (!cube.isActive()) continue;

            if (testSphereCube(sphereCenter, radius, cube)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 检测球形与单个Cube的相交
     */
    private boolean testSphereCube(Vector3f sphereCenter, float radius,
                                   CubeCollection cube) {
        if (!cube.isActive()) return false;

        // 将球心转换到Cube局部空间
        Matrix4f transform = createCubeToWorldTransform(cube);
        Matrix4f inverseTransform = new Matrix4f(transform).invert();
        Vector3f localSphereCenter = inverseTransform.transformPosition(sphereCenter, new Vector3f());
        Vector3f halfExtents = cube.getHalfExtents();

        // 计算局部空间中最近的点
        Vector3f closestPoint = new Vector3f();
        for (int i = 0; i < 3; i++) {
            float value = localSphereCenter.get(i);
            if (value > halfExtents.get(i)) value = halfExtents.get(i);
            else if (value < -halfExtents.get(i)) value = -halfExtents.get(i);
            closestPoint.setComponent(i, value);
        }

        // 计算距离
        float distanceSq = localSphereCenter.distanceSquared(closestPoint);
        return distanceSq <= radius * radius;
    }

    /**
     * 获取OBB的碰撞信息 (包括穿透深度和法线)
     */
    public Optional<CollisionInfo> getCollisionInfo(BoneHitboxComponent obb1,
                                                    BoneHitboxComponent obb2) {
        if (!obb1.isActive() || !obb2.isActive()) return Optional.empty();

        float minOverlap = Float.MAX_VALUE;
        Vector3f minAxis = new Vector3f();
        boolean minAxisFromObb1 = true;

        // 检查所有cube对
        for (CubeCollection cube1 : obb1.getCubeHitboxes().values()) {
            if (!cube1.isActive()) continue;

            for (CubeCollection cube2 : obb2.getCubeHitboxes().values()) {
                if (!cube2.isActive()) continue;

                if (testCubeCubeCollision(cube1, cube2)) {
                    // 计算这个碰撞对的穿透深度和法线
                    CollisionInfo info = calculateCubeCubeCollisionInfo(cube1, cube2);
                    if (info != null && info.penetrationDepth < minOverlap) {
                        minOverlap = info.penetrationDepth;
                        minAxis.set(info.normal);
                        minAxisFromObb1 = true;
                    }
                }
            }
        }

        if (minOverlap == Float.MAX_VALUE) {
            return Optional.empty();
        }

        // 确保法线从obb1指向obb2
        Vector3f centerDiff = obb2.getWorldCenter() != null ? 
                obb2.getWorldCenter().sub(obb1.getWorldCenter(), new Vector3f()) : 
                new Vector3f();
        if (minAxis.dot(centerDiff) < 0) {
            minAxis.mul(-1);
        }

        return Optional.of(new CollisionInfo(minOverlap, minAxis));
    }

    /**
     * 计算两个Cube之间的碰撞信息
     */
    private CollisionInfo calculateCubeCubeCollisionInfo(CubeCollection cube1, CubeCollection cube2) {
        Vector3f[] axes1 = getCubeAxes(cube1);
        Vector3f[] axes2 = getCubeAxes(cube2);

        float minOverlap = Float.MAX_VALUE;
        Vector3f minAxis = new Vector3f();

        // 检查所有轴，找到最小穿透
        for (int i = 0; i < 3; i++) {
            float overlap = getOverlapOnAxis(cube1, cube2, axes1[i]);
            if (overlap < minOverlap) {
                minOverlap = overlap;
                minAxis.set(axes1[i]);
            }
        }

        for (int i = 0; i < 3; i++) {
            float overlap = getOverlapOnAxis(cube1, cube2, axes2[i]);
            if (overlap < minOverlap) {
                minOverlap = overlap;
                minAxis.set(axes2[i]);
            }
        }

        // 9个叉积轴
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                Vector3f axis = axes1[i].cross(axes2[j], new Vector3f());
                if (axis.lengthSquared() < 1e-6f) continue; // 平行轴
                axis.normalize();
                float overlap = getOverlapOnAxis(cube1, cube2, axis);
                if (overlap < minOverlap) {
                    minOverlap = overlap;
                    minAxis.set(axis);
                }
            }
        }

        return new CollisionInfo(minOverlap, minAxis);
    }

    // 内部辅助方法
    private Vector3f[] getCubeAxes(CubeCollection cube) {
        Vector3f[] axes = new Vector3f[3];
        Matrix4f rotMatrix = new Matrix4f().rotation(cube.getWorldOrientation());
        axes[0] = rotMatrix.transformDirection(new Vector3f(1, 0, 0));
        axes[1] = rotMatrix.transformDirection(new Vector3f(0, 1, 0));
        axes[2] = rotMatrix.transformDirection(new Vector3f(0, 0, 1));
        return axes;
    }

    private Matrix4f createCubeToWorldTransform(CubeCollection cube) {
        return new Matrix4f()
                .translate(cube.getWorldCenter())
                .rotate(cube.getWorldOrientation());
    }

    private boolean overlapOnAxis(CubeCollection cube1, CubeCollection cube2,
                                  Vector3f axis) {
        float projection1 = getProjectionRadius(cube1, axis);
        float projection2 = getProjectionRadius(cube2, axis);
        float centerDistance = Math.abs(
                cube2.getWorldCenter().sub(cube1.getWorldCenter()).dot(axis)
        );
        return centerDistance <= projection1 + projection2;
    }

    private float getOverlapOnAxis(CubeCollection cube1, CubeCollection cube2,
                                   Vector3f axis) {
        float projection1 = getProjectionRadius(cube1, axis);
        float projection2 = getProjectionRadius(cube2, axis);
        float centerDistance = Math.abs(
                cube2.getWorldCenter().sub(cube1.getWorldCenter()).dot(axis)
        );
        return projection1 + projection2 - centerDistance;
    }

    private float getProjectionRadius(CubeCollection cube, Vector3f axis) {
        Vector3f[] axes = getCubeAxes(cube);
        Vector3f halfExtents = cube.getHalfExtents();

        float radius = 0;
        for (int i = 0; i < 3; i++) {
            radius += Math.abs(axis.dot(axes[i])) * halfExtents.get(i);
        }
        return radius;
    }

    /**
     * 碰撞信息容器
     */
    public static class CollisionInfo {
        public final float penetrationDepth;
        public final Vector3f normal;

        public CollisionInfo(float penetrationDepth, Vector3f normal) {
            this.penetrationDepth = penetrationDepth;
            this.normal = new Vector3f(normal);
        }
    }
}