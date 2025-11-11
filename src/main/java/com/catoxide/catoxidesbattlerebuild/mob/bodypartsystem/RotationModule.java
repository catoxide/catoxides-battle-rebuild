// RotationModule.java - 旋转变换模块
package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

public class RotationModule extends TransformModule {
    public RotationModule() {
        super("Rotation");
    }

    @Override
    public Vec3 process(TransformContext context) {
        if (!enabled) return context.currentPosition;

        Vector3f pos = new Vector3f(
                (float) context.currentPosition.x,
                (float) context.currentPosition.y,
                (float) context.currentPosition.z
        );

        // 应用骨骼旋转
        Vector3f rotated = context.boneTransform.rotation.transform(pos);

        return new Vec3(rotated.x, rotated.y, rotated.z);
    }
}