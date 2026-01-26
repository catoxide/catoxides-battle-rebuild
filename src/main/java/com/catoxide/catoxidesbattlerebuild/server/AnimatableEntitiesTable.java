// [file name]: AnimatableEntitiesTable.java
package com.catoxide.catoxidesbattlerebuild.server;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.Tags;
import software.bernie.geckolib.GeckoLib;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.core.animation.AnimationProcessor;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动画实体表格，用于批量更新实体动画并计算变换矩阵
 */
public class AnimatableEntitiesTable {
    private static final AnimatableEntitiesTable INSTANCE = new AnimatableEntitiesTable();

    // 实体映射表
    private final Map<UUID, EnhancedEntityCollection> entityMap = new ConcurrentHashMap<>();

    // 按更新策略分组（可选，用于更复杂的更新策略）
    private final Map<UpdateStrategy, List<EnhancedEntityCollection>> strategyGroups = new ConcurrentHashMap<>();

    private AnimatableEntitiesTable() {}

    public static AnimatableEntitiesTable getInstance() {
        return INSTANCE;
    }

    /**
     * 增强版实体集合Record
     * 包含基础关联信息 + 动态动画数据
     */


    /**
     * 更新策略枚举
     */
    public enum UpdateStrategy {
        ACTIVE,     // 活跃实体（靠近玩家）
        IDLE,       // 空闲实体
        FAR         // 远离玩家
    }

    /**
     * 注册实体到表格中
     */
    public void registerEntity(Entity entity, ResourceLocation modelLocation) {
        if (!(entity instanceof GeoAnimatable)) return;

        // 创建增强版集合
        EnhancedEntityCollection collection = EnhancedEntityCollection.create(entity, modelLocation);

        // 存储到实体映射表
        entityMap.put(entity.getUUID(), collection);

        // 按更新策略分组（可选）
        UpdateStrategy strategy = determineUpdateStrategy(entity);
        strategyGroups.computeIfAbsent(strategy, k -> new ArrayList<>()).add(collection);

        GeckoLib.LOGGER.debug("Registered entity {} with strategy {}", entity, strategy);
    }

    /**
     * 从表格中移除实体
     */
    public void unregisterEntity(Entity entity) {
        EnhancedEntityCollection collection = entityMap.remove(entity.getUUID());
        if (collection != null) {
            // 从策略组中移除（如果需要）
            for (List<EnhancedEntityCollection> group : strategyGroups.values()) {
                group.remove(collection);
            }
            GeckoLib.LOGGER.debug("Unregistered entity {}", entity);
        }
    }

    /**
     * 更新所有实体
     */
    public void updateAll(float partialTick) {
        // 并行更新所有实体
        entityMap.values().parallelStream().forEach(collection -> {
            if (collection.isValid()) {
                updateEntity(collection, partialTick);
            }
        });

        // 可选：清理无效实体
        cleanupInvalidEntities();
    }

    /**
     * 按策略组更新实体（可选）
     */
    public void updateByStrategy(UpdateStrategy strategy, float partialTick) {
        List<EnhancedEntityCollection> group = strategyGroups.get(strategy);
        if (group != null) {
            group.parallelStream()
                    .filter(EnhancedEntityCollection::isValid)
                    .forEach(collection -> updateEntity(collection, partialTick));
        }
    }

    /**
     * 更新单个实体
     */
    private void updateEntity(EnhancedEntityCollection collection, float partialTick) {
        try {
            // 1. 更新动画状态
            Map<String, Matrix4f> boneMatrices = ServerGeoModelManager.getInstance()
                    .updateAnimationAndGetMatrices(
                            collection.modelLocation(),
                            (GeoAnimatable) collection.entity(),
                            partialTick
                    );

            // 2. 计算cube顶点
            Map<String, List<Vector3f>> cubeVertices = null;
            if (boneMatrices != null && !boneMatrices.isEmpty()) {
                cubeVertices = calculateCubeVertices(collection.modelLocation(), boneMatrices);
            }

            // 3. 更新实体集合（创建新的record实例）
            EnhancedEntityCollection updatedCollection = collection.withDynamicData(boneMatrices, cubeVertices);

            // 4. 替换旧的集合
            entityMap.put(collection.entityId(), updatedCollection);

            // 5. 更新策略组（如果需要）
            updateStrategyGroup(collection, updatedCollection);

        } catch (Exception e) {
            GeckoLib.LOGGER.error("Failed to update entity {}: {}", collection.entity(), e.getMessage());
        }
    }

    /**
     * 计算cube的顶点坐标
     */
    private Map<String, List<Vector3f>> calculateCubeVertices(
            ResourceLocation modelLocation,
            Map<String, Matrix4f> boneMatrices) {

        BakedGeoModel bakedModel = ServerGeoModelManager.getInstance()
                .getBakedModel(modelLocation);
        if (bakedModel == null) return null;

        Map<String, List<Vector3f>> cubeVertices = new HashMap<>();

        // 遍历所有骨骼
        for (GeoBone bone : bakedModel.getBones()) {
            Matrix4f boneMatrix = boneMatrices.get(bone.getName());
            if (boneMatrix == null) continue;

            // 遍历骨骼的所有cube
            for (GeoCube cube : bone.getCubes()) {
                List<Vector3f> vertices = transformCubeVertices(cube, boneMatrix);
                cubeVertices.put(EnhancedEntityCollection.getCubeKey(bone.getName(), cube), vertices);
            }
        }

        return cubeVertices;
    }

