package com.catoxide.catoxidesbattlerebuild.client.models;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;

public record ClientBoneModelData(
        ResourceLocation modelLocation,
        Map<String, BoneStaticData> boneStaticDataMap,
        List<String> boneHierarchy,
        Map<String, List<String>> childBoneMap
) {

    public record BoneStaticData(
            String boneName,
            String parentBoneName,
            List<CubeStaticData> cubeStaticDataList,
            Vec3 localPivot,
            Vec3 localRotation,
            Vec3 localScale
    ) {}

    public record CubeStaticData(
            String id,
            Vec3 pivot,
            Vec3 size,
            Vec3 rotation,
            Vec3 originOffset
    ) {}

    public BoneStaticData getBoneStaticData(String boneName) {
        LogManager.clientDebug("ClientBoneModelData", "Getting static data for bone: {}", boneName);
        return boneStaticDataMap.get(boneName);
    }

    public boolean containsBone(String boneName) {
        boolean contains = boneStaticDataMap.containsKey(boneName);
        LogManager.clientDebug("ClientBoneModelData", "Checking bone existence: {} = {}", boneName, contains);
        return contains;
    }

    public List<String> getChildBones(String boneName) {
        List<String> children = childBoneMap.getOrDefault(boneName, List.of());
        LogManager.clientDebug("ClientBoneModelData", "Getting child bones for {}: count = {}", boneName, children.size());
        return children;
    }

    public String getParentBone(String boneName) {
        String parent = boneStaticDataMap.get(boneName).parentBoneName();
        LogManager.clientDebug("ClientBoneModelData", "Getting parent bone for {}: {}", boneName, parent);
        return parent;
    }

    public boolean isRootBone(String boneName) {
        boolean isRoot = getParentBone(boneName) == null;
        LogManager.clientDebug("ClientBoneModelData", "Checking if bone {} is root: {}", boneName, isRoot);
        return isRoot;
    }

    public List<String> getRootBones() {
        List<String> roots = boneHierarchy.stream()
                .filter(this::isRootBone)
                .toList();
        LogManager.clientDebug("ClientBoneModelData", "Getting root bones: count = {}", roots.size());
        return roots;
    }
}