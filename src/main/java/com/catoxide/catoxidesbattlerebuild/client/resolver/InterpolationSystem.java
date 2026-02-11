package com.catoxide.catoxidesbattlerebuild.client.resolver;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端插值系统
 * 负责平滑过渡骨骼变换矩阵
 */
public class InterpolationSystem {
    private static final Logger LOGGER = LoggerFactory.getLogger(InterpolationSystem.class);
    
    // 插值速度（0.0-1.0，值越大插值越快）
    private static final float INTERPOLATION_SPEED = 0.3f;
    
    /**
     * 插值两个矩阵
     */
    public static Matrix4f interpolateMatrix(Matrix4f from, Matrix4f to, float alpha) {
        if (from == null || to == null) {
            LOGGER.warn("[InterpolationSystem] Cannot interpolate null matrices");
            return to != null ? new Matrix4f(to) : new Matrix4f().identity();
        }
        
        // 提取平移、旋转、缩放
        Vector3f fromTranslation = new Vector3f();
        Vector3f fromScale = new Vector3f();
        Quaternionf fromRotation = new Quaternionf();
        from.getTranslation(fromTranslation);
        from.getScale(fromScale);
        from.getNormalizedRotation(fromRotation);
        
        Vector3f toTranslation = new Vector3f();
        Vector3f toScale = new Vector3f();
        Quaternionf toRotation = new Quaternionf();
        to.getTranslation(toTranslation);
        to.getScale(toScale);
        to.getNormalizedRotation(toRotation);
        
        // 插值
        Vector3f interpolatedTranslation = new Vector3f(fromTranslation).lerp(toTranslation, alpha);
        Vector3f interpolatedScale = new Vector3f(fromScale).lerp(toScale, alpha);
        Quaternionf interpolatedRotation = new Quaternionf(fromRotation).slerp(toRotation, alpha);
        
        // 构建插值后的矩阵
        Matrix4f result = new Matrix4f().identity()
            .translate(interpolatedTranslation)
            .rotate(interpolatedRotation)
            .scale(interpolatedScale);
        
        LOGGER.debug("[InterpolationSystem] Interpolated matrices: alpha={}, from={}, to={}", 
                alpha, from, to);
        
        return result;
    }
    
    /**
     * 使用默认插值速度插值两个矩阵
     */
    public static Matrix4f interpolateMatrix(Matrix4f from, Matrix4f to) {
        return interpolateMatrix(from, to, INTERPOLATION_SPEED);
    }
    
    /**
     * 插值两个向量
     */
    public static Vector3f interpolateVector(Vector3f from, Vector3f to, float alpha) {
        if (from == null || to == null) {
            LOGGER.warn("[InterpolationSystem] Cannot interpolate null vectors");
            return to != null ? new Vector3f(to) : new Vector3f();
        }
        
        Vector3f result = new Vector3f(from).lerp(to, alpha);
        LOGGER.debug("[InterpolationSystem] Interpolated vectors: alpha={}, from={}, to={}", 
                alpha, from, to);
        return result;
    }
    
    /**
     * 使用默认插值速度插值两个向量
     */
    public static Vector3f interpolateVector(Vector3f from, Vector3f to) {
        return interpolateVector(from, to, INTERPOLATION_SPEED);
    }
    
    /**
     * 插值两个四元数
     */
    public static Quaternionf interpolateQuaternion(Quaternionf from, Quaternionf to, float alpha) {
        if (from == null || to == null) {
            LOGGER.warn("[InterpolationSystem] Cannot interpolate null quaternions");
            return to != null ? new Quaternionf(to) : new Quaternionf();
        }
        
        Quaternionf result = new Quaternionf(from).slerp(to, alpha);
        LOGGER.debug("[InterpolationSystem] Interpolated quaternions: alpha={}, from={}, to={}", 
                alpha, from, to);
        return result;
    }
    
    /**
     * 使用默认插值速度插值两个四元数
     */
    public static Quaternionf interpolateQuaternion(Quaternionf from, Quaternionf to) {
        return interpolateQuaternion(from, to, INTERPOLATION_SPEED);
    }
    
    /**
     * 设置插值速度
     */
    public static void setInterpolationSpeed(float speed) {
        LOGGER.info("[InterpolationSystem] Setting interpolation speed: old={}, new={}", 
                INTERPOLATION_SPEED, speed);
        // 注意：这里需要修改为实例变量或使用其他方式存储
        // 当前实现中是静态常量，需要重构为实例变量
    }
    
    /**
     * 获取插值速度
     */
    public static float getInterpolationSpeed() {
        return INTERPOLATION_SPEED;
    }
}
