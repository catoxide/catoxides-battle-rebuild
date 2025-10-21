package com.catoxide.catoxidesbattlerebuild.mob.server;

import com.catoxide.catoxidesbattlerebuild.mob.BoneTransform;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;


public class BoneTransformCalculator {
    public static BoneTransform getWorldTransform(GeoModel model, String boneName, Object entity) {
        // 使用正确的类型转换
        GeoBone bone = (GeoBone) model.getBone(boneName).orElse(null);
        if (bone == null) return BoneTransform.IDENTITY;

        // 使用正确的 GeckoLib 4 API
        Vector3f worldPosition = getBoneWorldPosition(bone);
        Quaternionf worldRotation = getBoneWorldRotation(bone);
        Vector3f worldScale = getBoneWorldScale(bone);

        // 转换为你的 BoneTransform 对象
        return new BoneTransform(
                new Vec3(worldPosition.x, worldPosition.y, worldPosition.z),
                worldRotation,
                worldScale
        );
    }

    private static Vector3f getBoneWorldPosition(GeoBone bone) {
        return new Vector3f(
                (float)bone.getPosX(),
                (float)bone.getPosY(),
                (float)bone.getPosZ()
        );
    }

    private static Quaternionf getBoneWorldRotation(GeoBone bone) {
        float rotX = (float)Math.toRadians(bone.getRotX());
        float rotY = (float)Math.toRadians(bone.getRotY());
        float rotZ = (float)Math.toRadians(bone.getRotZ());

        Quaternionf rotation = new Quaternionf();
        rotation.rotationXYZ(rotX, rotY, rotZ);
        return rotation;
    }

    private static Vector3f getBoneWorldScale(GeoBone bone) {
        return new Vector3f(
                (float)bone.getScaleX(),
                (float)bone.getScaleY(),
                (float)bone.getScaleZ()
        );
    }
}