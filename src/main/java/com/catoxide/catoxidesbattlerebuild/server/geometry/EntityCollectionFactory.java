package com.catoxide.catoxidesbattlerebuild.server.geometry;

import com.catoxide.catoxidesbattlerebuild.server.models.ModelCollection;
import com.catoxide.catoxidesbattlerebuild.server.models.ServerGeoModelManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import software.bernie.geckolib.GeckoLib;
import software.bernie.geckolib.core.animatable.GeoAnimatable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EntityCollection工厂类，负责创建和管理EntityCollection实例
 * 确保相同模型的EntityCollection共享同一个ModelCollection
 */
public class EntityCollectionFactory {
    private static final EntityCollectionFactory INSTANCE = new EntityCollectionFactory();

    // 缓存：模型位置 -> ModelCollection
    private final Map<ResourceLocation, ModelCollection> modelCollectionCache = new ConcurrentHashMap<>();

    // 缓存：实体UUID -> EntityCollection
    private final Map<UUID, EntityCollection> entityCollectionCache = new ConcurrentHashMap<>();

    private EntityCollectionFactory() {}

    public static EntityCollectionFactory getInstance() {
        return INSTANCE;
    }

    /**
     * 创建EntityCollection（主工厂方法）
     */
    public EntityCollection createEntityCollection(Entity entity, ResourceLocation modelLocation) {
        if (!(entity instanceof GeoAnimatable)) {
            GeckoLib.LOGGER.warn("Entity {} is not a GeoAnimatable", entity);
            return null;
        }

        UUID entityId = entity.getUUID();

        // 检查是否已存在
        EntityCollection existing = entityCollectionCache.get(entityId);
        if (existing != null) {
            return existing;
        }

        try {
            // 1. 获取或创建ModelCollection
            ModelCollection modelCollection = getOrCreateModelCollection(modelLocation);
            if (modelCollection == null) {
                throw new IllegalStateException("Failed to create ModelCollection for: " + modelLocation);
            }

            // 2. 创建EntityCollection
            EntityCollection entityCollection = EntityCollection.create(
                    entityId,
                    modelLocation,
                    modelCollection,
                    entity
            );

            // 3. 缓存
            entityCollectionCache.put(entityId, entityCollection);

            GeckoLib.LOGGER.debug("Created EntityCollection for entity {} with model {}", entity, modelLocation);
            return entityCollection;

        } catch (Exception e) {
            GeckoLib.LOGGER.error("Failed to create EntityCollection for entity {}: {}", entity, e.getMessage());
            return null;
        }
    }

    /**
     * 获取或创建ModelCollection
     */
    private ModelCollection getOrCreateModelCollection(ResourceLocation modelLocation) {
        // 检查缓存
        ModelCollection cached = modelCollectionCache.get(modelLocation);
        if (cached != null) {
            return cached;
        }

        try {
            // 从ServerGeoModelManager获取
            ModelCollection modelCollection = ServerGeoModelManager.getInstance()
                    .getModelCollection(modelLocation);

            if (modelCollection == null) {
                GeckoLib.LOGGER.error("ModelCollection not found for: {}", modelLocation);
                return null;
            }

            // 缓存
            modelCollectionCache.put(modelLocation, modelCollection);
            return modelCollection;

        } catch (Exception e) {
            GeckoLib.LOGGER.error("Failed to get ModelCollection for {}: {}", modelLocation, e.getMessage());
            return null;
        }
    }

    /**
     * 更新EntityCollection的动态数据
     */
    public EntityCollection updateEntityCollection(
            EntityCollection collection,
            Map<String, org.joml.Matrix4f> boneMatrices,
            Map<String, java.util.List<org.joml.Vector3f>> cubeVertices
    ) {
        // 将Vector3f转换为Vec3以保证精度
        Map<String, java.util.List<net.minecraft.world.phys.Vec3>> convertedVertices = null;
        if (cubeVertices != null) {
            convertedVertices = new java.util.concurrent.ConcurrentHashMap<>();
            for (Map.Entry<String, java.util.List<org.joml.Vector3f>> entry : cubeVertices.entrySet()) {
                java.util.List<net.minecraft.world.phys.Vec3> vec3List = new java.util.ArrayList<>();
                for (org.joml.Vector3f vector3f : entry.getValue()) {
                    vec3List.add(new net.minecraft.world.phys.Vec3(
                        vector3f.x(),
                        vector3f.y(),
                        vector3f.z()
                    ));
                }
                convertedVertices.put(entry.getKey(), vec3List);
            }
        }
        
        EntityCollection updated = collection.withDynamicData(boneMatrices, convertedVertices);
        entityCollectionCache.put(collection.entityId(), updated);
        return updated;
    }

    /**
     * 根据实体ID获取EntityCollection
     */
    public EntityCollection getEntityCollection(UUID entityId) {
        return entityCollectionCache.get(entityId);
    }

    /**
     * 移除EntityCollection
     */
    public void removeEntityCollection(UUID entityId) {
        EntityCollection removed = entityCollectionCache.remove(entityId);
        if (removed != null) {
            GeckoLib.LOGGER.debug("Removed EntityCollection for entity ID: {}", entityId);
        }
    }

    /**
     * 根据实体移除
     */
    public void removeEntityCollection(Entity entity) {
        removeEntityCollection(entity.getUUID());
    }

    /**
     * 检查实体是否存在
     */
    public boolean containsEntity(UUID entityId) {
        return entityCollectionCache.containsKey(entityId);
    }

    /**
     * 获取所有EntityCollection
     */
    public java.util.Collection<EntityCollection> getAllCollections() {
        return entityCollectionCache.values();
    }

    /**
     * 获取统计信息
     */
    public FactoryStats getStats() {
        return new FactoryStats(
                entityCollectionCache.size(),
                modelCollectionCache.size()
        );
    }

    /**
     * 清理无效的EntityCollection
     */
    public void cleanupInvalidCollections() {
        entityCollectionCache.entrySet().removeIf(entry -> {
            if (entry.getValue() == null || !entry.getValue().isValid()) {
                GeckoLib.LOGGER.debug("Cleaning up invalid EntityCollection for entity ID: {}", entry.getKey());
                return true;
            }
            return false;
        });
    }

    /**
     * 清除所有缓存
     */
    public void clearCache() {
        modelCollectionCache.clear();
        entityCollectionCache.clear();
        GeckoLib.LOGGER.debug("EntityCollectionFactory cache cleared");
    }

    /**
     * 工厂统计信息
     */
    public static class FactoryStats {
        public final int entityCollections;
        public final int modelCollections;

        public FactoryStats(int entityCollections, int modelCollections) {
            this.entityCollections = entityCollections;
            this.modelCollections = modelCollections;
        }

        @Override
        public String toString() {
            return String.format("EntityCollections: %d, ModelCollections: %d",
                    entityCollections, modelCollections);
        }
    }
}