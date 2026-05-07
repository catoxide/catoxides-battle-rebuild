package com.catoxide.catoxidesbattlerebuild.server.models;

import com.catoxide.catoxidesbattlerebuild.server.geometry.CubeCollection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.loading.json.raw.Model;
import software.bernie.geckolib.loading.json.raw.Bone;
import software.bernie.geckolib.loading.json.raw.Cube;
import software.bernie.geckolib.loading.object.BoneStructure;
import software.bernie.geckolib.loading.object.GeometryTree;
import software.bernie.geckolib.util.JsonUtil;

import java.util.*;

/**
 * 骨骼模型数据提取器
 * 从原始模型文件中提取骨骼和Cube的静态数据，用于创建BoneModelData
 */
public class BoneModelDataExtractor {

    /**
     * 从GeometryTree提取BoneModelData
     */
    public static BoneModelData extractBoneModelData(ResourceLocation modelLocation, GeometryTree geometryTree) {
        // 初始化数据结构
        Map<String, BoneModelData.BoneStaticData> boneStaticDataMap = new HashMap<>();
        List<String> boneHierarchy = new ArrayList<>();
        Map<String, List<String>> childBoneMap = new HashMap<>();

        // 遍历顶层骨骼 - topLevelBones返回的是Map<String, BoneStructure>
        for (Map.Entry<String, BoneStructure> entry : geometryTree.topLevelBones().entrySet()) {
            processBoneStructureForStaticData(entry.getValue(), boneStaticDataMap, boneHierarchy, childBoneMap, null);
        }

        return new BoneModelData(modelLocation, boneStaticDataMap, boneHierarchy, childBoneMap);
    }

    /**
     * 从原始模型文件提取BoneModelData
     */
    public static BoneModelData extractBoneModelDataFromRawModel(ResourceLocation modelLocation, Model rawModel) {
        // 从原始模型创建几何树
        GeometryTree geometryTree = GeometryTree.fromModel(rawModel);

        // 提取骨骼模型数据
        return extractBoneModelData(modelLocation, geometryTree);
    }

