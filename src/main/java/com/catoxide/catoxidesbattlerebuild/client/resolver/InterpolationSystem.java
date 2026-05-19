package com.catoxide.catoxidesbattlerebuild.client.resolver;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class InterpolationSystem {

    private static final float DEFAULT_INTERPOLATION_SPEED = 0.1f;

    public static Vec3 interpolate(Vec3 start, Vec3 end, float alpha) {
        float clampedAlpha = Math.max(0.0f, Math.min(1.0f, alpha));
        
        double x = start.x + (end.x - start.x) * clampedAlpha;
        double y = start.y + (end.y - start.y) * clampedAlpha;
        double z = start.z + (end.z - start.z) * clampedAlpha;
        
        return new Vec3(x, y, z);
    }

    public static Quaternionf interpolate(Quaternionf start, Quaternionf end, float alpha) {
        float clampedAlpha = Math.max(0.0f, Math.min(1.0f, alpha));
        
        Quaternionf result = new Quaternionf();
        start.slerp(end, clampedAlpha, result);
        
        LogManager.clientDebug("InterpolationSystem", "Interpolated quaternion: alpha={}", clampedAlpha);
        
        return result;
    }

    public static Matrix4f interpolate(Matrix4f start, Matrix4f end, float alpha) {
        Vector3f startScale = new Vector3f();
        Vector3f endScale = new Vector3f();
        Vector3f startTranslation = new Vector3f();
        Vector3f endTranslation = new Vector3f();
        Quaternionf startRotation = new Quaternionf();
        Quaternionf endRotation = new Quaternionf();
        
        decomposeMatrix(start, startTranslation, startRotation, startScale);
        decomposeMatrix(end, endTranslation, endRotation, endScale);
        
        Vector3f interpolatedTranslation = new Vector3f(
            interpolateComponent(startTranslation.x(), endTranslation.x(), alpha),
            interpolateComponent(startTranslation.y(), endTranslation.y(), alpha),
            interpolateComponent(startTranslation.z(), endTranslation.z(), alpha)
        );
        
        Quaternionf interpolatedRotation = interpolate(startRotation, endRotation, alpha);
        
        Vector3f interpolatedScale = new Vector3f(
            interpolateComponent(startScale.x(), endScale.x(), alpha),
            interpolateComponent(startScale.y(), endScale.y(), alpha),
            interpolateComponent(startScale.z(), endScale.z(), alpha)
        );
        
        Matrix4f result = new Matrix4f();
        result.translation(interpolatedTranslation);
        result.rotate(interpolatedRotation);
        result.scale(interpolatedScale);
        
        return result;
    }

    private static float interpolateComponent(float start, float end, float alpha) {
        return start + (end - start) * alpha;
    }

    private static void decomposeMatrix(Matrix4f matrix, Vector3f translation, 
            Quaternionf rotation, Vector3f scale) {
        translation.set(matrix.m30(), matrix.m31(), matrix.m32());
        
        float sx = (float) Math.sqrt(matrix.m00() * matrix.m00() + matrix.m01() * matrix.m01() + matrix.m02() * matrix.m02());
        float sy = (float) Math.sqrt(matrix.m10() * matrix.m10() + matrix.m11() * matrix.m11() + matrix.m12() * matrix.m12());
        float sz = (float) Math.sqrt(matrix.m20() * matrix.m20() + matrix.m21() * matrix.m21() + matrix.m22() * matrix.m22());
        
        scale.set(sx, sy, sz);
        
        Matrix4f rotationMatrix = new Matrix4f(matrix);
        rotationMatrix.m00(matrix.m00() / sx);
        rotationMatrix.m01(matrix.m01() / sx);
        rotationMatrix.m02(matrix.m02() / sx);
        rotationMatrix.m10(matrix.m10() / sy);
        rotationMatrix.m11(matrix.m11() / sy);
        rotationMatrix.m12(matrix.m12() / sy);
        rotationMatrix.m20(matrix.m20() / sz);
        rotationMatrix.m21(matrix.m21() / sz);
        rotationMatrix.m22(matrix.m22() / sz);
        
        rotation.setFromUnnormalized(rotationMatrix);
    }

    public static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0.0f, Math.min(1.0f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3.0f - 2.0f * t);
    }

    public static float easeOutCubic(float t) {
        float f = 1.0f - t;
        return 1.0f - f * f * f;
    }

    public static float easeInOutCubic(float t) {
        return t < 0.5f ? 4.0f * t * t * t : 1.0f - (float)Math.pow(-2.0f * t + 2.0f, 3.0f) / 2.0f;
    }
}