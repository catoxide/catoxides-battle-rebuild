package com.catoxide.catoxidesbattlerebuild.client.models;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.model.GeoModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BoneModelDataExtractor {
    public static <T extends GeoAnimatable> ClientBoneModelData extractFromGeoModel(
            GeoModel<T> geoModel,
            ResourceLocation modelLocation,
            T animatable) {
        BakedGeoModel bakedModel = geoModel.getBakedModel(
                geoModel.getModelResource(animatable, null));
        return extractFromBakedGeoModel(bakedModel, modelLocation);
    }

    public static ClientBoneModelData extractFromBakedGeoModel(
            BakedGeoModel bakedModel,
            ResourceLocation modelLocation) {
        Map<String, ClientBoneModelData.BoneStaticData> boneDataMap = new HashMap<>();
        List<String> boneHierarchy = new ArrayList<>();
        Map<String, List<String>> childBoneMap = new HashMap<>();

        for (GeoBone rootBone : bakedModel.topLevelBones()) {
            processBone(rootBone, boneDataMap, boneHierarchy, childBoneMap, null);
        }

        LogManager.clientDebug("BoneModelDataExtractor",
                "Extracted model data: model={}, bones={}", modelLocation, boneDataMap.size());

        return new ClientBoneModelData(modelLocation, boneDataMap, boneHierarchy, childBoneMap);
    }

    private static void processBone(GeoBone bone,
                                    Map<String, ClientBoneModelData.BoneStaticData> boneDataMap,
                                    List<String> boneHierarchy,
                                    Map<String, List<String>> childBoneMap,
                                    String parentBoneName) {
        boneHierarchy.add(bone.getName());

        List<ClientBoneModelData.CubeStaticData> cubes = new ArrayList<>();
        for (GeoCube geoCube : bone.getCubes()) {
            cubes.add(extractCubeData(bone.getName(), geoCube));
        }

        ClientBoneModelData.BoneStaticData boneData = new ClientBoneModelData.BoneStaticData(
                bone.getName(),
                parentBoneName,
                cubes,
                new Vec3(bone.getPivotX() / 16.0, bone.getPivotY() / 16.0, bone.getPivotZ() / 16.0),
                new Vec3(0, 0, 0),
                new Vec3(1, 1, 1)
        );

        boneDataMap.put(bone.getName(), boneData);

        if (parentBoneName != null) {
            childBoneMap.computeIfAbsent(parentBoneName, k -> new ArrayList<>())
                    .add(bone.getName());
        }

        for (GeoBone child : bone.getChildBones()) {
            processBone(child, boneDataMap, boneHierarchy, childBoneMap, bone.getName());
        }
    }

    private static ClientBoneModelData.CubeStaticData extractCubeData(
            String boneName, GeoCube cube) {
        Vec3 pivot = cube.pivot().scale(1 / 16.0);
        Vec3 size = cube.size().scale(1 / 16.0);
        Vec3 rotation = cube.rotation();
        Vec3 originOffset = new Vec3(0, 0, 0);

        return new ClientBoneModelData.CubeStaticData(
                boneName + "_cube_" + cube.hashCode(),
                pivot,
                size,
                rotation,
                originOffset
        );
    }
}
