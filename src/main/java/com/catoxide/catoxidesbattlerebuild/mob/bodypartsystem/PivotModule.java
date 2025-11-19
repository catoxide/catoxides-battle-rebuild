// PivotModule.java - 枢轴点变换模块
package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

public class PivotModule extends TransformModule {
    public PivotModule() {
        super("Pivot");
    }

    @Override
    public Vec3 process(TransformContext context) {
        if (!enabled) return context.currentPosition;

        // 纯粹的枢轴点变换：顶点坐标 - 枢轴点坐标
        return new Vec3(
                context.currentPosition.x - context.pivot[0],
                context.currentPosition.y - context.pivot[1],
                context.currentPosition.z - context.pivot[2]
        );
    }
}