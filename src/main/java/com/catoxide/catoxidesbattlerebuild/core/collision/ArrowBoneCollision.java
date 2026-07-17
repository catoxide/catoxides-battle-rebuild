package com.catoxide.catoxidesbattlerebuild.core.collision;

import com.catoxide.catoxidesbattlerebuild.core.anim.AnimatedMob;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyUnit;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.EntityBoneSystem;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.List;

/**
 * 箭矢骨骼碰撞检测工具类。
 * <p>使用射线-球体碰撞（Ray-Sphere Intersection）算法，
 * 将箭矢运动轨迹视为射线，骨骼位置视为球体，
 * 检测箭矢是否击中某个骨骼区域。
 * <p>保留原版箭矢物理行为（嵌入、击退、粒子），仅替换碰撞判定逻辑。
 */
public final class ArrowBoneCollision {

    /** 默认骨骼碰撞半径（方块单位） */
    private static final float DEFAULT_BONE_RADIUS = 0.35f;

    private ArrowBoneCollision() {
    }

    /**
     * 骨骼碰撞结果记录。
     *
     * @param hit         是否命中
     * @param partName    命中的 BodyPart 名称
     * @param unitName    命中的 BodyUnit 名称
     * @param partWorldPos 骨骼世界坐标
     * @param hitPosition 命中点位置
     */
    public record BoneCollisionResult(
        boolean hit,
        String partName,
        String unitName,
        Vec3 partWorldPos,
        Vec3 hitPosition
    ) {
    }

    /**
     * 检测箭矢是否命中目标实体的骨骼。
     * <p>遍历所有骨骼单位，对每个骨骼执行射线-球体碰撞检测。
     * 如果命中，返回 BoneCollisionResult；否则返回未命中的结果。
     *
     * @param arrow  箭矢实体
     * @param target 目标实体
     * @return 骨骼碰撞结果
     * @apiNote 从原始的 checkCollision(arrow, target, entityId, boneSystem) 重构而来
     */
    public static BoneCollisionResult checkCollision(AbstractArrow arrow, LivingEntity target) {
        int entityId = target.getId();
        EntityBoneSystem boneSystem = EntityBoneSystem.getInstance();
        List<BodyUnit> units = boneSystem.getDefaultBodyUnits(entityId);
        if (units.isEmpty()) {
            return null;
        }

        // 箭矢当前位置（插值位置，0.0f = 当前帧）
        Vec3 arrowPos = arrow.getPosition(0.0f);
        // 箭矢运动方向（delta）
        Vec3 delta = arrow.getDeltaMovement();

        // 如果箭矢几乎静止，跳过碰撞检测
        if (delta.x * delta.x + delta.y * delta.y + delta.z * delta.z < 1e-6) {
            return null;
        }

        // 计算射线方向（单位向量）
        Vec3 rayDir = delta.normalize();

        // 对每个骨骼单位执行碰撞检测
        for (BodyUnit unit : units) {
            // 获取骨骼世界坐标
            Vector3f boneWorldPos = getBoneWorldPosition(target, unit.getBoneName());
            if (boneWorldPos == null) {
                continue;
            }

            // 骨骼碰撞半径
            float boneRadius = getBoneRadius(unit);

            // 射线-球体碰撞检测
            Vec3 sphereCenter = new Vec3(boneWorldPos.x(), boneWorldPos.y(), boneWorldPos.z());
            boolean hit = raySphereIntersect(arrowPos, rayDir, sphereCenter, boneRadius);

            if (hit) {
                // 计算命中点
                Vec3 hitPoint = computeHitPoint(arrowPos, rayDir, sphereCenter, boneRadius);
                return new BoneCollisionResult(true, unit.getParentPart().getPartName(), unit.getBoneName(),
                        new Vec3(boneWorldPos.x(), boneWorldPos.y(), boneWorldPos.z()), hitPoint);
            }
        }

        return null;
    }

