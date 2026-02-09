package com.catoxide.catoxidesbattlerebuild.server.models;

import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.object.Color;
import software.bernie.geckolib.loading.json.raw.Bone;
import software.bernie.geckolib.loading.json.raw.Cube;
import software.bernie.geckolib.loading.object.BoneStructure;
import software.bernie.geckolib.loading.object.GeometryTree;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型级别的静态数据缓存，存储与具体实体无关的模型信息
 * 包括骨骼层次结构、Cube集合等静态数据
 */
public class StaticModelDataManager {
    
    private static final StaticModelDataManager INSTANCE = new StaticModelDataManager();
    
    // 模型静态数据缓存：模型位置 -> 静态模型数据
    private final Map<ResourceLocation, StaticModelData> staticModelCache = new ConcurrentHashMap<>();
    
    // 单例访问
    public static StaticModelDataManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * 静态模型数据类
     * 存储与具体实体无关的模型信息
     */
    public static class StaticModelData {
        private final ResourceLocation modelLocation;
        private final Map<String, BoneStaticData> boneStaticDataMap; // 骨骼静态数据
        private final List<String> boneHierarchy; // 骨骼层次结构
        private final Map<String, List<String>> childBoneMap; // 父骨骼->子骨骼列表映射
        
        public StaticModelData(ResourceLocation modelLocation, 
                              Map<String, BoneStaticData> boneStaticDataMap,
                              List<String> boneHierarchy,
                              Map<String, List<String>> childBoneMap) {
            this.modelLocation = modelLocation;
            this.boneStaticDataMap = boneStaticDataMap;
            this.boneHierarchy = boneHierarchy;
            this.childBoneMap = childBoneMap;
        }
        
        public ResourceLocation getModelLocation() { return modelLocation; }
        public Map<String, BoneStaticData> getBoneStaticDataMap() { return boneStaticDataMap; }
        public List<String> getBoneHierarchy() { return boneHierarchy; }
        public Map<String, List<String>> getChildBoneMap() { return childBoneMap; }
        
        public BoneStaticData getBoneStaticData(String boneName) {
            return boneStaticDataMap.get(boneName);
        }
    }
    
    /**
     * 骨骼静态数据类
     * 存储特定骨骼的静态信息
     */
    public static class BoneStaticData {
        private final String boneName;
        private final String parentBoneName;
        private final List<CubeStaticData> cubeStaticDataList; // 骨骼上的Cube静态数据
        private final Vector3f localPivot; // 局部空间枢轴点
        private final Vector3f localRotation; // 局部空间旋转
        private final Vector3f localScale; // 局部空间缩放
        
        public BoneStaticData(String boneName, String parentBoneName,
                             List<CubeStaticData> cubeStaticDataList,
                             Vector3f localPivot, Vector3f localRotation, Vector3f localScale) {
            this.boneName = boneName;
            this.parentBoneName = parentBoneName;
            this.cubeStaticDataList = cubeStaticDataList != null ? cubeStaticDataList : new ArrayList<>();
            this.localPivot = localPivot != null ? localPivot : new Vector3f();
            this.localRotation = localRotation != null ? localRotation : new Vector3f();
            this.localScale = localScale != null ? localScale : new Vector3f(1, 1, 1);
        }
        
        public String getBoneName() { return boneName; }
        public String getParentBoneName() { return parentBoneName; }
        public List<CubeStaticData> getCubeStaticDataList() { return cubeStaticDataList; }
        public Vector3f getLocalPivot() { return localPivot; }
        public Vector3f getLocalRotation() { return localRotation; }
        public Vector3f getLocalScale() { return localScale; }
    }
    
    /**
     * Cube静态数据类
     * 存储Cube的静态信息
     */
    public static class CubeStaticData {
        private final String id;
        private final Vector3f pivot; // 枢轴点
        private final Vector3f size; // 尺寸
        private final Vector3f rotation; // 旋转
        private final Vector3f originOffset; // 相对于pivot的偏移
        
        public CubeStaticData(String id, Vector3f pivot, Vector3f size, Vector3f rotation, Vector3f originOffset) {
            this.id = id;
            this.pivot = pivot != null ? pivot : new Vector3f();
            this.size = size != null ? size : new Vector3f(1, 1, 1);
            this.rotation = rotation != null ? rotation : new Vector3f();
            this.originOffset = originOffset != null ? originOffset : new Vector3f();
        }
        
