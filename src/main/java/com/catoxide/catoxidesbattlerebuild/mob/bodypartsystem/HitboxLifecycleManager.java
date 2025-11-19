package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;

import java.util.*;

public class HitboxLifecycleManager {
    private final ModularZombie parent;
    private final Map<String, List<HitboxPart>> activeHitboxes = new HashMap<>();
    private final Set<String> destroyedParts = new HashSet<>();

    public HitboxLifecycleManager(ModularZombie parent) {
        this.parent = parent;
    }

    public void registerHitboxCluster(String partName, List<HitboxPart> cluster) {
        activeHitboxes.put(partName, new ArrayList<>(cluster));
        destroyedParts.remove(partName); // 注册新集群时重置破坏状态
    }

    public void updateHitboxCluster(String partName, List<HitboxPart> newCluster) {
        cleanupHitboxCluster(partName);
        registerHitboxCluster(partName, newCluster);
    }

    public void cleanupHitboxCluster(String partName) {
        List<HitboxPart> cluster = activeHitboxes.get(partName);
        if (cluster != null) {
            cluster.forEach(hitbox -> {
                if (hitbox != null && hitbox.isAlive()) {
                    hitbox.discard();
                }
            });
            cluster.clear();
        }
        activeHitboxes.remove(partName);
    }

    public void markPartAsDestroyed(String partName) {
        destroyedParts.add(partName);
        cleanupHitboxCluster(partName);
    }

    public void resetPart(String partName) {
        destroyedParts.remove(partName);
    }

    public void cleanupAllHitboxes() {
        new ArrayList<>(activeHitboxes.keySet()).forEach(this::cleanupHitboxCluster);
        activeHitboxes.clear();
        destroyedParts.clear();
    }

    public boolean isPartDestroyed(String partName) {
        return destroyedParts.contains(partName);
    }

    public List<HitboxPart> getHitboxCluster(String partName) {
        return activeHitboxes.getOrDefault(partName, Collections.emptyList());
    }

    public Map<String, List<HitboxPart>> getAllActiveHitboxes() {
        return Collections.unmodifiableMap(activeHitboxes);
    }

    public int getTotalHitboxCount() {
        return activeHitboxes.values().stream()
                .mapToInt(List::size)
                .sum();
    }

    public String getDebugInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("Active Hitbox Clusters: ").append(activeHitboxes.size()).append("\n");
        sb.append("Total Hitboxes: ").append(getTotalHitboxCount()).append("\n");
        sb.append("Destroyed Parts: ").append(destroyedParts.size()).append("\n");

        activeHitboxes.forEach((partName, cluster) -> {
            sb.append("  ").append(partName)
                    .append(": ").append(cluster.size())
                    .append(" hitboxes")
                    .append(destroyedParts.contains(partName) ? " [DESTROYED]" : "")
                    .append("\n");
        });

        return sb.toString();
    }
}