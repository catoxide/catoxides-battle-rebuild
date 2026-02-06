package com.catoxide.catoxidesbattlerebuild.server.entities;

import com.catoxide.catoxidesbattlerebuild.server.models.BoneModelData;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * 骨骼集合类
 * 代表一个骨骼及其相关的立方体集合，用于处理骨骼变换和碰撞检测
 * 这是一个实体特定的动态数据类，包含位置变换等实时信息
 */
public class BoneCollection {
    // 实体ID，用于标识属于哪个实体
    private final long entityId;
    
    // 骨骼名称
    private final String boneName;
    
    // 骨骼的枢轴点（变换中心）
    private final Vec3 pivot;
    
    // 骨骼的旋转
    private final Vec3 rotation;
    
    // 骨骼的缩放
    private final Vec3 scale;
    
    // 当前骨骼的世界变换矩阵
    private Matrix4f worldTransform = new Matrix4f().identity();
    
    // 属于此骨骼的立方体集合
    private final List<CubeCollection> cubeCollections;
    
    // 父骨骼名称
    private final String parentBoneName;
    
    // 子骨骼名称列表
    private final List<String> childBoneNames;
    
    /**
     * 从静态数据创建BoneCollection
     * 这个方法用于从预加载的静态模型数据创建BoneCollection
     */
    public static BoneCollection fromStaticData(long entityId, String boneName, BoneModelData.BoneStaticData boneStaticData, List<CubeCollection> cubes) {
        return new BoneCollection(
            entityId,
            boneName,
            boneStaticData.localPivot(),
            boneStaticData.localRotation(),
            boneStaticData.localScale(),
            cubes,
            boneStaticData.parentBoneName(),
            new ArrayList<>()
        );
    }
    
    // 构造函数
    public BoneCollection(long entityId, String boneName, Vec3 pivot, Vec3 rotation, Vec3 scale, List<CubeCollection> cubeCollections, String parentBoneName, List<String> childBoneNames) {
        this.entityId = entityId;
        this.boneName = boneName;
        this.pivot = pivot != null ? pivot : new Vec3(0, 0, 0);
        this.rotation = rotation != null ? rotation : new Vec3(0, 0, 0);
        this.scale = scale != null ? scale : new Vec3(1, 1, 1);
        this.cubeCollections = cubeCollections != null ? cubeCollections : new ArrayList<>();
        this.parentBoneName = parentBoneName;
        this.childBoneNames = childBoneNames != null ? childBoneNames : new ArrayList<>();
    }
    
    /**
     * 旧的构造函数，保持向后兼容
     */
    public BoneCollection(long entityId, String boneName, Vec3 pivot, Vec3 rotation, Vec3 scale, List<CubeCollection> cubeCollections) {
        this(entityId, boneName, pivot, rotation, scale, cubeCollections, null, new ArrayList<>());
    }
    
    /**
     * 更新骨骼的世界变换
     * 这会应用到所有属于此骨骼的立方体集合
     */
    public void updateWorldTransform(Matrix4f parentTransform) {
        // 创建当前骨骼的局部变换矩阵
        Matrix4f localTransform = new Matrix4f().identity();
        
        // 应用平移（pivot）
        if (pivot != null) {
            localTransform.translate((float)pivot.x, (float)pivot.y, (float)pivot.z);
        }
        
        // 应用旋转
        if (rotation != null) {
            localTransform.rotateXYZ((float)rotation.x, (float)rotation.y, (float)rotation.z);
        }
        
        // 应用缩放
        if (scale != null) {
            localTransform.scale((float)scale.x, (float)scale.y, (float)scale.z);
        }
        
        // 计算世界变换（父变换 * 本骨骼变换）
        this.worldTransform = new Matrix4f(parentTransform).mul(localTransform);
        
        // 更新所有立方体集合的世界变换
        for (CubeCollection cubeCollection : cubeCollections) {
            cubeCollection.updateWorldTransform(this.worldTransform);
        }
    }
    
    /**
     * 获取变换后的立方体集合
     */
    public List<CubeCollection> getTransformedCubeCollections() {
        return new ArrayList<>(cubeCollections);
    }
    
    // Getter方法
    public long getEntityId() { return entityId; }
    public String getBoneName() { return boneName; }
    public Vec3 getPivot() { return pivot; }
    public Vec3 getRotation() { return rotation; }
    public Vec3 getScale() { return scale; }
    public Matrix4f getWorldTransform() { return worldTransform; }
    public List<CubeCollection> getCubeCollections() { return new ArrayList<>(cubeCollections); }
    public String getParentBoneName() { return parentBoneName; }
    public List<String> getChildBoneNames() { return new ArrayList<>(childBoneNames); }
    
    /**
     * 添加子骨骼名称
     */
    public void addChildBoneName(String childBoneName) {
        if (!childBoneNames.contains(childBoneName)) {
            childBoneNames.add(childBoneName);
        }
    }
    
    /**
     * 设置子骨骼名称列表
     */
    public void setChildBoneNames(List<String> childBoneNames) {
        this.childBoneNames.clear();
        this.childBoneNames.addAll(childBoneNames);
    }
}