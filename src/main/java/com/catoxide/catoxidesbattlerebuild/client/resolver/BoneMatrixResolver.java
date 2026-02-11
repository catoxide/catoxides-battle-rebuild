package com.catoxide.catoxidesbattlerebuild.client.resolver;

import com.catoxide.catoxidesbattlerebuild.client.geometry.ClientBoneCollection;
import com.catoxide.catoxidesbattlerebuild.client.geometry.ClientEntityCollection;
import com.catoxide.catoxidesbattlerebuild.client.models.ClientBoneModelData;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 客户端骨骼矩阵解算器
 * 负责计算骨骼的世界变换矩阵
 */
public class BoneMatrixResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(BoneMatrixResolver.class);
    
    /**
     * 解算实体所有骨骼的世界变换矩阵
     */
    public static void resolveEntityBones(ClientEntityCollection entityCollection, 
            Map<String, ClientBoneCollection> boneCollections, ClientBoneModelData boneModelData) {
        LOGGER.info("[BoneMatrixResolver] Resolving bones for entity: uuid={}", entityCollection.entityUuid());
        
        // 获取根骨骼
        List<String> rootBones = boneModelData.getRootBones();
        
        // 从根骨骼开始递归解算
        for (String rootBone : rootBones) {
            resolveBoneHierarchy(rootBone, boneCollections, boneModelData, null, entityCollection.modelPosition(), entityCollection);
        }
        
        LOGGER.debug("[BoneMatrixResolver] Resolved {} root bones for entity: {}", 
                rootBones.size(), entityCollection.entityUuid());
    }
    
    /**
     * 递归解算骨骼层级
     */
    private static void resolveBoneHierarchy(String boneName, 
            Map<String, ClientBoneCollection> boneCollections, 
            ClientBoneModelData boneModelData,
            Matrix4f parentTransform,
            Vec3 modelPosition,
            ClientEntityCollection entityCollection) {
        
        ClientBoneCollection bone = boneCollections.get(boneName);
        if (bone == null) {
            LOGGER.warn("[BoneMatrixResolver] Bone not found: boneName={}", boneName);
            return;
        }
        
        // 计算本地变换矩阵
        Matrix4f localTransform = calculateLocalTransform(bone);
        
        // 计算世界变换矩阵
        Matrix4f worldTransform;
        if (parentTransform != null) {
            worldTransform = new Matrix4f(parentTransform).mul(localTransform);
        } else {
            // 根骨骼需要应用模型位置
            Matrix4f modelTranslation = new Matrix4f().identity()
                .translate((float)modelPosition.x, (float)modelPosition.y, (float)modelPosition.z);
            worldTransform = new Matrix4f(modelTranslation).mul(localTransform);
        }
        
        // 更新骨骼的世界变换
        bone.updateWorldTransform(worldTransform);
        
        // 更新实体集合中的骨骼变换数据
        entityCollection.dynamicData().setBoneTransform(boneName, worldTransform);
        
        LOGGER.debug("[BoneMatrixResolver] Resolved bone: name={}, hasParent={}", 
                boneName, parentTransform != null);
        
        // 递归处理子骨骼
        List<String> childBones = boneModelData.getChildBones(boneName);
        for (String childBone : childBones) {
            resolveBoneHierarchy(childBone, boneCollections, boneModelData, worldTransform, modelPosition, entityCollection);
        }
    }
    
    /**
     * 计算骨骼的本地变换矩阵
     */
    private static Matrix4f calculateLocalTransform(ClientBoneCollection bone) {
        Matrix4f transform = new Matrix4f().identity();
        
        // 应用枢轴点
        Vec3 pivot = bone.getPivot();
        transform.translate((float)pivot.x, (float)pivot.y, (float)pivot.z);
        
        // 应用旋转
        Vec3 rotation = bone.getRotation();
        Quaternionf quaternion = new Quaternionf()
            .rotationXYZ(
                (float)Math.toRadians(rotation.x),
                (float)Math.toRadians(rotation.y),
                (float)Math.toRadians(rotation.z)
            );
        transform.rotate(quaternion);
        
        // 应用缩放
        Vec3 scale = bone.getScale();
        transform.scale((float)scale.x, (float)scale.y, (float)scale.z);
        
        // 应用负枢轴点（恢复到原点）
        transform.translate(-(float)pivot.x, -(float)pivot.y, -(float)pivot.z);
        
        LOGGER.debug("[BoneMatrixResolver] Calculated local transform for bone: name={}, pivot={}, rotation={}, scale={}", 
                bone.getBoneName(), pivot, rotation, scale);
        
        return transform;
    }
    
    /**
     * 计算点在骨骼空间中的位置
     */
    public static Vec3 transformPointToBoneSpace(Vec3 worldPoint, Matrix4f boneTransform) {
        Matrix4f inverseTransform = new Matrix4f(boneTransform).invert();
        Vector3f localPoint = new Vector3f((float)worldPoint.x, (float)worldPoint.y, (float)worldPoint.z);
        org.joml.Vector4f homogeneousPoint = new org.joml.Vector4f(localPoint, 1.0f);
        org.joml.Vector4f localHomogeneous = inverseTransform.transform(homogeneousPoint);
        
        Vec3 result = new Vec3(
            localHomogeneous.x() / localHomogeneous.w(),
            localHomogeneous.y() / localHomogeneous.w(),
            localHomogeneous.z() / localHomogeneous.w()
        );
        
        LOGGER.debug("[BoneMatrixResolver] Transformed point to bone space: worldPoint={}, boneSpacePoint={}", 
                worldPoint, result);
        
        return result;
    }
}


