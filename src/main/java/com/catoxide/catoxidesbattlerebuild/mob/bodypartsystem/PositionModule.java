// PositionModule.java - 修复后的版本
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
            // 1. 添加回枢轴点位置
            result = result.add(
                    context.pivot[0],
                    context.pivot[1],
                    context.pivot[2]
            );

            // 2. 添加骨骼位置偏移
            result = result.add(context.transform.position);

            // 3. 转换为世界坐标
            result = new Vec3(
                    result.x * SCALE_FACTOR,
                    result.y * SCALE_FACTOR,
                    result.z * SCALE_FACTOR
            );
        }

        return result;
    }
}