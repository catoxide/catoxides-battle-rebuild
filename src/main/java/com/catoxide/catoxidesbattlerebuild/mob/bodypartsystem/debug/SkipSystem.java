// SkipSystem.java - 增强版
package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem.debug;

import com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem.BoneTransform;
import com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem.Vertex;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * 增强版跳过系统 - 集成所有变换跳过逻辑
 */
public class SkipSystem {
    // 跳过标志位
    private static final int SKIP_POSITION = 1;
    private static final int SKIP_ROTATION = 2;
    private static final int SKIP_SCALE = 4;
    private static final int SKIP_PIVOT = 8;
    private static final int SKIP_ANIMATION = 16;

    private int skipFlags = 0;
    private boolean enabled = false;

    // === 核心跳过逻辑 ===

    /**
     * 应用跳过系统到骨骼变换
     */
    public BoneTransform applyToBoneTransform(BoneTransform originalTransform) {
        if (!enabled || !shouldSkip(SKIP_ANIMATION)) {
            return originalTransform;
        }
        return new BoneTransform(Vec3.ZERO, new Quaternionf(), new Vector3f(1, 1, 1));
    }

    /**
     * 应用跳过系统到顶点变换
     */
    public Vec3 applyToVertex(Vertex vertex, BoneTransform transform, float[] pivot) {
        if (!enabled) {
            return transformVertexStandard(vertex, transform, pivot);
        }
        return transformVertexWithSkips(vertex, transform, pivot);
    }

    /**
     * 带跳过的顶点变换
     */
    private Vec3 transformVertexWithSkips(Vertex vertex, BoneTransform transform, float[] pivot) {
        float scaleFactor = 1.0f / 16.0f;
        Vector3f pos = new Vector3f(vertex.x * scaleFactor, vertex.y * scaleFactor, vertex.z * scaleFactor);

        // 应用跳过逻辑
        if (!shouldSkip(SKIP_PIVOT)) {
            pos = new Vector3f(
                    (vertex.x - pivot[0]) * scaleFactor,
                    (vertex.y - pivot[1]) * scaleFactor,
                    (vertex.z - pivot[2]) * scaleFactor
            );
        }

        if (!shouldSkip(SKIP_ROTATION)) {
            pos = transform.rotation.transform(pos);
        }
        if (!shouldSkip(SKIP_SCALE)) {
            pos.mul(transform.scale);
        }

        double x = pos.x, y = pos.y, z = pos.z;

        if (!shouldSkip(SKIP_POSITION)) {
            x += transform.position.x;
            y += transform.position.y;
            z += transform.position.z;
        }

        if (!shouldSkip(SKIP_PIVOT)) {
            x += pivot[0] * scaleFactor;
            y += pivot[1] * scaleFactor;
            z += pivot[2] * scaleFactor;
        }

        return new Vec3(x, y, z);
    }

    /**
     * 标准顶点变换（无跳过）
     */
    private Vec3 transformVertexStandard(Vertex vertex, BoneTransform transform, float[] pivot) {
        float scaleFactor = 1.0f / 16.0f;
        Vector3f standardPos = new Vector3f(
                (vertex.x - pivot[0]) * scaleFactor,
                (vertex.y - pivot[1]) * scaleFactor,
                (vertex.z - pivot[2]) * scaleFactor
        );

        Vector3f rotatedPos = transform.rotation.transform(standardPos);
        rotatedPos.mul(transform.scale);

        return new Vec3(
                transform.position.x + rotatedPos.x + pivot[0] * scaleFactor,
                transform.position.y + rotatedPos.y + pivot[1] * scaleFactor,
                transform.position.z + rotatedPos.z + pivot[2] * scaleFactor
        );
    }

    // === 便捷变换方法 ===

    /**
     * 快速顶点变换（带跳过检查）
     */
    public Vec3 quickTransform(Vertex vertex, BoneTransform transform, float[] pivot) {
        return applyToVertex(vertex, transform, pivot);
    }

    /**
     * 快速骨骼变换（带跳过检查）
     */
    public BoneTransform quickTransform(BoneTransform transform) {
        return applyToBoneTransform(transform);
    }

    // === 原有控制方法保持不变 ===
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }
    public void setSkip(int flag, boolean skip) {
        if (skip) skipFlags |= flag; else skipFlags &= ~flag;
    }
    public boolean shouldSkip(int flag) { return enabled && (skipFlags & flag) != 0; }

    // 便捷方法
    public void skipPosition(boolean skip) { setSkip(SKIP_POSITION, skip); }
    public void skipRotation(boolean skip) { setSkip(SKIP_ROTATION, skip); }
    public void skipScale(boolean skip) { setSkip(SKIP_SCALE, skip); }
    public void skipPivot(boolean skip) { setSkip(SKIP_PIVOT, skip); }
    public void skipAnimation(boolean skip) { setSkip(SKIP_ANIMATION, skip); }
    public void reset() { skipFlags = 0; }

    public String getDebugInfo() {
        if (!enabled) {
            return "跳过系统: 禁用";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("跳过系统: 启用 - ");
        List<String> skipped = new ArrayList<>();

        if (shouldSkip(SKIP_POSITION)) skipped.add("位置");
        if (shouldSkip(SKIP_ROTATION)) skipped.add("旋转");
        if (shouldSkip(SKIP_SCALE)) skipped.add("缩放");
        if (shouldSkip(SKIP_PIVOT)) skipped.add("枢轴点");
        if (shouldSkip(SKIP_ANIMATION)) skipped.add("动画");

        if (skipped.isEmpty()) {
            sb.append("无跳过");
        } else {
            sb.append("跳过: ").append(String.join(", ", skipped));
        }
        return sb.toString();
    }
}