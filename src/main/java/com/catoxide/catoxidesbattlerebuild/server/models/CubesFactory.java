package com.catoxide.catoxidesbattlerebuild.server.models;

import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;
import software.bernie.geckolib.GeckoLib;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.loading.object.BakedModelFactory;
import software.bernie.geckolib.loading.object.BoneStructure;
import software.bernie.geckolib.loading.object.GeometryTree;
import software.bernie.geckolib.loading.json.raw.Bone;
import software.bernie.geckolib.loading.json.raw.Cube;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 专门用于从原始模型数据中创建CubeCollection的工厂类
 * 通过直接访问GeometryTree中的原始数据，确保获取到准确的origin/offset信息
 */
public class CubesFactory {

    // 缓存：模型位置 -> 骨骼映射表（包含所有CubeCollection）
    private final Map<ResourceLocation, Map<String, List<CubeCollection>>> cubesCache = new ConcurrentHashMap<>();

    /**
     * 从GeometryTree中直接提取所有CubeCollection数据
     *
     * @param modelLocation 模型资源位置
     * @param geometryTree 几何树对象，包含原始模型数据
     * @return Map<String, List<CubeCollection>> 骨骼名 -> CubeCollection列表的映射
     */
    public Map<String, List<CubeCollection>> extractCubesFromGeometry(ResourceLocation modelLocation, GeometryTree geometryTree) {
        // 检查缓存
        if (cubesCache.containsKey(modelLocation)) {
            return cubesCache.get(modelLocation);
        }

        Map<String, List<CubeCollection>> boneCubesMap = new HashMap<>();

        try {
            // 遍历顶层骨骼 - 注意：topLevelBones返回的是BoneStructure，不是Bone
            for (Map.Entry<String, BoneStructure> entry : geometryTree.topLevelBones().entrySet()) {
                processBoneRecursive(entry.getValue(), boneCubesMap);
            }

            // 缓存结果
            cubesCache.put(modelLocation, boneCubesMap);

            GeckoLib.LOGGER.debug("CubesFactory extracted cube data for: {} ({} bones)",
                    modelLocation, boneCubesMap.size());

            return boneCubesMap;

        } catch (Exception e) {
            GeckoLib.LOGGER.error("CubesFactory failed to extract cube data for: {}",
                    modelLocation, e);
            throw new RuntimeException("Cube extraction failed: " + modelLocation, e);
        }
    }

    /**
     * 递归处理骨骼及其子骨骼
     */
    private void processBoneRecursive(BoneStructure boneStructure, Map<String, List<CubeCollection>> boneCubesMap) {
        try {
            Bone bone = boneStructure.self();

            // 处理当前骨骼的cube
            List<CubeCollection> cubeCollections = new ArrayList<>();

            if (bone.cubes() != null) {
                for (int i = 0; i < bone.cubes().length; i++) {
                    Cube cube = bone.cubes()[i];

                    // 从原始cube数据创建CubeCollection
                    String id = String.format("%s_cube_%d", bone.name(), i);

                    // 获取原始数据
                    double[] origin = cube.origin() != null ? cube.origin() : new double[]{0, 0, 0};
                    double[] size = cube.size() != null ? cube.size() : new double[]{1, 1, 1};
                    double[] rotation = cube.rotation() != null ? cube.rotation() : new double[]{0, 0, 0};
                    double[] pivot = cube.pivot() != null ? cube.pivot() : new double[]{0, 0, 0};

                    // 将double数组转换为Vector3f
                    Vector3f originVec = new Vector3f((float) origin[0], (float) origin[1], (float) origin[2]);
                    Vector3f sizeVec = new Vector3f((float) size[0], (float) size[1], (float) size[2]);
                    Vector3f rotationVec = new Vector3f((float) rotation[0], (float) rotation[1], (float) rotation[2]);
                    Vector3f pivotVec = new Vector3f((float) pivot[0], (float) pivot[1], (float) pivot[2]);

                    // 计算originOffset：origin相对于pivot的偏移
                    Vector3f originOffsetVec = new Vector3f(originVec).sub(pivotVec);

                    // 创建CubeCollection
                    CubeCollection cubeCollection = CubeCollection.fromJsonData(
                            id,
                            pivotVec,     // pivot作为枢轴点
                            sizeVec,      // 尺寸
                            rotationVec,  // 旋转
                            originOffsetVec // 原点偏移量
                    );

                    cubeCollections.add(cubeCollection);
                }
            }

            // 存储当前骨骼的cube集合
            boneCubesMap.put(bone.name(), cubeCollections);

            // 递归处理子骨骼 - 注意：children返回的是Map<String, BoneStructure>
            if (boneStructure.children() != null && !boneStructure.children().isEmpty()) {
                for (BoneStructure childBoneStructure : boneStructure.children().values()) {
                    processBoneRecursive(childBoneStructure, boneCubesMap);
                }
            }

        } catch (Exception e) {
            GeckoLib.LOGGER.error("Failed to process bone: {}",
                    boneStructure != null ? boneStructure.self().name() : "null", e);
        }
    }

    /**
     * 清除指定模型的缓存
     */
    public void clearCacheForResource(ResourceLocation modelLocation) {
        cubesCache.remove(modelLocation);
        GeckoLib.LOGGER.debug("CubesFactory cache cleared for: {}", modelLocation);
    }

    /**
     * 清除所有缓存
     */
    public void clearAllCache() {
        cubesCache.clear();
        GeckoLib.LOGGER.debug("CubesFactory all caches cleared");
    }

    /**
     * 获取缓存的cube数据
     */
    public Map<String, List<CubeCollection>> getCachedCubes(ResourceLocation modelLocation) {
        return cubesCache.get(modelLocation);
    }

    /**
     * 检查是否已缓存指定模型的数据
     */
    public boolean hasCachedCubes(ResourceLocation modelLocation) {
        return cubesCache.containsKey(modelLocation);
    }
}
