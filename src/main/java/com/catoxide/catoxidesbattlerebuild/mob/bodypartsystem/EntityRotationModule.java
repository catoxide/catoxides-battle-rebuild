package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class EntityRotationModule extends TransformModule {
    public EntityRotationModule() {
        super("EntityRotation");
    }

    @Override
    public Vec3 process(TransformContext context) {
        if (!enabled) return context.currentPosition;

        // 获取生物的整体旋转（yaw/pitch）
        Quaternionf entityRotation = context.boneTransform.rotation;
        if (entityRotation == null) {
            return context.currentPosition;
        }

        // 应用生物整体旋转
        Vector3f pos = new Vector3f(
                (float) context.currentPosition.x,
                (float) context.currentPosition.y,
                (float) context.currentPosition.z
        );

        Vector3f rotated = entityRotation.transform(pos);
        return new Vec3(rotated.x, rotated.y, rotated.z);
    }
}