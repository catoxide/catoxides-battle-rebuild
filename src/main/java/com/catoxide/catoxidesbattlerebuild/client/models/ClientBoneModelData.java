package com.catoxide.catoxidesbattlerebuild.client.models;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 客户端骨骼模型数据类
 * 存储模型级别的静态数据，用于快速构建ClientBoneCollection和ClientCubeCollection实例
 * 对齐服务端BoneModelData
 */
public record ClientBoneModelData(
        ResourceLocation modelLocation,                           // 模型位置
        Map<String, BoneStaticData> boneStaticDataMap,           // 骨骼静态数据映射（骨骼名 -> 静态数据）
        List<String> boneHierarchy,                              // 骨骼层级结构
        Map<String, List<String>> childBoneMap                   // 父子骨骼映射（父骨骼名 -> 子骨骼名列表）
) {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientBoneModelData.class);

    /**
     * 骨骼静态数据类
     * 存储特定骨骼的静态信息，可在多个实体间共享
     */
    public record BoneStaticData(
            String boneName,                                      // 骨骼名称
            String parentBoneName,                                // 父骨骼名称
            List<CubeStaticData> cubeStaticDataList,              // 骨骼上的Cube静态数据列表
            Vec3 localPivot,                                      // 局部空间枢轴点
            Vec3 localRotation,                                   // 局部空间旋转
            Vec3 localScale                                       // 局部空间缩放
    ) {}

    /**
     * Cube静态数据类
     * 存储Cube的静态信息，可在多个实体间共享
     */
    public record CubeStaticData(
            String id,                                            // Cube唯一标识
            Vec3 pivot,                                           // 枢轴点
            Vec3 size,                                            // 尺寸
            Vec3 rotation,                                        // 旋转
            Vec3 originOffset                                     // 相对于pivot的偏移
    ) {}

    /**
     * 获取指定骨骼的静态数据
     */
    public BoneStaticData getBoneStaticData(String boneName) {
        LOGGER.debug("[ClientBoneModelData] Getting static data for bone: {}", boneName);
        return boneStaticDataMap.get(boneName);
    }

    /**
     * 检查是否存在指定骨骼
     */
    public boolean containsBone(String boneName) {
        boolean contains = boneStaticDataMap.containsKey(boneName);
        LOGGER.debug("[ClientBoneModelData] Checking bone existence: {} = {}", boneName, contains);
        return contains;
    }

    /**
     * 获取指定骨骼的所有子骨骼名称
     */
    public List<String> getChildBones(String boneName) {
        List<String> children = childBoneMap.getOrDefault(boneName, List.of());
        LOGGER.debug("[ClientBoneModelData] Getting child bones for {}: count = {}", boneName, children.size());
        return children;
    }

    /**
     * 获取指定骨骼的父骨骼名称
     */
    public String getParentBone(String boneName) {
        String parent = boneStaticDataMap.get(boneName).parentBoneName();
        LOGGER.debug("[ClientBoneModelData] Getting parent bone for {}: {}", boneName, parent);
        return parent;
    }

    /**
     * 检查是否为根骨骼
     */
    public boolean isRootBone(String boneName) {
        boolean isRoot = getParentBone(boneName) == null;
        LOGGER.debug("[ClientBoneModelData] Checking if bone {} is root: {}", boneName, isRoot);
        return isRoot;
    }

    /**
     * 获取所有根骨骼名称
     */
    public List<String> getRootBones() {
        List<String> roots = boneHierarchy.stream()
                .filter(this::isRootBone)
                .toList();
        LOGGER.debug("[ClientBoneModelData] Getting root bones: count = {}", roots.size());
        return roots;
    }
}
