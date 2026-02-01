package com.catoxide.catoxidesbattlerebuild.server.hitboxsystem;

import org.joml.Vector3f;
import org.joml.Quaternionf;
import org.joml.Matrix4f;
import java.util.*;
import com.catoxide.catoxidesbattlerebuild.server.hitboxsystem.*;

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

        Vector3f[] axes1 = getObbAxes(obb1);
        Vector3f[] axes2 = getObbAxes(obb2);

        // 测试15个分离轴
        // OBB1的3个轴
        for (int i = 0; i < 3; i++) {
            if (!overlapOnAxis(obb1, obb2, axes1[i])) return false;
        }

        // OBB2的3个轴
        for (int i = 0; i < 3; i++) {
            if (!overlapOnAxis(obb1, obb2, axes2[i])) return false;
        }

        // 9个叉积轴
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                Vector3f axis = axes1[i].cross(axes2[j], new Vector3f());
                if (axis.lengthSquared() < 1e-6f) continue; // 平行轴
                axis.normalize();
                if (!overlapOnAxis(obb1, obb2, axis)) return false;
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

        Vector3f center = obb.getWorldCenter();
        Vector3f[] axes = getObbAxes(obb);
        Vector3f halfExtents = obb.getHalfExtents();

        // 转换到OBB局部空间
        Matrix4f transform = createOBBToWorldTransform(obb);
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

        // 将球心转换到OBB局部空间
        Matrix4f transform = createOBBToWorldTransform(obb);
        Matrix4f inverseTransform = new Matrix4f(transform).invert();
        Vector3f localSphereCenter = inverseTransform.transformPosition(sphereCenter, new Vector3f());
        Vector3f halfExtents = obb.getHalfExtents();

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
        if (!testOBBOBB(obb1, obb2)) return Optional.empty();

        Vector3f[] axes1 = getObbAxes(obb1);
        Vector3f[] axes2 = getObbAxes(obb2);

        float minOverlap = Float.MAX_VALUE;
        Vector3f minAxis = new Vector3f();
        boolean minAxisFromObb1 = true;

        // 检查所有轴，找到最小穿透
        for (int i = 0; i < 3; i++) {
            float overlap = getOverlapOnAxis(obb1, obb2, axes1[i]);
            if (overlap < minOverlap) {
                minOverlap = overlap;
                minAxis.set(axes1[i]);
                minAxisFromObb1 = true;
            }
        }

        for (int i = 0; i < 3; i++) {
            float overlap = getOverlapOnAxis(obb1, obb2, axes2[i]);
            if (overlap < minOverlap) {
                minOverlap = overlap;
                minAxis.set(axes2[i]);
                minAxisFromObb1 = false;
            }
        }

        // 确保法线从obb1指向obb2
        Vector3f centerDiff = obb2.getWorldCenter().sub(obb1.getWorldCenter(), new Vector3f());
        if (minAxis.dot(centerDiff) < 0) {
            minAxis.mul(-1);
        }

        return Optional.of(new CollisionInfo(minOverlap, minAxis));
    }

    // 内部辅助方法
    private Vector3f[] getObbAxes(BoneHitboxComponent obb) {
        Vector3f[] axes = new Vector3f[3];
        Matrix4f rotMatrix = new Matrix4f().rotation(obb.getWorldOrientation());
        axes[0] = rotMatrix.transformDirection(new Vector3f(1, 0, 0));
        axes[1] = rotMatrix.transformDirection(new Vector3f(0, 1, 0));
        axes[2] = rotMatrix.transformDirection(new Vector3f(0, 0, 1));
        return axes;
    }

    private Matrix4f createOBBToWorldTransform(BoneHitboxComponent obb) {
        return new Matrix4f()
                .translate(obb.getWorldCenter())
                .rotate(obb.getWorldOrientation());
    }

    private boolean overlapOnAxis(BoneHitboxComponent obb1, BoneHitboxComponent obb2,
                                  Vector3f axis) {
        float projection1 = getProjectionRadius(obb1, axis);
        float projection2 = getProjectionRadius(obb2, axis);
        float centerDistance = Math.abs(
                obb2.getWorldCenter().sub(obb1.getWorldCenter()).dot(axis)
        );
        return centerDistance <= projection1 + projection2;
    }

    private float getOverlapOnAxis(BoneHitboxComponent obb1, BoneHitboxComponent obb2,
                                   Vector3f axis) {
        float projection1 = getProjectionRadius(obb1, axis);
        float projection2 = getProjectionRadius(obb2, axis);
        float centerDistance = Math.abs(
                obb2.getWorldCenter().sub(obb1.getWorldCenter()).dot(axis)
        );
        return projection1 + projection2 - centerDistance;
    }

    private float getProjectionRadius(BoneHitboxComponent obb, Vector3f axis) {
        Vector3f[] axes = getObbAxes(obb);
        Vector3f halfExtents = obb.getHalfExtents();

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