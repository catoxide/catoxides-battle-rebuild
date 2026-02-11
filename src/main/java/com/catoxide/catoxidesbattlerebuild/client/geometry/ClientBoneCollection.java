package com.catoxide.catoxidesbattlerebuild.client.geometry;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端骨骼集合类
 * 存储骨骼的变换和立方体集合
 * 对齐服务端BoneCollection
 */
public class ClientBoneCollection {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientBoneCollection.class);
    
    // 实体ID
    private final long entityId;
    
    // 骨骼名称
    private final String boneName;
    
    // 枢轴点
    private final Vec3 pivot;
    
    // 旋转
    private final Vec3 rotation;
    
    // 缩放
    private final Vec3 scale;
    
    // 世界变换矩阵
    private Matrix4f worldTransform = new Matrix4f().identity();
    
    // 立方体集合
    private final List<ClientCubeCollection> cubeCollections;
    
    // 父骨骼名称
    private final String parentBoneName;
    
    // 子骨骼名称列表
    private final List<String> childBoneNames;

    /**
     * 从静态数据创建ClientBoneCollection
     */
    public static ClientBoneCollection fromStaticData(
            long entityId,
            com.catoxide.catoxidesbattlerebuild.client.models.ClientBoneModelData.BoneStaticData boneStaticData) {
        LOGGER.debug("[ClientBoneCollection] Creating bone from static data: entityId={}, boneName={}", 
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
        
        // 创建立方体集合
        for (com.catoxide.catoxidesbattlerebuild.client.models.ClientBoneModelData.CubeStaticData cubeData : 
                boneStaticData.cubeStaticDataList()) {
            ClientCubeCollection cube = ClientCubeCollection.fromStaticData(entityId, boneStaticData.boneName(), cubeData);
            bone.addCubeCollection(cube);
        }
        
        LOGGER.debug("[ClientBoneCollection] Created bone with {} cubes: boneName={}", 
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
        
        LOGGER.debug("[ClientBoneCollection] Created bone: name={}, parent={}", boneName, parentBoneName);
    }

    /**
     * 添加立方体集合
     */
    public void addCubeCollection(ClientCubeCollection cubeCollection) {
        cubeCollections.add(cubeCollection);
        LOGGER.debug("[ClientBoneCollection] Added cube to bone: boneName={}, cubeId={}", 
                boneName, cubeCollection.getId());
    }

    /**
     * 更新世界变换
     */
    public void updateWorldTransform(Matrix4f transform) {
        this.worldTransform = transform;
        
        // 更新所有立方体的世界变换
        for (ClientCubeCollection cube : cubeCollections) {
            cube.updateWorldTransform(transform);
        }
        
        LOGGER.debug("[ClientBoneCollection] Updated world transform for bone: name={}, cubes={}", 
                boneName, cubeCollections.size());
    }

    /**
     * 检查点是否在骨骼的任意立方体内
     */
    public boolean containsPoint(Vec3 point) {
        for (ClientCubeCollection cube : cubeCollections) {
            if (cube.containsPoint(point)) {
                LOGGER.debug("[ClientBoneCollection] Point found in cube: boneName={}, cubeId={}", 
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
    
    /**
     * 设置子骨骼名称列表
     */
    public void setChildBoneNames(List<String> childBoneNames) {
        this.childBoneNames.clear();
        this.childBoneNames.addAll(childBoneNames);
        LOGGER.debug("[ClientBoneCollection] Set child bones for {}: count={}", boneName, childBoneNames.size());
    }
}
