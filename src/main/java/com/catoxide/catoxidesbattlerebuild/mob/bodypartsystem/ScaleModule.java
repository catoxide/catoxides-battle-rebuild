// ScaleModule.java - 缩放变换模块
package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

public class ScaleModule extends TransformModule {
    public ScaleModule() {
        super("Scale");
    }

    @Override
    public Vec3 process(TransformContext context) {
        if (!enabled) return context.currentPosition;

        org.joml.Vector3f pos = new org.joml.Vector3f(
                (float) context.currentPosition.x,
                (float) context.currentPosition.y,
                (float) context.currentPosition.z
        );

        pos.mul(context.transform.scale);
        return new Vec3(pos.x, pos.y, pos.z);
    }
}