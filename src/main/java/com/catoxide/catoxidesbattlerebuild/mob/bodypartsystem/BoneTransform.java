package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

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

    public Vec3 applyRotation(Vec3 localOffset) {
        if (rotation == null || isIdentityQuaternion(rotation)) {
            return localOffset;
        }

        // 将 Vec3 转换为 Vector3f
        Vector3f vec = new Vector3f((float)localOffset.x, (float)localOffset.y, (float)localOffset.z);

        // 应用旋转
        Vector3f rotated = rotation.transform(vec);

        // 转换回 Vec3
        return new Vec3(rotated.x, rotated.y, rotated.z);
    }
    // 修复：添加检查四元数是否为单位的辅助方法
    private boolean isIdentityQuaternion(Quaternionf rotation) {
        return rotation.x == 0 && rotation.y == 0 && rotation.z == 0 && rotation.w == 1;
    }


    // 添加方法：获取旋转的欧拉角（用于调试）
    public Vector3f getEulerAngles() {
        if (rotation == null) return new Vector3f();

        // 将四元数转换为欧拉角
        return rotation.getEulerAnglesXYZ(new Vector3f());
    }
//    public void setEntityRotation(float yaw, float pitch, float roll) {
//        this.entityRotation.rotationYXZ(yaw, pitch, roll);
//    }
}