package com.catoxide.catoxidesbattlerebuild.mob.server;

import com.catoxide.catoxidesbattlerebuild.mob.BoneTransform;
import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ServerGeoModelLoader {
    private final ModularZombie entity;
    private final GeoModel<ModularZombie> model;

    public ServerGeoModelLoader(ModularZombie entity, GeoModel<ModularZombie> model) {
        this.entity = entity;
        this.model = model;
    }

    public BoneTransform getBoneWorldTransform(String boneName) {
        try {
            // 创建动画状态
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

            // 获取骨骼的世界变换数据 (Vector3d)
            Vector3d animatedPosition = bone.getWorldPosition();
            Vector3d animatedRotation = bone.getRotationVector();
            Vector3d animatedScale = bone.getScaleVector();

            // 转换为 Vector3f
            Vector3f position = new Vector3f(
                    (float) animatedPosition.x,
                    (float) animatedPosition.y,
                    (float) animatedPosition.z
            );

            // 将欧拉角转换为四元数
            Quaternionf rotation = new Quaternionf()
                    .rotateZ((float) Math.toRadians(animatedRotation.z))
                    .rotateY((float) Math.toRadians(animatedRotation.y))
                    .rotateX((float) Math.toRadians(animatedRotation.x));

            Vector3f scale = new Vector3f(
                    (float) animatedScale.x,
                    (float) animatedScale.y,
                    (float) animatedScale.z
            );

            // 调试输出
            System.out.println("骨骼 " + boneName + " 变换信息:");
            System.out.println("  世界位置 (Vector3d): " + animatedPosition);
            System.out.println("  转换后位置 (Vector3f): " + position);
            System.out.println("  旋转欧拉角: " + animatedRotation);
            System.out.println("  缩放: " + animatedScale);

            // 转换为世界坐标：加上实体位置
            Vec3 entityPos = entity.position();
            Vec3 worldPos = new Vec3(position.x, position.y, position.z);

            System.out.println("实体位置: " + entityPos);
            System.out.println("最终世界位置: " + worldPos);

            return new BoneTransform(worldPos, rotation, scale);

        } catch (Exception e) {
            System.err.println("计算骨骼变换失败: " + e.getMessage());
            e.printStackTrace();
            return new BoneTransform(entity.position());
        }
    }

    private Matrix4f calculateWorldTransform(GeoBone bone) {
        // 收集从根骨骼到当前骨骼的路径
        List<GeoBone> bonePath = new ArrayList<>();
        GeoBone current = bone;
        while (current != null) {
            bonePath.add(0, current); // 插入到开头，保持从根到子的顺序
            current = current.getParent();
        }

        Matrix4f transform = new Matrix4f().identity();
        // 按正确顺序应用变换：从根骨骼到当前骨骼
        for (GeoBone pathBone : bonePath) {
            Matrix4f localTransform = getLocalTransform(pathBone);
            transform = transform.mul(localTransform); // 注意乘法顺序
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