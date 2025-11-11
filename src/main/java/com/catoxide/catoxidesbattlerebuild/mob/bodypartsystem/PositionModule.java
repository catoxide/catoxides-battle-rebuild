// PositionModule.java - 位置变换模块
package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import net.minecraft.world.phys.Vec3;

public class PositionModule extends TransformModule {
    private static final float SCALE_FACTOR = 1.0f / 16.0f;

    public PositionModule() {
        super("Position");
    }

    @Override
    public Vec3 process(TransformContext context) {
        Vec3 result = context.currentPosition;

        if (enabled) {
            // 应用骨骼位置
            result = result.add(context.boneTransform.position);
        }

        // 重新添加枢轴点位置（如果需要）
        if (shouldAddPivotBack()) {
            result = result.add(
                    context.pivot[0] * SCALE_FACTOR,
                    context.pivot[1] * SCALE_FACTOR,
                    context.pivot[2] * SCALE_FACTOR
            );
        }

        return result;
    }

    private boolean shouldAddPivotBack() {
        // 这里可以根据需要添加逻辑来决定是否添加枢轴点
        return true;
    }
}