    /**
     * 变换cube的顶点
     */
    private List<Vector3f> transformCubeVertices(GeoCube cube, Matrix4f boneMatrix) {
        // 获取cube的8个顶点（局部坐标）
        Vector3f[] localVertices = getCubeLocalVertices(cube);
        List<Vector3f> transformed = new ArrayList<>(8);

        // 应用骨骼变换矩阵
        for (Vector3f vertex : localVertices) {
            Vector4f transformedVertex = boneMatrix.transform(new Vector4f(vertex, 1.0f));
            transformed.add(new Vector3f(
                    transformedVertex.x() / transformedVertex.w(),
                    transformedVertex.y() / transformedVertex.w(),
                    transformedVertex.z() / transformedVertex.w()
            ));
        }

        return transformed;
    }

    /**
     * 获取cube的8个局部顶点
     */
    private Vector3f[] getCubeLocalVertices(GeoCube cube) {
        float sizeX = (float)cube.size().x();
        float sizeY = (float)cube.size().y();
        float sizeZ = (float)cube.size().z();

        // 从原点偏移
        Vector3f origin = cube.pivot().toVector3f();
        float minX = origin.x() - sizeX / 2;
        float minY = origin.y();
        float minZ = origin.z() - sizeZ / 2;
        float maxX = minX + sizeX;
        float maxY = minY + sizeY;
        float maxZ = minZ + sizeZ;

        // 8个顶点
        return new Vector3f[] {
                new Vector3f(minX, minY, minZ), // 0
                new Vector3f(maxX, minY, minZ), // 1
                new Vector3f(maxX, minY, maxZ), // 2
                new Vector3f(minX, minY, maxZ), // 3
                new Vector3f(minX, maxY, minZ), // 4
                new Vector3f(maxX, maxY, minZ), // 5
                new Vector3f(maxX, maxY, maxZ), // 6
                new Vector3f(minX, maxY, maxZ)  // 7
        };
    }

    /**
     * 确定实体的更新策略（替代原来的优先级）
     */
    private UpdateStrategy determineUpdateStrategy(Entity entity) {
        // 玩家和Boss作为活跃实体
        if (entity instanceof Player || entity.getType().is(Tags.EntityTypes.BOSSES)) {
            return UpdateStrategy.ACTIVE;
        }
        // 玩家附近的实体
        else if (isNearPlayer(entity)) {
            return UpdateStrategy.ACTIVE;
        }
        // 远离玩家的实体
        else if (isFarFromPlayers(entity)) {
            return UpdateStrategy.FAR;
        }
        // 其他实体
        return UpdateStrategy.IDLE;
    }

    private boolean isNearPlayer(Entity entity) {
        return entity.level().players().stream()
                .anyMatch(player -> player.distanceToSqr(entity) < 256.0); // 16格内
    }

    private boolean isFarFromPlayers(Entity entity) {
        return entity.level().players().stream()
                .allMatch(player -> player.distanceToSqr(entity) > 1024.0); // 32格外
    }

    /**
     * 更新策略组中的实体引用
     */
    private void updateStrategyGroup(EnhancedEntityCollection oldCollection,
                                     EnhancedEntityCollection newCollection) {
        for (Map.Entry<UpdateStrategy, List<EnhancedEntityCollection>> entry : strategyGroups.entrySet()) {
            List<EnhancedEntityCollection> group = entry.getValue();
            int index = group.indexOf(oldCollection);
            if (index != -1) {
                group.set(index, newCollection);
                break;
            }
        }
    }

    /**
     * 清理无效实体
     */
    void cleanupInvalidEntities() {
        Iterator<Map.Entry<UUID, EnhancedEntityCollection>> iterator = entityMap.entrySet().iterator();
        while (iterator.hasNext()) {
            EnhancedEntityCollection collection = iterator.next().getValue();
            if (!collection.isValid()) {
                iterator.remove();
                // 同时从策略组中移除
                for (List<EnhancedEntityCollection> group : strategyGroups.values()) {
                    group.remove(collection);
                }
            }
        }
    }

    /**
     * 获取实体的cube顶点数据
     */
    public Map<String, List<Vector3f>> getEntityCubeVertices(UUID entityId) {
        EnhancedEntityCollection collection = entityMap.get(entityId);
        return collection != null ? collection.cubeVertices() : null;
    }

    /**
     * 获取实体的骨骼矩阵
     */
    public Map<String, Matrix4f> getEntityBoneMatrices(UUID entityId) {
        EnhancedEntityCollection collection = entityMap.get(entityId);
        return collection != null ? collection.boneMatrices() : null;
    }

    /**
     * 获取实体的动画处理器
     */
    public AnimationProcessor<GeoAnimatable> getEntityAnimationProcessor(UUID entityId) {
        EnhancedEntityCollection collection = entityMap.get(entityId);
        return collection != null ? collection.animationProcessor() : null;
    }

    /**
     * 获取实体集合（用于外部访问）
     */
    public EnhancedEntityCollection getEntityCollection(UUID entityId) {
        return entityMap.get(entityId);
    }

    /**
     * 获取表格统计信息
     */
    public TableStats getStats() {
        int totalEntities = entityMap.size();
        int activeCount = (int) entityMap.values().stream()
                .filter(EnhancedEntityCollection::isValid)
                .count();

        return new TableStats(totalEntities, activeCount, strategyGroups.size());
    }

    /**
     * 表格统计信息
     */
    public static class TableStats {
        public final int totalEntities;
        public final int activeEntities;
        public final int strategyGroups;

        public TableStats(int totalEntities, int activeEntities, int strategyGroups) {
            this.totalEntities = totalEntities;
            this.activeEntities = activeEntities;
            this.strategyGroups = strategyGroups;
        }
    }
}