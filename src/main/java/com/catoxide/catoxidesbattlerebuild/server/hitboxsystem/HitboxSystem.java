package com.catoxide.catoxidesbattlerebuild.server.hitboxsystem;

import com.catoxide.catoxidesbattlerebuild.server.EntityCollection;
import com.catoxide.catoxidesbattlerebuild.server.EntityCollectionFactory;
import com.catoxide.catoxidesbattlerebuild.server.MatrixTransformer;
import net.minecraft.world.entity.Entity;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import software.bernie.geckolib.GeckoLib;
import software.bernie.geckolib.cache.object.GeoBone;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 模块化受击系统 - 基于EntityCollection和Geobone
 */
public class HitboxSystem {
    private static final HitboxSystem INSTANCE = new HitboxSystem();

    // 实体ID -> 骨骼名 -> 受击盒组件
    private final Map<UUID, Map<String, BoneHitboxComponent>> entityHitboxes =
            new ConcurrentHashMap<>();

    // 配置注册表
    private final BoneHitboxRegistry configRegistry = BoneHitboxRegistry.getInstance();

    // 碰撞检测器
    private final OBBCollisionDetector collisionDetector =
            OBBCollisionDetector.getInstance();

    // 性能监控
    private long lastUpdateTime = System.currentTimeMillis();
    private int framesProcessed = 0;
    private float averageUpdateTime = 0;

    private HitboxSystem() {}

    public static HitboxSystem getInstance() {
        return INSTANCE;
    }

    /**
     * 注册实体到受击系统
     */
    public void registerEntity(Entity entity, ResourceLocation modelLocation) {
        UUID entityId = entity.getUUID();

        // 检查是否已注册
        if (entityHitboxes.containsKey(entityId)) {
            GeckoLib.LOGGER.debug("Entity {} already registered in HitboxSystem", entityId);
            return;
        }

        try {
            // 获取EntityCollection
            EntityCollection collection = EntityCollectionFactory.getInstance()
                    .getEntityCollection(entityId);

            // 创建骨骼受击盒
            Map<String, BoneHitboxComponent> boneHitboxes =
                    createBoneHitboxes(entityId, collection);

            entityHitboxes.put(entityId, boneHitboxes);

            GeckoLib.LOGGER.debug("Registered entity {} with {} bone hitboxes",
                    entityId, boneHitboxes.size());

        } catch (Exception e) {
            GeckoLib.LOGGER.error("Failed to register entity {} in HitboxSystem: {}",
                    entityId, e.getMessage(), e);
        }
    }

    /**
     * 为实体创建骨骼受击盒
     */
    private Map<String, BoneHitboxComponent> createBoneHitboxes(
            UUID entityId, EntityCollection collection) {

        Map<String, BoneHitboxComponent> hitboxes = new ConcurrentHashMap<>();

        // 获取配置
        HitboxConfig config = configRegistry.getConfig(collection.modelLocation());

        if (config == null) {
            config = configRegistry.getDefaultConfig();
        }

        // 从ModelCollection获取骨骼信息并创建受击盒
        var bakedModel = collection.bakedModel();

        if (bakedModel != null) {
            // 递归遍历所有骨骼
            for (var bone : bakedModel.topLevelBones()) {
                traverseBoneTree(entityId,bone, config, hitboxes);
            }
        }

        return hitboxes;
    }

    /**
     * 递归遍历骨骼树
     */
    private void traverseBoneTree(UUID entityId,GeoBone bone, HitboxConfig config,
                                  Map<String, BoneHitboxComponent> hitboxes) {
        // 处理当前骨骼
        createHitboxForBone(entityId,bone.getName(), config, hitboxes);

        // 递归处理所有子骨骼
        for (GeoBone childBone : bone.getChildBones()) {
            traverseBoneTree(entityId,childBone, config, hitboxes);
        }
    }

    /**
     * 为单个骨骼创建受击盒
     */
    private void createHitboxForBone(UUID entityId, String boneName,
                                     HitboxConfig config,
                                     Map<String, BoneHitboxComponent> hitboxes) {

        // 获取骨骼特定配置
        HitboxConfig.BoneConfig boneConfig = config.getBoneConfig(boneName);

        // 如果没有找到特定配置，使用默认配置
        if (boneConfig == null) {
            // 尝试使用默认的default_bone配置
            boneConfig = config.getBoneConfig("default_bone");

            // 如果连默认配置都没有，创建一个基本的配置
            if (boneConfig == null) {
                boneConfig = new HitboxConfig.BoneConfig(
                        boneName,
                        new Vector3f(0, 0, 0),
                        new Vector3f(0.2f, 0.2f, 0.2f),
                        new Quaternionf(),
                        true  // 默认启用
                ).setDamageMultiplier(1.0f);
            }
        }

        // 默认总是创建hitbox（除非显式禁用）
        if (boneConfig.isEnabled()) {
            BoneHitboxComponent hitbox = new BoneHitboxComponent(
                    entityId,
                    boneName,
                    boneConfig.getCenter(),
                    boneConfig.getSize(),
                    boneConfig.getOrientation()
            );

            hitbox.setDamageMultiplier(boneConfig.getDamageMultiplier());
            hitbox.setCritical(boneConfig.isCritical());
            hitbox.setArmored(boneConfig.isArmored());

            hitboxes.put(boneName, hitbox);
        }
    }

