// [file name]: AnimatableEntitiesTable.java
package com.catoxide.catoxidesbattlerebuild.server;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.Tags;
import software.bernie.geckolib.GeckoLib;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animation.AnimationProcessor;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动画实体表格，用于批量更新实体动画并计算变换矩阵
 */
public class ServerEntityManager {
    private static final ServerEntityManager INSTANCE = new ServerEntityManager();

    // 实体映射表
    private final Map<UUID, EntityCollection> entityMap = new ConcurrentHashMap<>();

    // 按更新策略分组（可选，用于更复杂的更新策略）
    private final Map<UpdateStrategy, List<EntityCollection>> strategyGroups = new ConcurrentHashMap<>();

    private ServerEntityManager() {}

    public static ServerEntityManager getInstance() {
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

        // 使用EntityCollectionFactory创建EntityCollection
        EntityCollection collection = EntityCollectionFactory.getInstance()
                .createEntityCollection(entity, modelLocation);

        if (collection == null) {
            GeckoLib.LOGGER.error("Failed to create EntityCollection for entity {}", entity);
            return;
        }

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
        UUID entityId = entity.getUUID();
        EntityCollection collection = entityMap.remove(entityId);

        if (collection != null) {
            // 从策略组中移除（如果需要）
            for (List<EntityCollection> group : strategyGroups.values()) {
                group.remove(collection);
            }
            // 从EntityCollectionFactory移除
            EntityCollectionFactory.getInstance().removeEntityCollection(entityId);
            GeckoLib.LOGGER.debug("Unregistered entity {}", entity);
        }
    }

    /**
     * 更新所有实体
     */
    public void updateAll(float partialTick) {
        long startTime = System.nanoTime();
 
        // 并行更新所有实体 
        entityMap.entrySet().parallelStream().forEach(entry -> { 
            UUID entityId = entry.getKey(); 
            EntityCollection collection = entry.getValue(); 
 
            try { 
                if (collection.isValid()) {
                    updateEntity(collection, partialTick);
                }
            } catch (Exception e) { 
                GeckoLib.LOGGER.error("Failed to update entity {}: {}", 
                        entityId, e.getMessage()); 
            } 
        }); 
 
        // 清理无效实体 
        cleanupInvalidEntities(); 
 
        // 性能监控 
        updatePerformanceStats(startTime); 
    }

    /**
     * 按策略组更新实体（可选）
     */
    public void updateByStrategy(UpdateStrategy strategy, float partialTick) {
        List<EntityCollection> group = strategyGroups.get(strategy);
        if (group != null) {
            group.parallelStream()
                    .filter(EntityCollection::isValid)
                    .forEach(collection -> updateEntity(collection, partialTick));
        }
    }

