// SkipSystem.java
package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem.debug;

import com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem.BoneTransform;
import com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem.Vertex;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量级跳过系统 - 用于调试变换问题
 */
public class SkipSystem {
    // 使用位标志来最小化内存开销
    private static final int SKIP_POSITION = 1;
    private static final int SKIP_ROTATION = 2;
    private static final int SKIP_SCALE = 4;
    private static final int SKIP_PIVOT = 8;
    private static final int SKIP_ANIMATION = 16;

    private int skipFlags = 0; // 所有位都为0表示不跳过任何步骤

    // 启用/禁用整个跳过系统
    private boolean enabled = false;

    public SkipSystem() {}

    /**
     * 启用或禁用跳过系统
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        System.out.println("跳过系统 " + (enabled ? "启用" : "禁用"));
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置跳过标志
     */
    public void setSkip(int flag, boolean skip) {
        if (skip) {
            skipFlags |= flag;
        } else {
            skipFlags &= ~flag;
        }
    }

    /**
     * 检查是否跳过某个步骤
     */
    public boolean shouldSkip(int flag) {
        return enabled && (skipFlags & flag) != 0;
    }

    // 便捷方法
    public void skipPosition(boolean skip) { setSkip(SKIP_POSITION, skip); }
    public void skipRotation(boolean skip) { setSkip(SKIP_ROTATION, skip); }
    public void skipScale(boolean skip) { setSkip(SKIP_SCALE, skip); }
    public void skipPivot(boolean skip) { setSkip(SKIP_PIVOT, skip); }
    public void skipAnimation(boolean skip) { setSkip(SKIP_ANIMATION, skip); }

    /**
     * 应用跳过系统到骨骼变换
     */
    public BoneTransform applySkips(BoneTransform originalTransform) {
        if (!enabled || !shouldSkip(SKIP_ANIMATION)) {
            return originalTransform;
        }

        // 跳过动画：返回单位变换
        return new BoneTransform(
                Vec3.ZERO,
                new Quaternionf(),
                new Vector3f(1, 1, 1)
        );
    }

    /**
     * 在顶点变换中应用跳过
     */
    public Vec3 applySkipsToVertex(Vertex vertex, BoneTransform transform, float[] pivot) {
        if (!enabled) {
            // 系统未启用，执行标准变换
            return transformVertexStandard(vertex, transform, pivot);
        }

        float scaleFactor = 1.0f / 16.0f;

        // 如果跳过枢轴点变换，直接使用原始顶点
        if (shouldSkip(SKIP_PIVOT)) {
            Vector3f pos = new Vector3f(
                    vertex.x * scaleFactor,
                    vertex.y * scaleFactor,
                    vertex.z * scaleFactor
            );

            // 应用变换（考虑跳过）
            if (!shouldSkip(SKIP_ROTATION)) {
                pos = transform.rotation.transform(pos);
            }
            if (!shouldSkip(SKIP_SCALE)) {
                pos.mul(transform.scale);
            }

            double x = pos.x;
            double y = pos.y;
            double z = pos.z;

            if (!shouldSkip(SKIP_POSITION)) {
                x += transform.position.x;
                y += transform.position.y;
                z += transform.position.z;
            }

            return new Vec3(x, y, z);
        }

        // 标准变换流程
        return transformVertexStandard(vertex, transform, pivot);
    }

    /**
     * 标准顶点变换（考虑跳过标志）
     */
    private Vec3 transformVertexStandard(Vertex vertex, BoneTransform transform, float[] pivot) {
        float scaleFactor = 1.0f / 16.0f;

        Vector3f standardPos = new Vector3f(
                (vertex.x - pivot[0]) * scaleFactor,
                (vertex.y - pivot[1]) * scaleFactor,
                (vertex.z - pivot[2]) * scaleFactor
        );

        // 应用变换（考虑跳过）
        Vector3f transformedPos = standardPos;

        if (!shouldSkip(SKIP_ROTATION)) {
            transformedPos = transform.rotation.transform(transformedPos);
        }
        if (!shouldSkip(SKIP_SCALE)) {
            transformedPos.mul(transform.scale);
        }

        double finalX = transformedPos.x;
        double finalY = transformedPos.y;
        double finalZ = transformedPos.z;

        if (!shouldSkip(SKIP_POSITION)) {
            finalX += transform.position.x;
            finalY += transform.position.y;
            finalZ += transform.position.z;
        }

        if (!shouldSkip(SKIP_PIVOT)) {
            finalX += pivot[0] * scaleFactor;
            finalY += pivot[1] * scaleFactor;
            finalZ += pivot[2] * scaleFactor;
        }

        return new Vec3(finalX, finalY, finalZ);
    }

    /**
     * 重置所有跳过设置
     */
    public void reset() {
        skipFlags = 0;
        System.out.println("跳过系统已重置");
    }

    /**
     * 获取调试信息
     */
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