    /**
     * 更新所有实体的受击盒变换
     */
    public void updateHitboxes(float partialTick) {
        long startTime = System.nanoTime();

        // 并行更新所有实体
        entityHitboxes.entrySet().parallelStream().forEach(entry -> {
            UUID entityId = entry.getKey();
            Map<String, BoneHitboxComponent> hitboxes = entry.getValue();

            try {
                updateEntityHitboxes(entityId, hitboxes, partialTick);
            } catch (Exception e) {
                GeckoLib.LOGGER.error("Failed to update hitboxes for entity {}: {}",
                        entityId, e.getMessage());
            }
        });

        // 清理无效实体
        cleanupInvalidEntities();

        // 性能监控
        updatePerformanceStats(startTime);
    }

    /**
     * 更新单个实体的所有受击盒
     */
    private void updateEntityHitboxes(UUID entityId,
                                      Map<String, BoneHitboxComponent> hitboxes,
                                      float partialTick) {

        // 获取EntityCollection
        EntityCollection collection = EntityCollectionFactory.getInstance()
                .getEntityCollection(entityId);

        if (collection == null || !collection.isValid()) {
            // 标记为需要清理
            hitboxes.values().forEach(hb -> hb.setActive(false));
            return;
        }

        // 获取骨骼矩阵
        Map<String, Matrix4f> boneMatrices = collection.boneMatrices();
        if (boneMatrices == null) return;

        // 更新每个受击盒的变换
        for (Map.Entry<String, BoneHitboxComponent> entry : hitboxes.entrySet()) {
            String boneName = entry.getKey();
            BoneHitboxComponent hitbox = entry.getValue();

            Matrix4f boneMatrix = boneMatrices.get(boneName);
            if (boneMatrix != null) {
                // 应用实体位置和旋转
                Entity entity = collection.entity();
                Matrix4f entityMatrix = new Matrix4f()
                        .translate((float)entity.getX(), (float)entity.getY(), (float)entity.getZ())
                        .rotateY((float)Math.toRadians(-entity.getYRot()));

                Matrix4f finalMatrix = entityMatrix.mul(boneMatrix, new Matrix4f());
                hitbox.updateWorldTransform(finalMatrix);
            }
        }
    }

    /**
     * 检测射线与所有实体的碰撞
     */
    public Optional<HitResult> raycastAll(Vector3f rayOrigin, Vector3f rayDirection,
                                          float maxDistance) {
        List<HitResult> hits = new ArrayList<>();

        for (Map.Entry<UUID, Map<String, BoneHitboxComponent>> entry :
                entityHitboxes.entrySet()) {

            for (BoneHitboxComponent hitbox : entry.getValue().values()) {
                if (!hitbox.isActive()) continue;

                Float distance = collisionDetector.raycastOBB(
                        rayOrigin, rayDirection, hitbox);

                if (distance != null && distance <= maxDistance) {
                    Vector3f hitPoint = rayDirection.mul(distance, new Vector3f())
                            .add(rayOrigin);

                    hits.add(new HitResult(
                            hitbox,
                            hitPoint,
                            distance,
                            rayDirection,
                            "raycast"
                    ));
                }
            }
        }

        // 返回最近的命中
        return hits.stream()
                .min(Comparator.comparing(HitResult::getDistance));
    }

    /**
     * 检测两个实体之间的碰撞
     */
    public List<HitResult> checkEntityCollision(UUID entity1Id, UUID entity2Id) {
        List<HitResult> results = new ArrayList<>();

        Map<String, BoneHitboxComponent> hitboxes1 = entityHitboxes.get(entity1Id);
        Map<String, BoneHitboxComponent> hitboxes2 = entityHitboxes.get(entity2Id);

        if (hitboxes1 == null || hitboxes2 == null) return results;

        // 检测所有可能的碰撞对
        for (BoneHitboxComponent hb1 : hitboxes1.values()) {
            if (!hb1.isActive()) continue;

            for (BoneHitboxComponent hb2 : hitboxes2.values()) {
                if (!hb2.isActive()) continue;

                if (collisionDetector.testOBBOBB(hb1, hb2)) {
                    // 计算碰撞点（近似为两个中心的中间点）
                    Vector3f hitPoint = hb1.getWorldCenter()
                            .add(hb2.getWorldCenter(), new Vector3f())
                            .mul(0.5f);

                    results.add(new HitResult(
                            hb1,
                            hitPoint,
                            0,
                            new Vector3f(),
                            "entity_collision"
                    ));
                }
            }
        }

        return results;
    }

    /**
     * 处理受击
     */
    public void processHit(HitResult hitResult, float damage, String damageType) {
        BoneHitboxComponent hitbox = hitResult.getHitbox();

        // 应用伤害乘数
        float finalDamage = damage * hitbox.getDamageMultiplier();

        // 记录受击
        hitbox.recordHit(finalDamage, damageType);

        // 触发事件
        triggerHitEvents(hitResult, finalDamage, damageType);

        // 应用击退、效果等
        applyHitEffects(hitResult, finalDamage);
    }

