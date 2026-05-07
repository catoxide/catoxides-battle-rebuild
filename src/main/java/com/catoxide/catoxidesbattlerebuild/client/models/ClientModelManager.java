package com.catoxide.catoxidesbattlerebuild.client.models;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import software.bernie.geckolib.loading.json.raw.Bone;
import software.bernie.geckolib.loading.json.raw.Cube;
import software.bernie.geckolib.loading.object.BoneStructure;
import software.bernie.geckolib.loading.object.GeometryTree;
import software.bernie.geckolib.model.GeoModel;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端模型管理器
 * 负责管理客户端模型集合和静态数据
 * 对齐服务端ServerGeoModelManager
 */
public class ClientModelManager {
    
    private static ClientModelManager instance;
    
    // 模型集合映射（模型位置 -> 模型集合）
    private final Map<ResourceLocation, ClientModelCollection> modelCollections;
    
    // 骨骼模型数据映射（模型位置 -> 骨骼模型数据）
    private final Map<ResourceLocation, ClientBoneModelData> boneModelDataMap;
    
    private ClientModelManager() {
        this.modelCollections = new ConcurrentHashMap<>();
        this.boneModelDataMap = new ConcurrentHashMap<>();
        LogManager.clientInfo("ClientModelManager", "ClientModelManager initialized");
    }
    
    /**
     * 获取单例实例
     */
    public static ClientModelManager getInstance() {
        if (instance == null) {
            instance = new ClientModelManager();
        }
        return instance;
    }
    
    /**
     * 初始化模型管理器
     */
    public void initialize() {
        LogManager.clientInfo("ClientModelManager", "=== INITIALIZING MODEL MANAGER ===");
        
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getResourceManager() == null) {
            LogManager.clientError("ClientModelManager", "Cannot initialize: Minecraft or ResourceManager is null");
            return;
        }
        
        // 扫描并加载所有geo JSON文件
        Map<ResourceLocation, net.minecraft.server.packs.resources.Resource> geoResources = mc.getResourceManager().listResources("geo", 
            resourceLocation -> resourceLocation.getPath().endsWith(".json"));
        
        LogManager.clientInfo("ClientModelManager", "Found {} geo JSON files in assets", geoResources.size());
        
        for (Map.Entry<ResourceLocation, net.minecraft.server.packs.resources.Resource> entry : geoResources.entrySet()) {
            ResourceLocation resourceLocation = entry.getKey();
            LogManager.clientInfo("ClientModelManager", "Processing geo file: {}", resourceLocation);
            
            try (InputStream inputStream = entry.getValue().open()) {
                // 使用GeckoLib的JsonUtil解析
                GeometryTree geometryTree = software.bernie.geckolib.util.JsonUtil.GEO_GSON.fromJson(
                    new InputStreamReader(inputStream), GeometryTree.class);
                
                if (geometryTree == null) {
                    LogManager.clientWarn("ClientModelManager", "Failed to parse GeometryTree for: {}", resourceLocation);
                    continue;
                }
                
                // 提取骨骼数据
                Map<String, ClientBoneModelData.BoneStaticData> boneStaticDataMap = extractBoneData(geometryTree);
                
                if (boneStaticDataMap.isEmpty()) {
                    LogManager.clientWarn("ClientModelManager", "No bone data extracted for: {}", resourceLocation);
                    continue;
                }
                
                // 构建骨骼层级和父子映射
                List<String> boneHierarchy = new ArrayList<>(boneStaticDataMap.keySet());
                Map<String, List<String>> childBoneMap = new HashMap<>();
                for (ClientBoneModelData.BoneStaticData boneStaticData : boneStaticDataMap.values()) {
                    String parentName = boneStaticData.parentBoneName();
                    childBoneMap.computeIfAbsent(parentName, k -> new ArrayList<>()).add(boneStaticData.boneName());
                }
                
                // 创建ClientBoneModelData
                ClientBoneModelData boneModelData = new ClientBoneModelData(resourceLocation, boneStaticDataMap, boneHierarchy, childBoneMap);
                
                // 创建并存储模型集合
                ClientModelCollection collection = ClientModelCollection.create(resourceLocation, 
                    resourceLocation.getPath(), boneModelData);
                modelCollections.put(resourceLocation, collection);
                
                // 同时存储到boneModelDataMap
                boneModelDataMap.put(resourceLocation, boneModelData);
                
                LogManager.clientInfo("ClientModelManager", "Loaded model: {} with {} bones", 
                    resourceLocation, boneStaticDataMap.size());
                
            } catch (IOException e) {
                LogManager.clientError("ClientModelManager", "Failed to load geo JSON: " + resourceLocation, e);
            }
        }
        
