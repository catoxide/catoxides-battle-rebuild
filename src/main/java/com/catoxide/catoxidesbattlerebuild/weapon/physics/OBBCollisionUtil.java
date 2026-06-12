package com.catoxide.catoxidesbattlerebuild.weapon.physics;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * OBB (Oriented Bounding Box) 碰撞检测工具类
 * 使用 Separating Axis Theorem (SAT) 算法进行碰撞检测
 */
public class OBBCollisionUtil {

    /**
     * OBB 包围盒
     */
    public record OBB(Vec3 center, Vec3[] axes, Vec3[] halfExtents) {
        /**
         * 从变换后的顶点创建 OBB
         */
        public static OBB fromVertices(List<Vector3f> vertices) {
            if (vertices == null || vertices.size() < 8) {
                return null;
            }

            // 计算中心点
            float cx = 0, cy = 0, cz = 0;
            for (Vector3f v : vertices) {
                cx += v.x;
                cy += v.y;
                cz += v.z;
            }
            cx /= vertices.size();
            cy /= vertices.size();
            cz /= vertices.size();
            Vec3 center = new Vec3(cx, cy, cz);

            // 计算局部坐标轴（使用前三个不共线的顶点估算）
            Vector3f v0 = vertices.get(0);
            Vector3f v1 = vertices.get(1);
            Vector3f v2 = vertices.get(2);

            Vec3 u = new Vec3(v1.x - v0.x, v1.y - v0.y, v1.z - v0.z).normalize();
            Vec3 w = new Vec3(v2.x - v0.x, v2.y - v0.y, v2.z - v0.z).normalize();
            Vec3 v = u.cross(w).normalize();

            Vec3[] axes = new Vec3[]{u, v, w};

            // 计算半长
            float[] minProj = new float[]{Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
            float[] maxProj = new float[]{-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};

            for (Vector3f vert : vertices) {
                Vec3 p = new Vec3(vert.x - cx, vert.y - cy, vert.z - cz);
                for (int i = 0; i < 3; i++) {
                    float proj = (float) p.dot(axes[i]);
                    minProj[i] = Math.min(minProj[i], proj);
                    maxProj[i] = Math.max(maxProj[i], proj);
                }
            }

            Vec3[] halfExtents = new Vec3[]{
                new Vec3(axes[0].x * (maxProj[0] - minProj[0]) / 2, 0, 0),
                new Vec3(0, axes[1].y * (maxProj[1] - minProj[1]) / 2, 0),
                new Vec3(0, 0, axes[2].z * (maxProj[2] - minProj[2]) / 2)
            };

            return new OBB(center, axes, halfExtents);
        }

        /**
         * 获取 OBB 的 8 个角顶点
         */
        public Vec3[] getVertices() {
            Vec3[] verts = new Vec3[8];
            Vec3[] signs = new Vec3[]{
                new Vec3(-1, -1, -1), new Vec3(1, -1, -1), new Vec3(-1, 1, -1), new Vec3(1, 1, -1),
                new Vec3(-1, -1, 1), new Vec3(1, -1, 1), new Vec3(-1, 1, 1), new Vec3(1, 1, 1)
            };

            for (int i = 0; i < 8; i++) {
                double x = center.x, y = center.y, z = center.z;
                for (int j = 0; j < 3; j++) {
                    x += axes[j].x * halfExtents[j].x * signs[i].x;
                    y += axes[j].y * halfExtents[j].y * signs[i].y;
                    z += axes[j].z * halfExtents[j].z * signs[i].z;
                }
                verts[i] = new Vec3(x, y, z);
            }
            return verts;
        }
    }

    /**
     * 检测 OBB 与 AABB 的碰撞
     */
    public static boolean obbIntersectsAABB(OBB obb, AABB aabb) {
        if (obb == null || aabb == null) {
            return false;
        }

        // AABB 的轴
        Vec3 aabbAxes0 = new Vec3(1, 0, 0);
        Vec3 aabbAxes1 = new Vec3(0, 1, 0);
        Vec3 aabbAxes2 = new Vec3(0, 0, 1);

        Vec3[] aabbAxes = new Vec3[]{aabbAxes0, aabbAxes1, aabbAxes2};
        Vec3 aabbCenter = new Vec3(
            (aabb.minX + aabb.maxX) / 2,
            (aabb.minY + aabb.maxY) / 2,
            (aabb.minZ + aabb.maxZ) / 2
        );
        Vec3[] aabbHalfExtents = new Vec3[]{
            new Vec3((aabb.maxX - aabb.minX) / 2, 0, 0),
            new Vec3(0, (aabb.maxY - aabb.minY) / 2, 0),
            new Vec3(0, 0, (aabb.maxZ - aabb.minZ) / 2)
        };

        // 所有可能的分离轴
        Vec3[] axes = new Vec3[15];
        int idx = 0;

        // OBB 轴
        for (int i = 0; i < 3; i++) {
            axes[idx++] = obb.axes[i];
        }

        // AABB 轴
        for (int i = 0; i < 3; i++) {
            axes[idx++] = aabbAxes[i];
        }

        // 叉积轴
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                Vec3 cross = obb.axes[i].cross(aabbAxes[j]);
                if (cross.lengthSqr() > 1e-10) {
                    axes[idx++] = cross.normalize();
                }
            }
        }

