// EntityRotationModule.java - 应用实体整体旋转
package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

public class EntityRotationModule extends TransformModule {
    public EntityRotationModule() {
        super("EntityRotation");
    }

    @Override
    public Vec3 process(TransformContext context) {
        if (!enabled) return context.currentPosition;

        // 应用实体整体旋转（生物朝向）
        Vector3f pos = new Vector3f(
                (float) context.currentPosition.x,
                (float) context.currentPosition.y,
                (float) context.currentPosition.z
        );

        Vector3f rotated = context.transform.entityRotation.transform(pos);
        return new Vec3(rotated.x, rotated.y, rotated.z);
    }
}