        LogManager.clientInfo("ClientModelManager", "Model manager initialization completed. Loaded {} models.", modelCollections.size());
    }
    
    /**
     * 从GeometryTree提取骨骼数据
     */
    private Map<String, ClientBoneModelData.BoneStaticData> extractBoneData(GeometryTree geometryTree) {
        Map<String, ClientBoneModelData.BoneStaticData> boneStaticDataMap = new ConcurrentHashMap<>();
        
        if (geometryTree == null) {
            LogManager.clientWarn("ClientModelManager", "GeometryTree is null, cannot extract bone data");
            return boneStaticDataMap;
        }
        
        // 使用topLevelBones()遍历顶层骨骼
        Map<String, software.bernie.geckolib.loading.object.BoneStructure> topLevelBones = geometryTree.topLevelBones();
        if (topLevelBones == null) {
            LogManager.clientWarn("ClientModelManager", "topLevelBones is null, cannot extract bone data");
            return boneStaticDataMap;
        }
        
        for (Map.Entry<String, software.bernie.geckolib.loading.object.BoneStructure> entry : topLevelBones.entrySet()) {
            software.bernie.geckolib.loading.object.BoneStructure boneStructure = entry.getValue();
            processBoneStructure(boneStructure, boneStaticDataMap, null);
        }
        
        return boneStaticDataMap;
    }
    
    /**
     * 递归处理骨骼结构
     */
    private void processBoneStructure(software.bernie.geckolib.loading.object.BoneStructure boneStructure,
                                     Map<String, ClientBoneModelData.BoneStaticData> boneStaticDataMap,
                                     String parentName) {
        // 获取Bone对象
        software.bernie.geckolib.loading.json.raw.Bone bone = boneStructure.self();
        String boneName = bone.name();
        
        List<ClientBoneModelData.CubeStaticData> cubeStaticDataList = new ArrayList<>();
        
        // 处理骨骼的cubes - cubes()返回的是Cube[]数组
        if (bone.cubes() != null && bone.cubes().length > 0) {
            for (int i = 0; i < bone.cubes().length; i++) {
                software.bernie.geckolib.loading.json.raw.Cube cube = bone.cubes()[i];
                String id = boneName + "_cube_" + i;
                
                double[] origin = cube.origin() != null ? cube.origin() : new double[]{0, 0, 0};
                double[] size = cube.size() != null ? cube.size() : new double[]{1, 1, 1};
                double[] rotation = cube.rotation() != null ? cube.rotation() : new double[]{0, 0, 0};
                double[] pivot = cube.pivot() != null ? cube.pivot() : new double[]{0, 0, 0};
                
                // 保持像素坐标，不进行转换
                Vec3 originVec = new Vec3((float) origin[0], (float) origin[1], (float) origin[2]);
                Vec3 sizeVec = new Vec3((float) size[0], (float) size[1], (float) size[2]);
                Vec3 rotationVec = new Vec3((float) rotation[0], (float) rotation[1], (float) rotation[2]);
                Vec3 pivotVec = new Vec3((float) pivot[0], (float) pivot[1], (float) pivot[2]);
                
                // 计算 originOffset
                Vec3 originOffsetVec = new Vec3(
                    originVec.x - pivotVec.x,
                    originVec.y - pivotVec.y,
                    originVec.z - pivotVec.z
                );
                
                ClientBoneModelData.CubeStaticData cubeStaticData = new ClientBoneModelData.CubeStaticData(
                    id, pivotVec, sizeVec, rotationVec, originOffsetVec
                );
                
                cubeStaticDataList.add(cubeStaticData);
            }
        }
        
        // 获取骨骼的 pivot 数据
        double[] pivotArr = bone.pivot() != null ? bone.pivot() : new double[]{0, 0, 0};
        Vec3 bonePivot = new Vec3((float) pivotArr[0], (float) pivotArr[1], (float) pivotArr[2]);
        
        // 获取骨骼的 rotation 数据
        double[] rotArr = bone.rotation() != null ? bone.rotation() : new double[]{0, 0, 0};
        Vec3 boneRotation = new Vec3((float) rotArr[0], (float) rotArr[1], (float) rotArr[2]);
        
        // 使用默认缩放值（Bone类没有scale()方法）
        Vec3 boneScale = new Vec3(1, 1, 1);
        
        // 创建骨骼静态数据
        ClientBoneModelData.BoneStaticData boneStaticData = new ClientBoneModelData.BoneStaticData(
            boneName,
            parentName,
            cubeStaticDataList,
            bonePivot,
            boneRotation,
            boneScale
        );
        boneStaticDataMap.put(boneName, boneStaticData);
        
        // 递归处理子骨骼 - children()返回Map<String, BoneStructure>
        if (boneStructure.children() != null && !boneStructure.children().isEmpty()) {
            for (software.bernie.geckolib.loading.object.BoneStructure child : boneStructure.children().values()) {
                processBoneStructure(child, boneStaticDataMap, boneName);
            }
        }
    }
    
    /**
     * 注册模型集合
     */
    public void registerModelCollection(ResourceLocation modelLocation, ClientModelCollection collection) {
        modelCollections.put(modelLocation, collection);
        LogManager.clientInfo("ClientModelManager", "Registered model collection: {} at {}", 
            collection.modelLocation(), modelLocation);
    }
    
    /**
     * 注册骨骼模型数据
     */
    public void registerBoneModelData(ResourceLocation modelLocation, ClientBoneModelData boneModelData) {
        boneModelDataMap.put(modelLocation, boneModelData);
        LogManager.clientInfo("ClientModelManager", "Registered bone model data for model: {}", modelLocation);
    }
    
    /**
     * 获取模型集合
     */
    public ClientModelCollection getModelCollection(ResourceLocation modelLocation) {
        ClientModelCollection collection = modelCollections.get(modelLocation);
        if (collection == null) {
            LogManager.clientWarn("ClientModelManager", "Model collection not found: {}", modelLocation);
            return null;
        }
        LogManager.clientDebug("ClientModelManager", "Retrieved model collection: {}", modelLocation);
        return collection;
    }
    
    /**
     * 获取骨骼模型数据
     */
    public ClientBoneModelData getBoneModelData(ResourceLocation modelLocation) {
        ClientBoneModelData boneModelData = boneModelDataMap.get(modelLocation);
        if (boneModelData == null) {
            LogManager.clientWarn("ClientModelManager", "Bone model data not found: {}", modelLocation);
            return null;
        }
        LogManager.clientDebug("ClientModelManager", "Retrieved bone model data: {}", modelLocation);
        return boneModelData;
    }
    
    /**
     * 检查模型是否存在
     */
    public boolean hasModel(ResourceLocation modelLocation) {
        boolean has = modelCollections.containsKey(modelLocation);
        LogManager.clientDebug("ClientModelManager", "Checking model existence: {} = {}", modelLocation, has);
        return has;
    }
    
    /**
     * 获取所有已注册的模型位置
     */
    public Set<ResourceLocation> getRegisteredModelLocations() {
        return modelCollections.keySet();
    }
    
    /**
     * 获取模型数量
     */
    public int getModelCount() {
        return modelCollections.size();
    }
    
    /**
     * 清空所有模型数据
     */
    public void clearAll() {
        int collectionCount = modelCollections.size();
        int boneDataCount = boneModelDataMap.size();
        
        modelCollections.clear();
        boneModelDataMap.clear();
        
        LogManager.clientInfo("ClientModelManager", "Cleared {} model collections and {} bone model data entries", 
            collectionCount, boneDataCount);
    }
    
    /**
     * 从网络接收模型数据
     */
    public void receiveModelDataFromNetwork(ResourceLocation modelLocation, ClientModelCollection collection) {
        LogManager.clientInfo("ClientModelManager", "Receiving model data from network: {}", modelLocation);
        registerModelCollection(modelLocation, collection);
    }
}
