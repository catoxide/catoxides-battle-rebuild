package com.catoxide.catoxidesbattlerebuild.client.geometry;

import com.catoxide.catoxidesbattlerebuild.client.models.ClientModelCollection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端实体集合类
 * 存储实体的动态数据和骨骼集合
 * 对齐服务端EntityCollection
 */
public record ClientEntityCollection(
        UUID entityUuid,                                        // 实体UUID
        Vec3 modelPosition,                                     // 模型位置
        ClientModelCollection modelCollection,                 // 模型集合引用
        Entity entityRef,                                      // 实体引用
        DynamicData dynamicData                                // 动态数据
) {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientEntityCollection.class);
    
    /**
     * 动态数据类
     * 存储实体的实时变换数据
     */
    public static class DynamicData {
        // 骨骼变换矩阵映射（骨骼名 -> 世界变换矩阵）
        private final Map<String, Matrix4f> boneTransforms;
        
        // Cube顶点数据映射（骨骼名 -> 立方体ID -> 世界顶点列表）
        private final Map<String, Map<String, List<Vec3>>> cubeVertices;
        
        public DynamicData() {
            this.boneTransforms = new ConcurrentHashMap<>();
            this.cubeVertices = new ConcurrentHashMap<>();
        }
        
        public Map<String, Matrix4f> getBoneTransforms() {
            return new ConcurrentHashMap<>(boneTransforms);
        }
        
        public Map<String, Map<String, List<Vec3>>> getCubeVertices() {
            Map<String, Map<String, List<Vec3>>> copy = new ConcurrentHashMap<>();
            cubeVertices.forEach((boneName, cubes) -> {
                Map<String, List<Vec3>> cubesCopy = new ConcurrentHashMap<>();
                cubes.forEach((cubeId, vertices) -> {
                    cubesCopy.put(cubeId, new ArrayList<>(vertices));
                });
                copy.put(boneName, cubesCopy);
            });
            return copy;
        }
        
        public void setBoneTransform(String boneName, Matrix4f transform) {
            boneTransforms.put(boneName, transform);
        }
        
        public void setCubeVertices(String boneName, String cubeId, List<Vec3> vertices) {
            cubeVertices.computeIfAbsent(boneName, k -> new ConcurrentHashMap<>())
                        .put(cubeId, new ArrayList<>(vertices));
        }
    }

    /**
     * 创建ClientEntityCollection实例
     */
    public static ClientEntityCollection create(
            UUID entityUuid,
            Vec3 modelPosition,
            ClientModelCollection modelCollection,
            Entity entityRef) {
        LOGGER.info("[ClientEntityCollection] Creating entity collection: uuid={}, model={}", 
                entityUuid, modelCollection.modelName());
        return new ClientEntityCollection(
            entityUuid,
            modelPosition,
            modelCollection,
            entityRef,
            new DynamicData()
        );
    }

    /**
     * 创建包含动态数据的ClientEntityCollection实例
     */
    public ClientEntityCollection withDynamicData(DynamicData dynamicData) {
        LOGGER.debug("[ClientEntityCollection] Creating entity with dynamic data: uuid={}", entityUuid);
        return new ClientEntityCollection(
            entityUuid,
            modelPosition,
            modelCollection,
            entityRef,
            dynamicData
        );
    }

    /**
     * 检查实体是否有效
     */
    public boolean isValid() {
        boolean valid = entityUuid != null && modelCollection != null && entityRef != null && entityRef.isAlive();
        LOGGER.debug("[ClientEntityCollection] Checking entity validity: uuid={}, valid={}", entityUuid, valid);
        return valid;
    }

    /**
     * 获取骨骼变换矩阵
     */
    public Matrix4f getBoneTransform(String boneName) {
        Matrix4f transform = dynamicData.getBoneTransforms().get(boneName);
        if (transform == null) {
            LOGGER.warn("[ClientEntityCollection] Bone transform not found: boneName={}, entityUuid={}", 
                    boneName, entityUuid);
        }
        return transform;
    }

    /**
     * 获取Cube顶点数据
     */
    public List<Vec3> getCubeVertices(String boneName, String cubeId) {
        Map<String, List<Vec3>> cubes = dynamicData.getCubeVertices().get(boneName);
        if (cubes == null) {
            LOGGER.warn("[ClientEntityCollection] Bone not found in cube vertices: boneName={}, entityUuid={}", 
                    boneName, entityUuid);
            return List.of();
        }
        List<Vec3> vertices = cubes.get(cubeId);
        if (vertices == null) {
            LOGGER.warn("[ClientEntityCollection] Cube not found: cubeId={}, boneName={}, entityUuid={}", 
                    cubeId, boneName, entityUuid);
            return List.of();
        }
        return new ArrayList<>(vertices);
    }

    /**
     * 获取所有骨骼名称
     */
    public List<String> getAllBoneNames() {
        List<String> boneNames = new ArrayList<>(dynamicData.getBoneTransforms().keySet());
        LOGGER.debug("[ClientEntityCollection] Getting all bone names: count={}, entityUuid={}", 
                boneNames.size(), entityUuid);
        return boneNames;
    }
}