    /**
     * 递归处理BoneStructure以提取静态数据
     * 适配GeckoLib API：使用BoneStructure来处理骨骼层次结构
     */
    private static void processBoneStructureForStaticData(BoneStructure boneStructure,
                                                          Map<String, BoneModelData.BoneStaticData> boneStaticDataMap,
                                                          List<String> boneHierarchy,
                                                          Map<String, List<String>> childBoneMap,
                                                          String parentBoneName) {
        // 获取Bone对象
        Bone bone = boneStructure.self();

        // 添加到层次结构
        boneHierarchy.add(bone.name());

        // 处理当前骨骼的cube - cubes()返回的是Cube[]数组
        List<BoneModelData.CubeStaticData> cubeStaticDataList = new ArrayList<>();

        if (bone.cubes() != null && bone.cubes().length > 0) {
            for (int i = 0; i < bone.cubes().length; i++) {
                Cube cube = bone.cubes()[i];

                // 从原始cube数据创建CubeStaticData
                String id = String.format("%s_cube_%d", bone.name(), i);

                // 获取原始数据
                double[] origin = cube.origin() != null ? cube.origin() : new double[]{0, 0, 0};
                double[] size = cube.size() != null ? cube.size() : new double[]{1, 1, 1};
                double[] rotation = cube.rotation() != null ? cube.rotation() : new double[]{0, 0, 0};
                double[] pivot = cube.pivot() != null ? cube.pivot() : new double[]{0, 0, 0};

                // 将double数组转换为Vec3，并将像素坐标转换为方块坐标（除以16）
                Vec3 originVec = new Vec3(origin[0] / 16.0, origin[1] / 16.0, origin[2] / 16.0);
                Vec3 sizeVec = new Vec3(size[0] / 16.0, size[1] / 16.0, size[2] / 16.0);
                Vec3 rotationVec = new Vec3(rotation[0], rotation[1], rotation[2]);
                Vec3 pivotVec = new Vec3(pivot[0] / 16.0, pivot[1] / 16.0, pivot[2] / 16.0);

                // 计算originOffset：origin相对于pivot的偏移
                Vec3 originOffsetVec = originVec.subtract(pivotVec);

                // 创建CubeStaticData
                BoneModelData.CubeStaticData cubeStaticData = new BoneModelData.CubeStaticData(
                        id,
                        pivotVec,           // 枢轴点
                        sizeVec,            // 尺寸
                        rotationVec,        // 旋转
                        originOffsetVec     // 原点偏移量
                );

                cubeStaticDataList.add(cubeStaticData);
            }
        }

        // 获取骨骼的pivot数据
        double[] pivot = bone.pivot() != null ? bone.pivot() : new double[]{0, 0, 0};

        // 创建骨骼静态数据（pivot需要转换为方块坐标）
        BoneModelData.BoneStaticData boneStaticData = new BoneModelData.BoneStaticData(
                bone.name(),
                parentBoneName,
                cubeStaticDataList,
                new Vec3(pivot[0] / 16.0, pivot[1] / 16.0, pivot[2] / 16.0), // 骨骼的局部pivot（转换为方块坐标）
                new Vec3(0, 0, 0), // 默认旋转
                new Vec3(1, 1, 1)  // 默认缩放
        );

        boneStaticDataMap.put(bone.name(), boneStaticData);

        // 记录父子关系
        if (parentBoneName != null) {
            childBoneMap.computeIfAbsent(parentBoneName, k -> new ArrayList<>()).add(bone.name());
        }

        // 递归处理子骨骼 - children返回的是Map<String, BoneStructure>
        if (boneStructure.children() != null && !boneStructure.children().isEmpty()) {
            for (BoneStructure childBoneStructure : boneStructure.children().values()) {
                processBoneStructureForStaticData(childBoneStructure, boneStaticDataMap, boneHierarchy, childBoneMap, bone.name());
            }
        }
    }

    /**
     * 从原始模型文件路径提取BoneModelData
     * 使用Gson库解析JSON文件，避免依赖FILE_CODEC
     */
    public static BoneModelData extractFromModelFile(ResourceLocation modelLocation, java.io.InputStream inputStream) throws Exception {
        // 使用Gson解析模型文件
        Model rawModel = JsonUtil.GEO_GSON.fromJson(new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8), Model.class);

        return extractBoneModelDataFromRawModel(modelLocation, rawModel);
    }

    /**
     * 使用CubesFactory从GeometryTree提取CubeCollection数据
     * 整合CubesFactory的cube获取方法
     */
    public static Map<String, List<CubeCollection>> extractCubeCollections(ResourceLocation modelLocation, GeometryTree geometryTree) {
        CubesFactory cubesFactory = new CubesFactory();
        return cubesFactory.extractCubesFromGeometry(modelLocation, geometryTree);
    }

    /**
     * 从原始模型文件提取CubeCollection数据
     */
    public static Map<String, List<CubeCollection>> extractCubeCollectionsFromRawModel(ResourceLocation modelLocation, Model rawModel) {
        // 从原始模型创建几何树
        GeometryTree geometryTree = GeometryTree.fromModel(rawModel);

        // 提取CubeCollection数据
        return extractCubeCollections(modelLocation, geometryTree);
    }

    /**
     * 从原始模型文件路径提取CubeCollection数据
     * 使用Gson库解析JSON文件，避免依赖FILE_CODEC
     */
    public static Map<String, List<CubeCollection>> extractCubeCollectionsFromFile(ResourceLocation modelLocation, java.io.InputStream inputStream) throws Exception {
        // 使用Gson解析模型文件
        Model rawModel = JsonUtil.GEO_GSON.fromJson(new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8), Model.class);

        return extractCubeCollectionsFromRawModel(modelLocation, rawModel);
    }
}