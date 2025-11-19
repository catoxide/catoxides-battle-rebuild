// PivotModule.java - 枢轴点变换模块
package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

// 修复 PivotModule.java
public class PivotModule extends TransformModule {
    private static final float SCALE_FACTOR = 1.0f / 16.0f;

    public PivotModule() {
        super("Pivot");
    }

    @Override
    public Vec3 process(TransformContext context) {
        if (!enabled) {
            // 如果禁用，直接返回缩放后的原始顶点
            return new Vec3(
                    context.originalVertex.x * SCALE_FACTOR,
                    context.originalVertex.y * SCALE_FACTOR,
                    context.originalVertex.z * SCALE_FACTOR
            );
        }

        // 应用枢轴点变换：顶点相对于枢轴点
        float relativeX = context.originalVertex.x - context.pivot[0];
        float relativeY = context.originalVertex.y - context.pivot[1];
        float relativeZ = context.originalVertex.z - context.pivot[2];

        // 转换为世界坐标单位
        return new Vec3(
                relativeX * SCALE_FACTOR,
                relativeY * SCALE_FACTOR,
                relativeZ * SCALE_FACTOR
        );
    }
}