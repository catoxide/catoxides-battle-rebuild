// PivotModule.java - 枢轴点变换模块
package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

// 修复 PivotModule.java
public class PivotModule extends TransformModule {
    public PivotModule() {
        super("Pivot");
    }

    @Override
    public Vec3 process(TransformContext context) {
        if (!enabled) return context.currentPosition;

        // 纯粹的枢轴点变换：将坐标系统转换到以枢轴点为原点
        float pivotX = context.pivot[0];
        float pivotY = context.pivot[1];
        float pivotZ = context.pivot[2];

        return new Vec3(
                context.currentPosition.x - pivotX,
                context.currentPosition.y - pivotY,
                context.currentPosition.z - pivotZ
        );
    }
}