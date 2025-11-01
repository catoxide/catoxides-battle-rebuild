package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import java.util.*;

/**
 * 模型管理器 - 负责管理多个几何模型
 */
public class ModelManager {
    private final Map<String, GeometryModel> loadedModels = new HashMap<>();
    private final ModelLoader modelLoader;

    public ModelManager() {
        this.modelLoader = new ModelLoader();
    }

    /**
     * 加载模型
     */
    public GeometryModel loadModel(String modelPath) {
        return loadedModels.computeIfAbsent(modelPath, path -> {
            System.out.println("加载模型: " + path);
            return modelLoader.loadGeometryModel(path);
        });
    }

    /**
     * 卸载模型
     */
    public void unloadModel(String modelPath) {
        loadedModels.remove(modelPath);
        System.out.println("卸载模型: " + modelPath);
    }

    /**
     * 获取已加载的模型
     */
    public GeometryModel getModel(String modelPath) {
        GeometryModel model = loadedModels.get(modelPath);
        if (model == null) {
            throw new IllegalStateException("模型未加载: " + modelPath);
        }
        return model;
    }

    /**
     * 检查模型是否已加载
     */
    public boolean isModelLoaded(String modelPath) {
        return loadedModels.containsKey(modelPath);
    }

    /**
     * 获取所有已加载的模型路径
     */
    public Set<String> getLoadedModelPaths() {
        return Collections.unmodifiableSet(loadedModels.keySet());
    }

    /**
     * 清理所有模型
     */
    public void clear() {
        int count = loadedModels.size();
        loadedModels.clear();
        System.out.println("清理了 " + count + " 个模型");
    }
}
