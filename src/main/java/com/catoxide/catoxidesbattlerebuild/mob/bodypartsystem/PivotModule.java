// PivotModule.java - 枢轴点变换模块
package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

public class PivotModule extends TransformModule {
    private static final float SCALE_FACTOR = 1.0f / 16.0f;

    public PivotModule() {
        super("Pivot");
    }

    @Override
    public Vec3 process(TransformContext context) {
        if (!enabled) return context.currentPosition;

        // 应用枢轴点偏移
        Vector3f pos = new Vector3f(
                (float) (context.currentPosition.x - context.pivot[0]),
                (float) (context.currentPosition.y - context.pivot[1]),
                (float) (context.currentPosition.z - context.pivot[2])
        );

        // 缩放转换
        pos.mul(SCALE_FACTOR);

        return new Vec3(pos.x, pos.y, pos.z);
    }
}