package com.catoxide.catoxidesbattlerebuild.client.resolver;

import com.catoxide.catoxidesbattlerebuild.client.geometry.ClientBoneCollection;
import com.catoxide.catoxidesbattlerebuild.client.models.ClientBoneModelData;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

public class BoneMatrixResolver {
    
    public static void resolveEntityBones(Map<String, ClientBoneCollection> boneCollections, 
            ClientBoneModelData boneModelData, Vec3 modelPosition) {
        LogManager.clientInfo("BoneMatrixResolver", "Resolving bones for model");
        
        List<String> rootBones = boneModelData.getRootBones();
        
        for (String rootBone : rootBones) {
            resolveBoneHierarchy(rootBone, boneCollections, boneModelData, null, modelPosition);
        }
        
        LogManager.clientDebug("BoneMatrixResolver", "Resolved {} root bones", rootBones.size());
    }
    
    private static void resolveBoneHierarchy(String boneName,
            Map<String, ClientBoneCollection> boneCollections,
            ClientBoneModelData boneModelData,
            Matrix4f parentTransform,
            Vec3 modelPosition) {
        
        ClientBoneCollection bone = boneCollections.get(boneName);
        if (bone == null) {
            LogManager.clientWarn("BoneMatrixResolver", "Bone not found: boneName={}", boneName);
            return;
        }
        
        Matrix4f localTransform = calculateLocalTransform(bone);
        
        Matrix4f worldTransform;
        if (parentTransform != null) {
            worldTransform = new Matrix4f(parentTransform).mul(localTransform);
        } else {
            Matrix4f modelTranslation = new Matrix4f().identity()
                .translate((float)modelPosition.x, (float)modelPosition.y, (float)modelPosition.z);
            worldTransform = new Matrix4f(modelTranslation).mul(localTransform);
        }
        
        bone.updateWorldTransform(worldTransform);
        
        LogManager.clientDebug("BoneMatrixResolver", "Resolved bone: name={}, hasParent={}",
                boneName, parentTransform != null);
        
        List<String> childBones = boneModelData.getChildBones(boneName);
        for (String childBone : childBones) {
            resolveBoneHierarchy(childBone, boneCollections, boneModelData, worldTransform, modelPosition);
        }
    }
    
    private static Matrix4f calculateLocalTransform(ClientBoneCollection bone) {
        Matrix4f transform = new Matrix4f().identity();
        
        Vec3 pivot = bone.getPivot();
        transform.translate((float)pivot.x, (float)pivot.y, (float)pivot.z);
        
        Vec3 rotation = bone.getRotation();
        Quaternionf quaternion = new Quaternionf()
            .rotationXYZ(
                (float)Math.toRadians(rotation.x),
                (float)Math.toRadians(rotation.y),
                (float)Math.toRadians(rotation.z)
            );
        transform.rotate(quaternion);
        
        Vec3 scale = bone.getScale();
        transform.scale((float)scale.x, (float)scale.y, (float)scale.z);
        
        transform.translate(-(float)pivot.x, -(float)pivot.y, -(float)pivot.z);
        
        LogManager.clientDebug("BoneMatrixResolver", "Calculated local transform for bone: name={}, pivot={}, rotation={}, scale={}",
                bone.getBoneName(), pivot, rotation, scale);
        
        return transform;
    }
    
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
        
        LogManager.clientDebug("BoneMatrixResolver", "Transformed point to bone space: worldPoint={}, boneSpacePoint={}",
                worldPoint, result);
        
        return result;
    }
}