        // SAT 检测
        for (Vec3 axis : axes) {
            if (!axisOnAxisOverlap(obb, aabb, aabbCenter, aabbHalfExtents, axis)) {
                return false; // 分离成功，无碰撞
            }
        }

        return true; // 所有轴都有重叠，有碰撞
    }

    /**
     * 检测 OBB 与 OBB 的碰撞
     */
    public static boolean obbIntersectsOBB(OBB obb1, OBB obb2) {
        if (obb1 == null || obb2 == null) {
            return false;
        }

        // 所有可能的分离轴：15个轴
        Vec3[] axes = new Vec3[15];
        int idx = 0;

        // OBB1 轴
        for (int i = 0; i < 3; i++) {
            axes[idx++] = obb1.axes[i];
        }

        // OBB2 轴
        for (int i = 0; i < 3; i++) {
            axes[idx++] = obb2.axes[i];
        }

        // 叉积轴
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                Vec3 cross = obb1.axes[i].cross(obb2.axes[j]);
                if (cross.lengthSqr() > 1e-10) {
                    axes[idx++] = cross.normalize();
                }
            }
        }

        // SAT 检测
        for (Vec3 axis : axes) {
            if (!obb1OnAxisOverlap(obb1, obb2, axis)) {
                return false;
            }
        }

        return true;
    }

    /**
     * 检测点是否在 AABB 内
     */
    public static boolean pointInAABB(Vec3 point, AABB aabb) {
        return point.x >= aabb.minX && point.x <= aabb.maxX &&
               point.y >= aabb.minY && point.y <= aabb.maxY &&
               point.z >= aabb.minZ && point.z <= aabb.maxZ;
    }

    /**
     * 检测点是否在 OBB 内
     */
    public static boolean pointInOBB(Vec3 point, OBB obb) {
        if (obb == null) return false;

        Vec3 d = point.subtract(obb.center);
        for (int i = 0; i < 3; i++) {
            double proj = d.dot(obb.axes[i]);
            double halfExtentLen = obb.halfExtents[i].length();
            if (Math.abs(proj) > halfExtentLen) {
                return false;
            }
        }
        return true;
    }

    /**
     * OBB 在轴上的投影
     */
    private static Vec3 getOBBProjection(OBB obb, Vec3 axis) {
        Vec3[] verts = obb.getVertices();
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;

        for (Vec3 v : verts) {
            double proj = v.dot(axis);
            min = Math.min(min, proj);
            max = Math.max(max, proj);
        }

        return new Vec3(min, max, 0);
    }

    /**
     * AABB 在轴上的投影
     */
    private static Vec3 getAABBProjection(AABB aabb, Vec3 axis) {
        Vec3[] verts = new Vec3[]{
            new Vec3(aabb.minX, aabb.minY, aabb.minZ),
            new Vec3(aabb.maxX, aabb.minY, aabb.minZ),
            new Vec3(aabb.minX, aabb.maxY, aabb.minZ),
            new Vec3(aabb.maxX, aabb.maxY, aabb.minZ),
            new Vec3(aabb.minX, aabb.minY, aabb.maxZ),
            new Vec3(aabb.maxX, aabb.minY, aabb.maxZ),
            new Vec3(aabb.minX, aabb.maxY, aabb.maxZ),
            new Vec3(aabb.maxX, aabb.maxY, aabb.maxZ)
        };

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;

        for (Vec3 v : verts) {
            double proj = v.dot(axis);
            min = Math.min(min, proj);
            max = Math.max(max, proj);
        }

        return new Vec3(min, max, 0);
    }

    /**
     * OBB1 在轴上的投影
     */
    private static Vec3 getOBBProjection(OBB obb1, OBB obb2, Vec3 axis) {
        return getOBBProjection(obb1, axis);
    }

    /**
     * OBB2 在轴上的投影
     */
    private static Vec3 getOBBProjection2(OBB obb1, OBB obb2, Vec3 axis) {
        return getOBBProjection(obb2, axis);
    }

    private static boolean obb1OnAxisOverlap(OBB obb1, OBB obb2, Vec3 axis) {
        Vec3 proj1 = getOBBProjection(obb1, axis);
        Vec3 proj2 = getOBBProjection(obb2, axis);
        return proj1.x <= proj2.y && proj2.x <= proj1.y;
    }

    private static boolean axisOnAxisOverlap(OBB obb, AABB aabb, Vec3 aabbCenter, Vec3[] aabbHalfExtents, Vec3 axis) {
        Vec3 obbProj = getOBBProjection(obb, axis);

        // AABB 投影
        double aabbProjCenter = aabbCenter.dot(axis);
        double aabbProjHalf = 0;
        for (int i = 0; i < 3; i++) {
            aabbProjHalf += Math.abs(aabbHalfExtents[i].dot(axis));
        }

        double aabbMin = aabbProjCenter - aabbProjHalf;
        double aabbMax = aabbProjCenter + aabbProjHalf;

        return obbProj.x <= aabbMax && aabbMin <= obbProj.y;
    }
}
