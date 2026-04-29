package com.catoxide.catoxidesbattlerebuild.client.models;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.bernie.geckolib.GeckoLib;
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
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientModelManager.class);
    
    private static ClientModelManager instance;
    
    // 模型集合映射（模型位置 -> 模型集合）
    private final Map<ResourceLocation, ClientModelCollection> modelCollections;
    
    // 骨骼模型数据映射（模型位置 -> 骨骼模型数据）
    private final Map<ResourceLocation, ClientBoneModelData> boneModelDataMap;
    
    private ClientModelManager() {
        this.modelCollections = new ConcurrentHashMap<>();
        this.boneModelDataMap = new ConcurrentHashMap<>();
        LOGGER.info("[ClientModelManager] ClientModelManager initialized");
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
     * 从资源包加载 geo JSON 模型文件
     */
    public void initialize() {
        LOGGER.info("[ClientModelManager] === INITIALIZING MODEL MANAGER ===");
        modelCollections.clear();
        boneModelDataMap.clear();
        
        // 从客户端资源包加载 geo JSON 模型
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getResourceManager() == null) {
            LOGGER.error("[ClientModelManager] Cannot initialize: Minecraft or ResourceManager is null");
            return;
        }
        
        var resourceManager = mc.getResourceManager();
        
        // 查找所有 geo JSON 文件
        var geoResources = resourceManager.listResources("geo", fileName -> fileName.toString().endsWith(".json"));
        
        LOGGER.info("[ClientModelManager] Found {} geo JSON files in assets", geoResources.size());
        
        for (Map.Entry<ResourceLocation, net.minecraft.server.packs.resources.Resource> entry : geoResources.entrySet()) {
            ResourceLocation resourceLocation = entry.getKey();
            LOGGER.info("[ClientModelManager] Processing geo file: {}", resourceLocation);
            try {
                // 加载 GeoJSON 并解析为 GeometryTree
                net.minecraft.server.packs.resources.Resource resource = entry.getValue();
                try (InputStream inputStream = resource.open();
                     InputStreamReader reader = new InputStreamReader(inputStream)) {
                    
                    // 使用 GeckoLib 的 Gson 解析几何数据
                    java.lang.reflect.Field gsonField = software.bernie.geckolib.GeckoLib.class.getDeclaredField("GSON");
                    gsonField.setAccessible(true);
                    com.google.gson.Gson gson = (com.google.gson.Gson) gsonField.get(null);
                    
                    // 读取 JSON 内容
                    StringBuilder jsonBuilder = new StringBuilder();
                    char[] buffer = new char[4096];
                    int len;
                    while ((len = reader.read(buffer)) > 0) {
                        jsonBuilder.append(buffer, 0, len);
                    }
                    
                    // 解析为 GeometryTree
                    GeometryTree geometryTree = gson.fromJson(jsonBuilder.toString(), GeometryTree.class);
                    
                    if (geometryTree == null) {
                        LOGGER.warn("[ClientModelManager] Failed to parse GeometryTree for: {}", resourceLocation);
                        continue;
                    }
                    
                    // 从 GeometryTree 提取静态数据
                    ClientBoneModelData boneModelData = extractBoneModelData(resourceLocation, geometryTree);
                    
                    if (boneModelData == null || boneModelData.boneStaticDataMap().isEmpty()) {
                        LOGGER.warn("[ClientModelManager] No bone data extracted for: {}", resourceLocation);
                        continue;
                    }
                    
                    // 创建模型名称（从资源位置派生）
                    String modelName = resourceLocation.getPath().replace(".json", "");
                    
                    // 创建并注册模型集合
                    ClientModelCollection modelCollection = ClientModelCollection.create(
                        resourceLocation, modelName, boneModelData
                    );
                    registerModelCollection(modelCollection);
                    registerBoneModelData(resourceLocation, boneModelData);
                    
                    LOGGER.info("[ClientModelManager] Loaded model: {} with {} bones", 
                        resourceLocation, boneModelData.boneStaticDataMap().size());
                }
            } catch (Exception e) {
                LOGGER.error("[ClientModelManager] Failed to load geo JSON: {}", resourceLocation, e);
            }
        }
        
        LOGGER.info("[ClientModelManager] Model manager initialization completed. Loaded {} models.", modelCollections.size());
    }
    
    /**
     * 从 GeometryTree 提取客户端骨骼模型数据
     */
    private ClientBoneModelData extractBoneModelData(ResourceLocation modelLocation, GeometryTree geometryTree) {
        Map<String, ClientBoneModelData.BoneStaticData> boneStaticDataMap = new HashMap<>();
        List<String> boneHierarchy = new ArrayList<>();
        Map<String, List<String>> childBoneMap = new HashMap<>();
        
        // 遍历顶层骨骼
        for (Map.Entry<String, BoneStructure> entry : geometryTree.topLevelBones().entrySet()) {
            processBoneStructure(entry.getValue(), boneStaticDataMap, boneHierarchy, childBoneMap, null);
        }
        
        return new ClientBoneModelData(
            modelLocation,
            boneStaticDataMap,
            boneHierarchy,
            childBoneMap
        );
    }
    
    /**
     * 递归处理 BoneStructure 提取静态数据
     */
    private void processBoneStructure(BoneStructure boneStructure,
                                      Map<String, ClientBoneModelData.BoneStaticData> boneStaticDataMap,
                                      List<String> boneHierarchy,
                                      Map<String, List<String>> childBoneMap,
                                      String parentBoneName) {
        Bone bone = boneStructure.self();
        boneHierarchy.add(bone.name());
        
        // 处理当前骨骼的 cube
        List<ClientBoneModelData.CubeStaticData> cubeStaticDataList = new ArrayList<>();
        
        if (bone.cubes() != null && bone.cubes().length > 0) {
            for (int i = 0; i < bone.cubes().length; i++) {
                Cube cube = bone.cubes()[i];
                
                String id = String.format("%s_cube_%d", bone.name(), i);
                
                double[] origin = cube.origin() != null ? cube.origin() : new double[]{0, 0, 0};
                double[] size = cube.size() != null ? cube.size() : new double[]{1, 1, 1};
                double[] rotation = cube.rotation() != null ? cube.rotation() : new double[]{0, 0, 0};
                double[] pivot = cube.pivot() != null ? cube.pivot() : new double[]{0, 0, 0};
                
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
        double[] pivot = bone.pivot() != null ? bone.pivot() : new double[]{0, 0, 0};
        
        // 创建骨骼静态数据
        ClientBoneModelData.BoneStaticData boneStaticData = new ClientBoneModelData.BoneStaticData(
            bone.name(),
            parentBoneName,
            cubeStaticDataList,
            new Vec3((float) pivot[0], (float) pivot[1], (float) pivot[2]),
            new Vec3(0, 0, 0),
            new Vec3(1, 1, 1)
        );
        
        boneStaticDataMap.put(bone.name(), boneStaticData);
        
        // 记录父子关系
        if (parentBoneName != null) {
            childBoneMap.computeIfAbsent(parentBoneName, k -> new ArrayList<>()).add(bone.name());
        }
        
        // 递归处理子骨骼
        if (boneStructure.children() != null && !boneStructure.children().isEmpty()) {
            for (BoneStructure childBoneStructure : boneStructure.children().values()) {
                processBoneStructure(childBoneStructure, boneStaticDataMap, boneHierarchy, childBoneMap, bone.name());
            }
        }
    }
    
    /**
     * 注册模型集合
     */
    public void registerModelCollection(ClientModelCollection modelCollection) {
        ResourceLocation location = modelCollection.modelLocation();
        modelCollections.put(location, modelCollection);
        LOGGER.info("[ClientModelManager] Registered model collection: {} at {}", 
                modelCollection.modelName(), location);
    }
    
    /**
     * 注册骨骼模型数据
     */
    public void registerBoneModelData(ResourceLocation modelLocation, ClientBoneModelData boneModelData) {
        boneModelDataMap.put(modelLocation, boneModelData);
        LOGGER.info("[ClientModelManager] Registered bone model data for model: {}", modelLocation);
    }
    
    /**
     * 获取模型集合
     */
    public ClientModelCollection getModelCollection(ResourceLocation modelLocation) {
        ClientModelCollection collection = modelCollections.get(modelLocation);
        if (collection == null) {
            LOGGER.warn("[ClientModelManager] Model collection not found: {}", modelLocation);
        } else {
            LOGGER.debug("[ClientModelManager] Retrieved model collection: {}", modelLocation);
        }
        return collection;
    }
    
    /**
     * 获取骨骼模型数据
     */
    public ClientBoneModelData getBoneModelData(ResourceLocation modelLocation) {
        ClientBoneModelData data = boneModelDataMap.get(modelLocation);
        if (data == null) {
            LOGGER.warn("[ClientModelManager] Bone model data not found: {}", modelLocation);
        } else {
            LOGGER.debug("[ClientModelManager] Retrieved bone model data: {}", modelLocation);
        }
        return data;
    }
    
    /**
     * 检查模型是否已注册
     */
    public boolean hasModel(ResourceLocation modelLocation) {
        boolean has = modelCollections.containsKey(modelLocation);
        LOGGER.debug("[ClientModelManager] Checking model existence: {} = {}", modelLocation, has);
        return has;
    }
    
    /**
     * 清理所有模型数据
     */
    public void clear() {
        int modelCount = modelCollections.size();
        int boneDataCount = boneModelDataMap.size();
        modelCollections.clear();
        boneModelDataMap.clear();
        LOGGER.info("[ClientModelManager] Cleared {} model collections and {} bone model data entries", 
                modelCount, boneDataCount);
    }
    
    /**
     * 预留：从网络接收模型数据
     */
    public void receiveModelDataFromNetwork(ResourceLocation modelLocation, ClientBoneModelData boneModelData) {
        LOGGER.info("[ClientModelManager] Receiving model data from network: {}", modelLocation);
        registerBoneModelData(modelLocation, boneModelData);
        // TODO: 实现网络数据接收逻辑
    }
}
