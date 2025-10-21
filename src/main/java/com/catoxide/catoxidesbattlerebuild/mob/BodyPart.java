package com.catoxide.catoxidesbattlerebuild.mob;

import net.minecraft.world.phys.AABB;
import java.util.ArrayList;
import java.util.List;

public class BodyPart {
    private final String partName;
    private final String boneName;
    private final List<AABB> preciseHitboxes; // 改为多个精确碰撞箱
    private final AABB baseHitbox; // 保留原有单个碰撞箱用于兼容
    private float currentHealth;
    private final float maxHealth;
    private final float damageMultiplier;

    public BodyPart(String partName, String boneName, AABB baseHitbox, float maxHealth, float damageMultiplier) {
        this.partName = partName;
        this.boneName = boneName;
        this.baseHitbox = baseHitbox;
        this.maxHealth = maxHealth;
        this.currentHealth = maxHealth;
        this.damageMultiplier = damageMultiplier;

        // 自动从单个AABB生成多个精确碰撞箱
        this.preciseHitboxes = createPreciseHitboxesFromAABB(baseHitbox);
    }

    // 新的构造器支持直接传入精确碰撞箱
    public BodyPart(String partName, String boneName, List<AABB> preciseHitboxes, float maxHealth, float damageMultiplier) {
        this.partName = partName;
        this.boneName = boneName;
        this.preciseHitboxes = preciseHitboxes;
        this.baseHitbox = calculateBoundingBox(preciseHitboxes); // 计算包围盒
        this.maxHealth = maxHealth;
        this.currentHealth = maxHealth;
        this.damageMultiplier = damageMultiplier;
    }

    // 从单个AABB创建多个小碰撞箱
    private List<AABB> createPreciseHitboxesFromAABB(AABB aabb) {
        List<AABB> hitboxes = new ArrayList<>();

        double width = aabb.maxX - aabb.minX;
        double height = aabb.maxY - aabb.minY;
        double depth = aabb.maxZ - aabb.minZ;

        // 根据尺寸决定分段数量
        int xSegments = Math.max(1, (int)(width / 0.2));
        int ySegments = Math.max(1, (int)(height / 0.2));
        int zSegments = Math.max(1, (int)(depth / 0.2));

        double segmentWidth = width / xSegments;
        double segmentHeight = height / ySegments;
        double segmentDepth = depth / zSegments;

        for (int x = 0; x < xSegments; x++) {
            for (int y = 0; y < ySegments; y++) {
                for (int z = 0; z < zSegments; z++) {
                    double minX = aabb.minX + x * segmentWidth;
                    double minY = aabb.minY + y * segmentHeight;
                    double minZ = aabb.minZ + z * segmentDepth;
                    double maxX = minX + segmentWidth;
                    double maxY = minY + segmentHeight;
                    double maxZ = minZ + segmentDepth;

                    hitboxes.add(new AABB(minX, minY, minZ, maxX, maxY, maxZ));
                }
            }
        }

        return hitboxes;
    }

    // 计算多个碰撞箱的包围盒
    private AABB calculateBoundingBox(List<AABB> hitboxes) {
        if (hitboxes.isEmpty()) {
            return new AABB(0, 0, 0, 0, 0, 0);
        }

        AABB result = hitboxes.get(0);
        for (int i = 1; i < hitboxes.size(); i++) {
            result = result.minmax(hitboxes.get(i));
        }
        return result;
    }

    public boolean takeDamage(float damage) {
        currentHealth = Math.max(0, currentHealth - damage * damageMultiplier);
        return currentHealth <= 0;
    }

    // Getter/Setter
    public String getPartName() { return partName; }
    public String getBoneName() { return boneName; }
    public AABB getBaseHitbox() { return baseHitbox; }
    public List<AABB> getPreciseHitboxes() { return preciseHitboxes; }
    public float getCurrentHealth() { return currentHealth; }
    public boolean isDestroyed() { return currentHealth <= 0; }
    public int getHitboxCount() { return preciseHitboxes.size(); }
}