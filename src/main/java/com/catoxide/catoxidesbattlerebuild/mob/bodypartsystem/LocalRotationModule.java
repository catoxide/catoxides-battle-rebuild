// RotationModule.java - 旋转变换模块
package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

public class LocalRotationModule extends TransformModule {
    public LocalRotationModule() {
        super("LocalRotation");
    }

    @Override
    public Vec3 process(TransformContext context) {
        if (!enabled) return context.currentPosition;

        // 应用骨骼的局部动画旋转（围绕枢轴点）
        Vector3f pos = new Vector3f(
                (float) context.currentPosition.x,
                (float) context.currentPosition.y,
                (float) context.currentPosition.z
        );

        Vector3f rotated = context.boneTransform.rotation.transform(pos);
        return new Vec3(rotated.x, rotated.y, rotated.z);
    }
}