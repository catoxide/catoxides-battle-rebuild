package com.catoxide.catoxidesbattlerebuild.core.physics;

/**
 * 箭矢轨迹物理参数配置。
 * <p>定义箭矢飞行的重力、空气阻力、基础速度和是否启用目标预测。</p>
 * <p>所有值均可通过配置文件或游戏内命令动态修改。</p>
 */
public class ArrowTrajectory {

    /** 重力加速度 (单位: 格/刻²)，默认 0.05 */
    public final float gravity;

    /** 空气阻力系数 (每刻速度乘以该值)，默认 0.99 */
    public final float airResistance;

    /** 箭矢基础发射速度，默认 1.6 格/刻 */
    public final float baseSpeed;

    /** 是否启用目标预测（根据目标移动轨迹预测落点），默认 false */
    public final boolean enableTargetPrediction;

    /** 目标预测的模拟刻数，默认 60 (3秒) */
    public final int predictionTicks;

    /** 默认实例：使用原版近似参数 */
    public static final ArrowTrajectory DEFAULT = new ArrowTrajectory(0.05f, 0.99f, 1.6f, false, 60);

    private ArrowTrajectory(float gravity, float airResistance, float baseSpeed, boolean enableTargetPrediction, int predictionTicks) {
        this.gravity = gravity;
        this.airResistance = airResistance;
        this.baseSpeed = baseSpeed;
        this.enableTargetPrediction = enableTargetPrediction;
        this.predictionTicks = predictionTicks;
    }

    /**
     * 创建自定义轨迹配置。
     *
     * @param gravity 重力加速度
     * @param airResistance 空气阻力系数
     * @param baseSpeed 基础速度
     * @param enableTargetPrediction 是否启用目标预测
     * @param predictionTicks 预测模拟刻数
     * @return 新的 ArrowTrajectory 实例
     */
    public static ArrowTrajectory of(float gravity, float airResistance, float baseSpeed, boolean enableTargetPrediction, int predictionTicks) {
        return new ArrowTrajectory(gravity, airResistance, baseSpeed, enableTargetPrediction, predictionTicks);
    }
}