    /**
     * 更新单个实体
     */
    private void updateEntity(EntityCollection collection, float partialTick) {
        try {
            // Debug：输出更新开始信息
            System.out.println("[ServerEntityManager] Updating entity: " + collection.entityId());
            
            // 1. 获取动画处理器
            var animationProcessor = collection.animationProcessor();
            if (animationProcessor == null) {
                System.out.println("[ServerEntityManager] Warning: Animation processor is null for entity: " + collection.entity());
                return;
            }
            System.out.println("[ServerEntityManager] Animation processor found for entity: " + collection.entityId());

            // 2. 更新动画状态
            Map<String, Matrix4f> boneMatrices = updateAnimationAndGetMatrices(
                    animationProcessor,
                    collection.modelLocation(),
                    (GeoAnimatable) collection.entity(),
                    partialTick
            );

            System.out.println("[ServerEntityManager] Bone matrices after update: " + 
                    (boneMatrices != null ? boneMatrices.size() + " bones" : "null"));

            // 3. 计算cube顶点
            Map<String, List<Vector3f>> cubeVertices = null;
            if (boneMatrices != null && !boneMatrices.isEmpty()) {
                cubeVertices = calculateCubeVertices(collection.modelLocation(), boneMatrices);
                System.out.println("[ServerEntityManager] Cube vertices calculated: " + 
                        (cubeVertices != null ? cubeVertices.size() + " cubes" : "null"));
            }

            // 4. 更新实体集合（创建新的record实例）
            EntityCollection updatedCollection = collection.withDynamicData(boneMatrices, cubeVertices);

            // 5. 替换旧的集合
            entityMap.put(collection.entityId(), updatedCollection);
            
            // 6. 更新EntityCollectionFactory缓存
            EntityCollectionFactory.getInstance().updateEntityCollection(
                collection, boneMatrices, cubeVertices
            );
            System.out.println("[ServerEntityManager] Entity collection updated successfully");

            // 7. 更新策略组（如果需要）
            updateStrategyGroup(collection, updatedCollection);

        } catch (Exception e) {
            System.out.println("[ServerEntityManager] Error updating entity " + collection.entityId() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private Map<String, Matrix4f> updateAnimationAndGetMatrices(
            AnimationProcessor<GeoAnimatable> animationProcessor,
            ResourceLocation modelLocation,
            GeoAnimatable animatable,
            float partialTick
    ) {
        try {
            // 1. 确保管理器已初始化
            ensureServerGeoModelManagerInitialized();

            // 2. 更新动画
            ServerGeoModelManager.getInstance().updateAnimation(
                    modelLocation,
                    animatable,
                    partialTick
            );

            // 3. 获取骨骼矩阵（这里需要从动画处理器中提取）
            return extractBoneMatrices(animationProcessor);

        } catch (Exception e) {
            GeckoLib.LOGGER.error("Failed to update animation for {}: {}", modelLocation, e.getMessage(), e);
            return null;
        }
    }

    private Map<String, Matrix4f> extractBoneMatrices(AnimationProcessor<GeoAnimatable> animationProcessor) {
        Map<String, Matrix4f> boneMatrices = new HashMap<>();

        try {
            // 获取所有已注册的骨骼
            Collection<CoreGeoBone> bones = animationProcessor.getRegisteredBones();
            
            // Debug：输出骨骼数量
            System.out.println("[ServerEntityManager] Extracting bone matrices. Registered bones count: " + 
                    (bones != null ? bones.size() : "null"));

            // 遍历所有骨骼并获取它们的矩阵
            for (CoreGeoBone coreBone : bones) {
                // 将 CoreGeoBone 转换为 GeoBone
                if (coreBone instanceof GeoBone bone) {
                    String boneName = bone.getName();

                    // 使用getPositionMatrix()获取模型空间的变换矩阵
                    // getWorldSpaceMatrix()在服务器端返回的是局部空间矩阵
                    Matrix4f matrix = bone.getLocalSpaceMatrix();
                    if (matrix != null) {
                        // 创建矩阵的副本
                        boneMatrices.put(boneName, new Matrix4f(matrix));
                        
                        // Debug：输出矩阵的平移部分
                        Vector3f translation = new Vector3f();
                        matrix.getTranslation(translation);
                        System.out.println("[ServerEntityManager] Extracted matrix for bone: " + boneName + 
                                ", Translation: (" + translation.x + ", " + translation.y + ", " + translation.z + ")");
                    } else {
                        System.out.println("[ServerEntityManager] Warning: matrix is null for bone: " + boneName);
                    }
                }
            }

            System.out.println("[ServerEntityManager] Total matrices extracted: " + boneMatrices.size());

        } catch (Exception e) {
            System.out.println("[ServerEntityManager] Error extracting bone matrices: " + e.getMessage());
            e.printStackTrace();
        }

        return boneMatrices.isEmpty() ? null : boneMatrices;
    }

    private void ensureServerGeoModelManagerInitialized() {
        // 这个方法确保管理器在使用前已经初始化
        // 在实际项目中，可能需要在合适的时机调用initialize方法
        if (!isServerGeoModelManagerInitialized()) {
            GeckoLib.LOGGER.warn("ServerGeoModelManager not initialized, attempting to initialize...");
            // 这里可能需要传递ResourceManager和Executor
            // ServerGeoModelManager.getInstance().initialize(resourceManager, executor);
        }
    }
    private boolean isServerGeoModelManagerInitialized() {
        try {
            // 尝试获取一个模型来检查是否初始化
            ServerGeoModelManager.getInstance().getBakedModel(new ResourceLocation("test", "test"));
            return true;
        } catch (IllegalStateException e) {
            return false;
        } catch (Exception e) {
            return true; // 其他异常说明已初始化但模型不存在
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
        for (GeoBone bone : bakedModel.topLevelBones()) {
            Matrix4f boneMatrix = boneMatrices.get(bone.getName());
            if (boneMatrix == null) continue;

            for (GeoCube cube : bone.getCubes()) {
                List<Vector3f> vertices = transformCubeVertices(cube, boneMatrix);
                cubeVertices.put(EntityCollection.getCubeKey(bone.getName(), cube), vertices);
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
    private void updateStrategyGroup(EntityCollection oldCollection,
                                     EntityCollection newCollection) {
        for (Map.Entry<UpdateStrategy, List<EntityCollection>> entry : strategyGroups.entrySet()) {
            List<EntityCollection> group = entry.getValue();
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
        Iterator<Map.Entry<UUID, EntityCollection>> iterator = entityMap.entrySet().iterator();
        while (iterator.hasNext()) {
            EntityCollection collection = iterator.next().getValue();
            if (!collection.isValid()) {
                iterator.remove();
                // 同时从策略组中移除
                for (List<EntityCollection> group : strategyGroups.values()) {
                    group.remove(collection);
                }
            }
        }
    }

    /**
     * 更新性能统计信息
     */
    private void updatePerformanceStats(long startTime) {
        long endTime = System.nanoTime();
        long duration = endTime - startTime;
        double durationMs = duration / 1_000_000.0;
        
        // 记录性能信息（每秒记录一次或根据需要调整）
        GeckoLib.LOGGER.debug("updateAll completed in {} ms ({} entities)", 
                String.format("%.2f", durationMs), 
                entityMap.size());
    }

    /**
     * 获取实体的cube顶点数据
     */
    public Map<String, List<Vector3f>> getEntityCubeVertices(UUID entityId) {
        EntityCollection collection = entityMap.get(entityId);
        return collection != null ? collection.cubeVertices() : null;
    }

    /**
     * 获取实体的骨骼矩阵
     */
    public Map<String, Matrix4f> getEntityBoneMatrices(UUID entityId) {
        EntityCollection collection = entityMap.get(entityId);
        return collection != null ? collection.boneMatrices() : null;
    }

    /**
     * 获取实体的动画处理器
     */
    public AnimationProcessor<GeoAnimatable> getEntityAnimationProcessor(UUID entityId) {
        EntityCollection collection = entityMap.get(entityId);
        return collection != null ? collection.animationProcessor() : null;
    }

    /**
     * 获取实体集合（用于外部访问）
     */
    public EntityCollection getEntityCollection(UUID entityId) {
        return entityMap.get(entityId);
    }

    /**
     * 获取表格统计信息
     */
    public TableStats getStats() {
        int totalEntities = entityMap.size();
        int activeCount = (int) entityMap.values().stream()
                .filter(EntityCollection::isValid)
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