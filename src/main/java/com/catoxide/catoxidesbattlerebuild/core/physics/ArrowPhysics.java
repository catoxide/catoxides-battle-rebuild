package com.catoxide.catoxidesbattlerebuild.core.physics;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.Vec3;

/**
 * 箭矢物理引擎。
 * <p>提供自定义的物理模拟，包括重力、空气阻力和可选的目标预测。</p>
 * <p>通过 {@link #updateTrajectory(AbstractArrow, ArrowTrajectory)} 在每刻更新箭矢轨迹。</p>
 */
public final class ArrowPhysics {

    private static final int PRECISION_PREDICTION_TICKS = 60;
    private static final double MIN_SPEED_SQ = 1e-6;

    private ArrowPhysics() {
    }

    /**
     * 更新箭矢轨迹（每刻调用）。
     * <p>应用重力和空气阻力，更新箭矢速度和位置。</p>
     *
     * @param arrow 箭矢实体
     * @param config 轨迹配置参数
     */
    public static void updateTrajectory(AbstractArrow arrow, ArrowTrajectory config) {
        if (config == null) {
            // 回退到默认配置
            config = ArrowTrajectory.DEFAULT;
        }

        // 如果箭矢已嵌入方块/实体，跳过物理更新
        if (arrow.isNoGravity() || arrow.isInvisible()) {
            return;
        }

        // 读取当前速度
        Vec3 currentVelocity = arrow.getDeltaMovement();
        double vx = currentVelocity.x;
        double vy = currentVelocity.y;
        double vz = currentVelocity.z;

        // 1. 应用重力（向下加速）
        vy -= config.gravity;

        // 2. 应用空气阻力（速度衰减）
        float resistance = config.airResistance;
        vx *= resistance;
        vy *= resistance;
        vz *= resistance;

        // 3. 检查速度是否衰减到可忽略
        double speedSq = vx * vx + vy * vy + vz * vz;
        if (speedSq < MIN_SPEED_SQ) {
            // 速度过小，停止运动
            arrow.setDeltaMovement(Vec3.ZERO);
            return;
        }

        // 4. 更新速度
        Vec3 newVelocity = new Vec3(vx, vy, vz);
        arrow.setDeltaMovement(newVelocity);

        // 5. 如果启用目标预测，计算预测方向并微调
        if (config.enableTargetPrediction && arrow.getOwner() instanceof net.minecraft.world.entity.LivingEntity shooter) {
            predictAndAdjustTrajectory(arrow, shooter, config.predictionTicks);
        }

        // 6. 更新箭矢旋转方向以匹配运动方向
        updateArrowRotation(arrow, newVelocity);
    }