    /**
     * 从 AnimatedMob 获取骨骼世界位置。
     *
     * @param target   目标实体
     * @param boneName 骨骼名称
     * @return 骨骼世界坐标，如果无法获取则返回 null
     */
    private static Vector3f getBoneWorldPosition(LivingEntity target, String boneName) {
        if (target instanceof AnimatedMob<?> animatedMob) {
            Vector3f pos = animatedMob.getServerBonePosition(boneName);
            if (pos != null) {
                // 返回世界坐标副本（Spark-Core 的 Vector3f 是可变的）
                return new Vector3f(pos.x(), pos.y(), pos.z());
            }
        }

        // 如果不是 AnimatedMob，返回实体中心作为 fallback
        Vec3 entityPos = target.getPosition(0.0f);
        return new Vector3f((float) entityPos.x, (float) entityPos.y, (float) entityPos.z);
    }

    /**
     * 获取骨骼碰撞半径。
     * <p>优先使用 BodyUnit 中配置的 collisionTag 对应的半径，
     * 如果未配置或不是数字则使用默认值。
     *
     * @param unit 骨骼单位
     * @return 碰撞半径
     */
    private static float getBoneRadius(BodyUnit unit) {
        String tag = unit.getCollisionTag();
        if (tag != null && !tag.isEmpty()) {
            try {
                return Float.parseFloat(tag);
            } catch (NumberFormatException e) {
                // 如果标签不是数字，使用默认值
            }
        }
        return DEFAULT_BONE_RADIUS;
    }

    /**
     * 射线-球体碰撞检测（Ray-Sphere Intersection）。
     * <p>使用标准射线-球体相交算法：
     * 给定射线 R(t) = O + t*D 和球体 S(C, r)，
     * 求解 t 使得 |R(t) - C|^2 = r^2。
     *
     * @param origin    射线起点
     * @param direction 射线方向（单位向量）
     * @param center    球心
     * @param radius    球半径
     * @return 如果射线与球体相交则返回 true
     */
    private static boolean raySphereIntersect(Vec3 origin, Vec3 direction, Vec3 center, float radius) {
        // OC = origin - center
        double ocX = origin.x - center.x;
        double ocY = origin.y - center.y;
        double ocZ = origin.z - center.z;

        // a = D·D (direction is normalized, so a ≈ 1.0)
        double a = direction.x * direction.x + direction.y * direction.y + direction.z * direction.z;

        // b = OC·D
        double b = 2.0 * (ocX * direction.x + ocY * direction.y + ocZ * direction.z);

        // c = OC·OC - r^2
        double c = ocX * ocX + ocY * ocY + ocZ * ocZ - (double) radius * radius;

        // discriminant = b^2 - 4ac
        double discriminant = b * b - 4.0 * a * c;

        return discriminant >= 0.0;
    }

    /**
     * 计算射线-球体碰撞的命中点。
     *
     * @param origin    射线起点
     * @param direction 射线方向（单位向量）
     * @param center    球心
     * @param radius    球半径
     * @return 命中点的 Vec3
     */
    private static Vec3 computeHitPoint(Vec3 origin, Vec3 direction, Vec3 center, float radius) {
        double ocX = origin.x - center.x;
        double ocY = origin.y - center.y;
        double ocZ = origin.z - center.z;

        double a = direction.x * direction.x + direction.y * direction.y + direction.z * direction.z;
        double b = 2.0 * (ocX * direction.x + ocY * direction.y + ocZ * direction.z);
        double c = ocX * ocX + ocY * ocY + ocZ * ocZ - (double) radius * radius;

        double discriminant = b * b - 4.0 * a * c;
        if (discriminant < 0.0) {
            // 退化为球心投影
            return new Vec3(center.x, center.y, center.z);
        }

        // 取较小的 t 值（最近的交点）
        double t = (-b - Math.sqrt(discriminant)) / (2.0 * a);

        // t 不能为负（射线前方）
        if (t < 0.0) {
            t = 0.0;
        }

        return origin.add(direction.x * t, direction.y * t, direction.z * t);
    }
}
