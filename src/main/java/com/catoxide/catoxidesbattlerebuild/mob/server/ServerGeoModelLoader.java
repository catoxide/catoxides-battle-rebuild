package com.catoxide.catoxidesbattlerebuild.mob.server;

import com.catoxide.catoxidesbattlerebuild.mob.BoneTransform;
import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

public class ServerGeoModelLoader {
    private final ModularZombie entity;
    private final GeoModel<ModularZombie> model;

    public ServerGeoModelLoader(ModularZombie entity, GeoModel<ModularZombie> model) {
        this.entity = entity;
        this.model = model;
    }

    public BoneTransform getBoneWorldTransform(String boneName) {
        try {
            // 创建一个简单的动画状态来获取 baked model
            AnimationState<ModularZombie> animationState = new AnimationState<>(entity, 0, 0, 0, false);

            // 获取 BakedGeoModel
            BakedGeoModel bakedModel = model.getBakedModel(model.getModelResource(entity));
            if (bakedModel == null) {
                return new BoneTransform(entity.position());
            }

            // 从 BakedGeoModel 获取骨骼
            GeoBone bone = bakedModel.getBone(boneName).orElse(null);
            if (bone == null) {
                System.err.println("未找到骨骼: " + boneName);
                return new BoneTransform(entity.position());
            }

            // 计算世界变换
            Matrix4f worldTransform = calculateWorldTransform(bone);

            // 提取变换数据
            Vector3f position = new Vector3f();
            Quaternionf rotation = new Quaternionf();
            Vector3f scale = new Vector3f();

            worldTransform.getTranslation(position);
            worldTransform.getUnnormalizedRotation(rotation);
            worldTransform.getScale(scale);

            // 转换为世界坐标
            Vec3 entityPos = entity.position();
            Vec3 worldPos = new Vec3(position.x, position.y, position.z).add(entityPos);

            return new BoneTransform(worldPos, rotation, scale);

        } catch (Exception e) {
            System.err.println("计算骨骼变换失败: " + e.getMessage());
            return new BoneTransform(entity.position());
        }
    }

    private Matrix4f calculateWorldTransform(GeoBone bone) {
        Matrix4f transform = new Matrix4f().identity();

        // 向上遍历骨骼层级
        GeoBone current = bone;
        while (current != null) {
            Matrix4f localTransform = getLocalTransform(current);
            transform = localTransform.mul(transform);
            current = current.getParent();
        }

        return transform;
    }

    private Matrix4f getLocalTransform(GeoBone bone) {
        Matrix4f transform = new Matrix4f().identity();

        // 使用正确的 GeckoLib 4 API 获取骨骼变换
        double posX = bone.getPosX();
        double posY = bone.getPosY();
        double posZ = bone.getPosZ();

        double rotX = bone.getRotX();
        double rotY = bone.getRotY();
        double rotZ = bone.getRotZ();

        double scaleX = bone.getScaleX();
        double scaleY = bone.getScaleY();
        double scaleZ = bone.getScaleZ();

        transform.translate((float)posX, (float)posY, (float)posZ);
        transform.rotateZ((float)Math.toRadians(rotZ));
        transform.rotateY((float)Math.toRadians(rotY));
        transform.rotateX((float)Math.toRadians(rotX));
        transform.scale((float)scaleX, (float)scaleY, (float)scaleZ);

        return transform;
    }
}