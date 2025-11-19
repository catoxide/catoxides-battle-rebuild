// BoneRotationModule.java - 应用骨骼局部旋转（动画）
package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

public class BoneRotationModule extends TransformModule {
    public BoneRotationModule() {
        super("BoneRotation");
    }

    @Override
    public Vec3 process(TransformContext context) {
        if (!enabled) return context.currentPosition;

        org.joml.Vector3f pos = new org.joml.Vector3f(
                (float) context.currentPosition.x,
                (float) context.currentPosition.y,
                (float) context.currentPosition.z
        );

        // 先应用骨骼局部旋转（动画）
        org.joml.Vector3f rotated = context.transform.rotation.transform(pos);

        // 再应用实体整体旋转
        if (context.hasEntityRotation()) {
            rotated = context.transform.entityRotation.transform(rotated);
        }

        return new Vec3(rotated.x, rotated.y, rotated.z);
    }
}