        public String getId() { return id; }
        public Vector3f getPivot() { return pivot; }
        public Vector3f getSize() { return size; }
        public Vector3f getRotation() { return rotation; }
        public Vector3f getOriginOffset() { return originOffset; }
    }
    
    /**
     * 预加载模型静态数据
     */
    public StaticModelData preloadModelData(ResourceLocation modelLocation, GeometryTree geometryTree) {
        // 检查缓存
        if (staticModelCache.containsKey(modelLocation)) {
            return staticModelCache.get(modelLocation);
        }
        
        // 提取静态数据
        Map<String, BoneStaticData> boneStaticDataMap = new HashMap<>();
        List<String> boneHierarchy = new ArrayList<>();
        Map<String, List<String>> childBoneMap = new HashMap<>();
        
        // 遍历顶层骨骼 - topLevelBones返回的是Map<String, BoneStructure>
        for (Map.Entry<String, BoneStructure> entry : geometryTree.topLevelBones().entrySet()) {
            processBoneStructureForStaticData(entry.getValue(), boneStaticDataMap, boneHierarchy, childBoneMap, null);
        }
        
        // 创建静态模型数据
        StaticModelData staticModelData = new StaticModelData(modelLocation, boneStaticDataMap, boneHierarchy, childBoneMap);
        
        // 缓存结果
        staticModelCache.put(modelLocation, staticModelData);
        
        return staticModelData;
    }
    
    /**
     * 递归处理BoneStructure以提取静态数据
     * 适配GeckoLib API：使用BoneStructure来处理骨骼层次结构
     */
    private void processBoneStructureForStaticData(BoneStructure boneStructure,
                                                  Map<String, BoneStaticData> boneStaticDataMap,
                                                  List<String> boneHierarchy,
                                                  Map<String, List<String>> childBoneMap,
                                                  String parentBoneName) {
        // 获取Bone对象
        Bone bone = boneStructure.self();
        
        // 添加到层次结构
        boneHierarchy.add(bone.name());
        
        // 处理当前骨骼的cube - cubes()返回的是Cube[]数组
        List<CubeStaticData> cubeStaticDataList = new ArrayList<>();
        
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
                
                // 将double数组转换为Vector3f
                Vector3f originVec = new Vector3f((float) origin[0], (float) origin[1], (float) origin[2]);
                Vector3f sizeVec = new Vector3f((float) size[0], (float) size[1], (float) size[2]);
                Vector3f rotationVec = new Vector3f((float) rotation[0], (float) rotation[1], (float) rotation[2]);
                Vector3f pivotVec = new Vector3f((float) pivot[0], (float) pivot[1], (float) pivot[2]);
                
                // 计算originOffset：origin相对于pivot的偏移
                Vector3f originOffsetVec = new Vector3f(originVec).sub(pivotVec);
                
                // 创建CubeStaticData
                CubeStaticData cubeStaticData = new CubeStaticData(
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
        
        // 创建骨骼静态数据
        BoneStaticData boneStaticData = new BoneStaticData(
                bone.name(),
                parentBoneName,
                cubeStaticDataList,
                new Vector3f((float) pivot[0], (float) pivot[1], (float) pivot[2]), // 骨骼的局部pivot
                new Vector3f(0, 0, 0), // 默认旋转
                new Vector3f(1, 1, 1)  // 默认缩放
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
     * 获取缓存的静态模型数据
     */
    public StaticModelData getStaticModelData(ResourceLocation modelLocation) {
        return staticModelCache.get(modelLocation);
    }
    
    /**
     * 检查是否已缓存指定模型的静态数据
     */
    public boolean hasStaticModelData(ResourceLocation modelLocation) {
        return staticModelCache.containsKey(modelLocation);
    }
    
    /**
     * 清除指定模型的静态数据缓存
     */
    public void clearStaticModelData(ResourceLocation modelLocation) {
        staticModelCache.remove(modelLocation);
    }
    
    /**
     * 清除所有静态数据缓存
     */
    public void clearAllStaticModelData() {
        staticModelCache.clear();
    }
}