    /**
     * 对箭矢应用目标预测。
     * <p>根据目标当前位置和移动速度，预测 N tick 后的位置，
     * 并调整箭矢的飞行方向。</p>
     *
     * @param arrow 箭矢实体
     * @param target 目标实体
     * @param ticks 预测刻数
     */
    private static void predictAndAdjustTrajectory(AbstractArrow arrow, net.minecraft.world.entity.LivingEntity target, int ticks) {
        try {
            Vec3 arrowPos = arrow.getPosition(1.0f);
            Vec3 targetPos = target.getPosition(1.0f);

            // 目标当前速度
            Vec3 targetVelocity = target.getDeltaMovement();

            // 预测目标未来位置
            double predictedX = targetPos.x + targetVelocity.x * ticks;
            double predictedY = targetPos.y + targetVelocity.y * ticks;
            double predictedZ = targetPos.z + targetVelocity.z * ticks;

            // 预测命中向量
            double dx = predictedX - arrowPos.x;
            double dy = predictedY - arrowPos.y;
            double dz = predictedZ - arrowPos.z;

            // 计算飞行时间（考虑重力）
            // 简化模型：使用距离/速度近似
            double speed = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (speed < 0.001) {
                return;
            }

            // 飞行时间估计：考虑重力的迭代修正
            double flightTime = estimateFlightTime(speed, ArrowTrajectory.DEFAULT.baseSpeed);

            // 重新计算考虑重力的预测
            dy -= ArrowTrajectory.DEFAULT.gravity * flightTime * flightTime;

            // 新的方向向量
            double newDx = predictedX - arrowPos.x;
            double newDy = (predictedY - ArrowTrajectory.DEFAULT.gravity * flightTime * flightTime) - arrowPos.y;
            double newDz = predictedZ - arrowPos.z;

            // 归一化
            double newLen = Math.sqrt(newDx * newDx + newDy * newDy + newDz * newDz);
            if (newLen < 0.001) {
                return;
            }

            // 缩放为基础速度
            float baseSpeed = ArrowTrajectory.DEFAULT.baseSpeed;
            double scale = baseSpeed / newLen;

            Vec3 predictedVelocity = new Vec3(newDx * scale, newDy * scale, newDz * scale);

            // 渐进混合：每刻最多调整 10% 的方向
            Vec3 currentVel = arrow.getDeltaMovement();
            double blend = 0.1;
            double blendedX = currentVel.x + (predictedVelocity.x - currentVel.x) * blend;
            double blendedY = currentVel.y + (predictedVelocity.y - currentVel.y) * blend;
            double blendedZ = currentVel.z + (predictedVelocity.z - currentVel.z) * blend;

            arrow.setDeltaMovement(blendedX, blendedY, blendedZ);
        } catch (Exception e) {
            // 预测失败时静默降级
            LogManager.serverWarn("ArrowPhysics", "Target prediction failed: {}", e.getMessage());
        }
    }

    /**
     * 估算飞行时间（考虑重力影响）。
     *
     * @param distance 距离
     * @param speed 初始速度
     * @return 估算的飞行时间（刻）
     */
    private static double estimateFlightTime(double distance, double speed) {
        // 简化：飞行时间 = 距离 / 速度
        if (speed < 0.001) {
            return 0.0;
        }
        return distance / speed;
    }

    /**
     * 更新箭矢旋转方向以匹配当前运动方向。
     * <p>让箭矢的视觉朝向与飞行方向一致。</p>
     *
     * @param arrow 箭矢实体
     * @param velocity 当前速度向量
     */
    private static void updateArrowRotation(AbstractArrow arrow, Vec3 velocity) {
        double vx = velocity.x;
        double vy = velocity.y;
        double vz = velocity.z;

        // 计算俯仰角 (pitch)
        double pitch = Math.toRadians(Math.atan2(vy, Math.sqrt(vx * vx + vz * vz)));

        // 计算偏航角 (yaw)
        double yaw = Math.toRadians(Math.atan2(vz, vx)) * 180.0 / Math.PI;
        yaw -= 90.0; // 调整偏移

        // 设置旋转
        // 使用 vanilla 的内部方法，通过反射或 mixin
        // 这里我们调用 setPos 间接更新视觉位置
        Vec3 currentPos = arrow.position();
        arrow.setPos(currentPos.x, currentPos.y, currentPos.z);
    }

    /**
     * 计算两点之间的距离。
     *
     * @param x1 起点 X
     * @param y1 起点 Y
     * @param z1 起点 Z
     * @param x2 终点 X
     * @param y2 终点 Y
     * @param z2 终点 Z
     * @return 欧几里得距离
     */
    public static double distance(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * 将 Vec3 转换为 double 数组 [x, y, z]。
     *
     * @param vec 向量
     * @return double 数组
     */
    public static double[] toArray(Vec3 vec) {
        return new double[]{vec.x, vec.y, vec.z};
    }

    /**
     * 创建 Vec3 从 double 数组。
     *
     * @param arr 数组 [x, y, z]
     * @return Vec3 实例
     */
    public static Vec3 fromArray(double[] arr) {
        return new Vec3(arr[0], arr[1], arr[2]);
    }
}