    /**
     * 应用受击效果
     */
    private void applyHitEffects(HitResult hitResult, float damage) {
        BoneHitboxComponent hitbox = hitResult.getHitbox();

        // 根据受击盒类型应用不同效果
        if (hitbox.isCritical()) {
            // 暴击效果：额外伤害、音效、粒子
        }

        if (hitbox.isArmored()) {
            // 装甲效果：减伤、火花粒子
            damage *= 0.5f; // 50%减伤
        }

        // TODO: 实际应用到实体生命值
        // Entity entity = getEntityById(hitbox.getEntityId());
        // if (entity instanceof LivingEntity living) {
        //     living.hurt(DamageSource.GENERIC, damage);
        // }
    }

    /**
     * 触发受击事件
     */
    private void triggerHitEvents(HitResult hitResult, float damage, String damageType) {
        // TODO: 实现事件系统
        // MinecraftForge.EVENT_BUS.post(new BoneHitEvent(hitResult, damage, damageType));
    }

    /**
     * 清理无效实体
     */
    private void cleanupInvalidEntities() {
        Iterator<UUID> iterator = entityHitboxes.keySet().iterator();

        while (iterator.hasNext()) {
            UUID entityId = iterator.next();

            EntityCollection collection = EntityCollectionFactory.getInstance()
                    .getEntityCollection(entityId);

            if (collection == null || !collection.isValid()) {
                iterator.remove();
                GeckoLib.LOGGER.debug("Removed invalid entity from HitboxSystem: {}", entityId);
            }
        }
    }

    /**
     * 从受击系统移除实体
     */
    public void unregisterEntity(UUID entityId) {
        Map<String, BoneHitboxComponent> removed = entityHitboxes.remove(entityId);
        if (removed != null) {
            GeckoLib.LOGGER.debug("Unregistered entity from HitboxSystem: {}", entityId);
        }
    }

    /**
     * 获取实体的受击盒
     */
    public Collection<BoneHitboxComponent> getEntityHitboxes(UUID entityId) {
        Map<String, BoneHitboxComponent> hitboxes = entityHitboxes.get(entityId);
        return hitboxes != null ? hitboxes.values() : Collections.emptyList();
    }

    /**
     * 获取特定骨骼的受击盒
     */
    public Optional<BoneHitboxComponent> getBoneHitbox(UUID entityId, String boneName) {
        Map<String, BoneHitboxComponent> hitboxes = entityHitboxes.get(entityId);
        return hitboxes != null ?
                Optional.ofNullable(hitboxes.get(boneName)) :
                Optional.empty();
    }

    /**
     * 更新性能统计
     */
    private void updatePerformanceStats(long startTime) {
        long endTime = System.nanoTime();
        long elapsedNs = endTime - startTime;
        float elapsedMs = elapsedNs / 1_000_000.0f;

        framesProcessed++;
        averageUpdateTime = (averageUpdateTime * (framesProcessed - 1) + elapsedMs) / framesProcessed;

        // 每60帧重置一次统计
        if (framesProcessed >= 60) {
            framesProcessed = 0;
            averageUpdateTime = 0;
        }
    }

    /**
     * 获取系统统计信息
     */
    public SystemStats getStats() {
        int totalEntities = entityHitboxes.size();
        int totalHitboxes = entityHitboxes.values().stream()
                .mapToInt(Map::size)
                .sum();

        return new SystemStats(totalEntities, totalHitboxes, averageUpdateTime);
    }

    /**
     * 系统统计信息
     */
    public static class SystemStats {
        public final int entityCount;
        public final int hitboxCount;
        public final float averageUpdateTime;

        public SystemStats(int entityCount, int hitboxCount, float averageUpdateTime) {
            this.entityCount = entityCount;
            this.hitboxCount = hitboxCount;
            this.averageUpdateTime = averageUpdateTime;
        }

        @Override
        public String toString() {
            return String.format("Entities: %d, Hitboxes: %d, Avg Update: %.2fms",
                    entityCount, hitboxCount, averageUpdateTime);
        }
    }
    // 在 HitboxSystem 类中添加以下方法：

    /**
     * 获取所有活跃实体的受击盒数据（供网络同步使用）
     */
    public Map<UUID, Collection<BoneHitboxComponent>> getAllActiveHitboxes() {
        Map<UUID, Collection<BoneHitboxComponent>> result = new HashMap<>();

        for (Map.Entry<UUID, Map<String, BoneHitboxComponent>> entry : entityHitboxes.entrySet()) {
            UUID entityId = entry.getKey();
            Map<String, BoneHitboxComponent> hitboxMap = entry.getValue();

            // 只收集活跃的受击盒
            Collection<BoneHitboxComponent> activeHitboxes = hitboxMap.values().stream()
                    .filter(BoneHitboxComponent::isActive)
                    .collect(Collectors.toList());

            if (!activeHitboxes.isEmpty()) {
                result.put(entityId, activeHitboxes);
            }
        }

        return result;
    }
}