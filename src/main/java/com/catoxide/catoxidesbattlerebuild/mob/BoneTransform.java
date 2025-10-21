package com.catoxide.catoxidesbattlerebuild.mob;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Quaternionf;

public class BoneTransform {
    public final Vec3 position;
    public final Quaternionf rotation;
    public final Vector3f scale;

    public BoneTransform(Vec3 position, Quaternionf rotation, Vector3f scale) {
        this.position = position;
        this.rotation = rotation;
        this.scale = scale;
    }

    public BoneTransform(Vec3 position, Quaternionf rotation) {
        this(position, rotation, new Vector3f(1, 1, 1));
    }

    public BoneTransform(Vec3 position) {
        this(position, new Quaternionf(), new Vector3f(1, 1, 1));
    }

    // 添加 IDENTITY 常量
    public static final BoneTransform IDENTITY = new BoneTransform(Vec3.ZERO);
}