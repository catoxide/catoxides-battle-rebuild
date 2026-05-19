package com.catoxide.catoxidesbattlerebuild.client.geometry;

import com.catoxide.catoxidesbattlerebuild.client.models.ClientBoneModelData;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

public class ClientBoneCollection {
    
    private final long entityId;
    private final String boneName;
    private final Vec3 pivot;
    private final Vec3 rotation;
    private final Vec3 scale;
    private Matrix4f worldTransform = new Matrix4f().identity();
    private final List<ClientCubeCollection> cubeCollections;
    private final String parentBoneName;
    private final List<String> childBoneNames;

    public static ClientBoneCollection fromStaticData(
            long entityId,
            ClientBoneModelData.BoneStaticData boneStaticData) {
        LogManager.clientDebug("ClientBoneCollection", "Creating bone from static data: entityId={}, boneName={}",
                entityId, boneStaticData.boneName());
        
        ClientBoneCollection bone = new ClientBoneCollection(
            entityId,
            boneStaticData.boneName(),
            boneStaticData.localPivot(),
            boneStaticData.localRotation(),
            boneStaticData.localScale(),
            boneStaticData.parentBoneName(),
            new ArrayList<>()
        );
        
        for (ClientBoneModelData.CubeStaticData cubeData :
                boneStaticData.cubeStaticDataList()) {
            ClientCubeCollection cube = ClientCubeCollection.fromStaticData(entityId, boneStaticData.boneName(), cubeData);
            bone.addCubeCollection(cube);
        }
        
        LogManager.clientDebug("ClientBoneCollection", "Created bone with {} cubes: boneName={}",
                boneStaticData.cubeStaticDataList().size(), boneStaticData.boneName());
        
        return bone;
    }

    public ClientBoneCollection(long entityId, String boneName, Vec3 pivot, Vec3 rotation,
            Vec3 scale, String parentBoneName, List<String> childBoneNames) {
        this.entityId = entityId;
        this.boneName = boneName;
        this.pivot = pivot != null ? pivot : new Vec3(0, 0, 0);
        this.rotation = rotation != null ? rotation : new Vec3(0, 0, 0);
        this.scale = scale != null ? scale : new Vec3(1, 1, 1);
        this.parentBoneName = parentBoneName;
        this.childBoneNames = childBoneNames != null ? childBoneNames : new ArrayList<>();
        this.cubeCollections = new ArrayList<>();
        
        LogManager.clientDebug("ClientBoneCollection", "Created bone: name={}, parent={}", boneName, parentBoneName);
    }

    public void addCubeCollection(ClientCubeCollection cubeCollection) {
        cubeCollections.add(cubeCollection);
        LogManager.clientDebug("ClientBoneCollection", "Added cube to bone: boneName={}, cubeId={}",
                boneName, cubeCollection.getId());
    }

    public void updateWorldTransform(Matrix4f transform) {
        this.worldTransform = transform;
        
        for (ClientCubeCollection cube : cubeCollections) {
            cube.updateWorldTransform(transform);
        }
        
        LogManager.clientDebug("ClientBoneCollection", "Updated world transform for bone: name={}, cubes={}",
                boneName, cubeCollections.size());
    }

    public boolean containsPoint(Vec3 point) {
        for (ClientCubeCollection cube : cubeCollections) {
            if (cube.containsPoint(point)) {
                LogManager.clientDebug("ClientBoneCollection", "Point found in cube: boneName={}, cubeId={}",
                        boneName, cube.getId());
                return true;
            }
        }
        return false;
    }

    public long getEntityId() { return entityId; }
    public String getBoneName() { return boneName; }
    public Vec3 getPivot() { return pivot; }
    public Vec3 getRotation() { return rotation; }
    public Vec3 getScale() { return scale; }
    public Matrix4f getWorldTransform() { return worldTransform; }
    public List<ClientCubeCollection> getCubeCollections() { return new ArrayList<>(cubeCollections); }
    public String getParentBoneName() { return parentBoneName; }
    public List<String> getChildBoneNames() { return new ArrayList<>(childBoneNames); }
    
    public void setChildBoneNames(List<String> childBoneNames) {
        this.childBoneNames.clear();
        this.childBoneNames.addAll(childBoneNames);
        LogManager.clientDebug("ClientBoneCollection", "Set child bones for {}: count={}", boneName, childBoneNames.size());
    }
}