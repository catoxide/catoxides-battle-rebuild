package com.catoxide.catoxidesbattlerebuild.server.models;

import com.catoxide.catoxidesbattlerebuild.server.geometry.CubeCollection;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.GeckoLib;
import software.bernie.geckolib.loading.object.GeometryTree;

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
        
        // 使用静态数据管理器预加载模型数据
        StaticModelDataManager staticDataManager = StaticModelDataManager.getInstance();
        StaticModelDataManager.StaticModelData staticModelData = staticDataManager.preloadModelData(modelLocation, geometryTree);
        
        Map<String, List<CubeCollection>> boneCubesMap = new HashMap<>();
        
        try {
            // 使用预加载的静态数据创建CubeCollection
            for (Map.Entry<String, StaticModelDataManager.BoneStaticData> entry : 
                 staticModelData.getBoneStaticDataMap().entrySet()) {
                String boneName = entry.getKey();
                StaticModelDataManager.BoneStaticData boneStaticData = entry.getValue();
                
                // 将静态数据转换为CubeCollection
                List<CubeCollection> cubeCollections = new ArrayList<>();
                for (StaticModelDataManager.CubeStaticData cubeStaticData : boneStaticData.getCubeStaticDataList()) {
                    CubeCollection cubeCollection = CubeCollection.fromJsonData(
                            cubeStaticData.getId(),
                            cubeStaticData.getPivot(),
                            cubeStaticData.getSize(),
                            cubeStaticData.getRotation(),
                            cubeStaticData.getOriginOffset()
                    );
                    cubeCollections.add(cubeCollection);
                }
                
                boneCubesMap.put(boneName, cubeCollections);
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
     * 从静态模型数据创建CubeCollection
     */
    public Map<String, List<CubeCollection>> extractCubesFromStaticData(ResourceLocation modelLocation) {
        StaticModelDataManager staticDataManager = StaticModelDataManager.getInstance();
        StaticModelDataManager.StaticModelData staticModelData = staticDataManager.getStaticModelData(modelLocation);
        
        if (staticModelData == null) {
            throw new IllegalStateException("Static model data not preloaded for: " + modelLocation);
        }
        
        Map<String, List<CubeCollection>> boneCubesMap = new HashMap<>();
        
        // 使用预加载的静态数据创建CubeCollection
        for (Map.Entry<String, StaticModelDataManager.BoneStaticData> entry : 
             staticModelData.getBoneStaticDataMap().entrySet()) {
            String boneName = entry.getKey();
            StaticModelDataManager.BoneStaticData boneStaticData = entry.getValue();
            
            // 将静态数据转换为CubeCollection
            List<CubeCollection> cubeCollections = new ArrayList<>();
            for (StaticModelDataManager.CubeStaticData cubeStaticData : boneStaticData.getCubeStaticDataList()) {
                CubeCollection cubeCollection = CubeCollection.fromJsonData(
                        cubeStaticData.getId(),
                        cubeStaticData.getPivot(),
                        cubeStaticData.getSize(),
                        cubeStaticData.getRotation(),
                        cubeStaticData.getOriginOffset()
                );
                cubeCollections.add(cubeCollection);
            }
            
            boneCubesMap.put(boneName, cubeCollections);
        }
        
        return boneCubesMap;
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