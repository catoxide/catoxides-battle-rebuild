package com.catoxide.catoxidesbattlerebuild.client.models;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

public class ModelDataManager {
    private static final ModelDataManager INSTANCE = new ModelDataManager();

    private final Map<ResourceLocation, ClientBoneModelData> modelDataCache = new HashMap<>();

    private ModelDataManager() {}

    public static ModelDataManager getInstance() {
        return INSTANCE;
    }

    public void cacheModelData(ResourceLocation modelLocation, ClientBoneModelData modelData) {
        modelDataCache.put(modelLocation, modelData);
        LogManager.clientDebug("ModelDataManager", "Cached model data: {}", modelLocation);
    }

    public ClientBoneModelData getModelData(ResourceLocation modelLocation) {
        ClientBoneModelData data = modelDataCache.get(modelLocation);
        LogManager.clientDebug("ModelDataManager", "Retrieved model data: {} (exists={})",
                modelLocation, data != null);
        return data;
    }

    public boolean hasModelData(ResourceLocation modelLocation) {
        return modelDataCache.containsKey(modelLocation);
    }

    public void clearCache() {
        modelDataCache.clear();
        LogManager.clientDebug("ModelDataManager", "Cache cleared");
    }
}
