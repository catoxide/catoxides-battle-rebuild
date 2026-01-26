package com.catoxide.catoxidesbattlerebuild.server;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import software.bernie.geckolib.GeckoLib;
import software.bernie.geckolib.core.animatable.GeoAnimatable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简化的实体-模型关联管理器
 * 一次性建立关联，持续使用
 */
public class EntityAssociator {
    private static final EntityAssociator INSTANCE = new EntityAssociator();
    private final Map<UUID, EntityCollection> entityCollection = new ConcurrentHashMap<>();

    private EntityAssociator() {}

    public static EntityAssociator getInstance() {
        return INSTANCE;
    }

    /**
     * 建立实体-模型关联（一次性）
     */
    public EntityCollection associate(Entity entity, ResourceLocation modelLocation) {
        if (!(entity instanceof GeoAnimatable)) {
            return null;
        }

        EntityCollection association = EntityCollection.create(entity, modelLocation);
        entityCollection.put(entity.getUUID(), association);

        GeckoLib.LOGGER.debug("Associated entity {} with model {}", entity, modelLocation);
        return association;
    }

    /**
     * 解除关联（实体死亡/移除时调用）
     */
    public void disassociate(Entity entity) {
        entityCollection.remove(entity.getUUID());
        GeckoLib.LOGGER.debug("Disassociated entity {}", entity);
    }

    /**
     * 获取实体关联
     */
    public EntityCollection getCollection(UUID entityId) {
        return entityCollection.get(entityId);
    }


    /**
     * 批量更新所有实体的动画
     */
    public void updateAllAnimations(float partialTick) {
        entityCollection.values().parallelStream().forEach(association -> {
            // 注意：这里需要获取实体引用，可能有性能开销
            // 但这是必须的，因为实体状态可能改变
            Entity entity = getEntityById(association.entityId());
            if (entity != null && association.isValid(entity) && entity instanceof GeoAnimatable geoEntity) {
                association.updateAnimation(geoEntity, partialTick);
            }
        });
    }

    /**
     * 获取关联数量
     */
    public int getAssociationCount() {
        return entityCollection.size();
    }

    /**
     * 清理无效关联
     */
    public void cleanupInvalidAssociations() {
        entityCollection.entrySet().removeIf(entry -> {
            Entity entity = getEntityById(entry.getKey());
            return entity == null || !entry.getValue().isValid(entity);
        });
    }

    private Entity getEntityById(UUID entityId) {
        // 注意：这个方法需要实现，可以通过ServerLevel获取
        // 这里只是示例，实际实现需要上下文信息
        return null;
    }
}