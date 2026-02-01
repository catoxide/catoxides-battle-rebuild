package com.catoxide.catoxidesbattlerebuild.client;

import com.catoxide.catoxidesbattlerebuild.server.hitboxsystem.BoneHitboxComponent;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import software.bernie.geckolib.GeckoLib;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端受击盒系统
 */
public class HitboxSystemClient {
    private static final HitboxSystemClient INSTANCE = new HitboxSystemClient();

    // 实体ID -> 骨骼受击盒列表
    private final Map<Integer, List<BoneHitboxComponent>> entityHitboxes = new ConcurrentHashMap<>();

    // 性能监控
    private int lastEntityCount = 0;
    private int lastHitboxCount = 0;
    private long lastUpdateTime = 0;

    private HitboxSystemClient() {}

    public static HitboxSystemClient getInstance() {
        return INSTANCE;
    }

    /**
     * 更新客户端受击盒数据
     */
    public void updateHitboxes(Map<Integer, List<BoneHitboxComponent>> hitboxes) {
        entityHitboxes.clear();
        entityHitboxes.putAll(hitboxes);

        lastEntityCount = hitboxes.size();
        lastHitboxCount = hitboxes.values().stream()
                .mapToInt(List::size)
                .sum();
        lastUpdateTime = System.currentTimeMillis();

        if (GeckoLib.LOGGER.isDebugEnabled()) {
            GeckoLib.LOGGER.debug("Client updated {} entities, {} hitboxes",
                    lastEntityCount, lastHitboxCount);
        }
    }

    /**
     * 获取实体的所有受击盒
     */
    public Collection<BoneHitboxComponent> getEntityHitboxes(int entityId) {
        return entityHitboxes.getOrDefault(entityId, Collections.emptyList());
    }

    /**
     * 获取实体的所有受击盒（通过UUID）
     */
//    public Collection<BoneHitboxComponent> getEntityHitboxes(UUID entityUUID) {
//        Minecraft mc = Minecraft.getInstance();
//        if (mc.level == null) {
//            return Collections.emptyList();
//        }
//
//        Entity entity = mc.level.getPlayerByUUID(entityUUID);
//        if (entity == null) {
//            // 尝试获取任意实体
//            entity = mc.level.getEntities().get(entityUUID);
//        }
//
//        return entity != null ? getEntityHitboxes(entity.getId()) : Collections.emptyList();
//    }

    /**
     * 清理旧数据
     */
    public void cleanup() {
        if (Minecraft.getInstance().level == null) {
            entityHitboxes.clear();
            return;
        }

        // 移除不存在的实体
        Iterator<Integer> iterator = entityHitboxes.keySet().iterator();
        while (iterator.hasNext()) {
            int entityId = iterator.next();
            if (Minecraft.getInstance().level.getEntity(entityId) == null) {
                iterator.remove();
            }
        }
    }

    /**
     * 获取客户端统计信息
     */
    public ClientStats getStats() {
        return new ClientStats(lastEntityCount, lastHitboxCount, lastUpdateTime);
    }

    /**
     * 客户端统计信息
     */
    public static class ClientStats {
        public final int entityCount;
        public final int hitboxCount;
        public final long lastUpdateTime;

        public ClientStats(int entityCount, int hitboxCount, long lastUpdateTime) {
            this.entityCount = entityCount;
            this.hitboxCount = hitboxCount;
            this.lastUpdateTime = lastUpdateTime;
        }

        @Override
        public String toString() {
            return String.format("Entities: %d, Hitboxes: %d, Updated: %dms ago",
                    entityCount, hitboxCount, System.currentTimeMillis() - lastUpdateTime);
        }
    }
}