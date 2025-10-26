package com.catoxide.catoxidesbattlerebuild.mob;

import net.minecraft.world.phys.AABB;
import java.util.ArrayList;
import java.util.List;

public class BodyPart {
    private final String partName;
    private final String boneName;
    private final List<AABB> preciseHitboxes;
    private final AABB baseHitbox;

    // 模块类型属性
    private final PartType partType;
    private boolean lethalWhenDestroyed;

    // 可破坏模块属性
    private float currentHealth;
    private final float maxHealth;

    // 伤害倍率属性（所有模块都有）
    private final float damageMultiplierToMain;

    public enum PartType {
        DESTRUCTIBLE,    // 可破坏模块（有独立血量）
        INDESTRUCTIBLE   // 不可破坏模块（无独立血量）
    }

    // 可破坏模块构造器
    public BodyPart(String partName, String boneName, AABB baseHitbox,
                    float maxHealth, float damageMultiplierToMain, boolean lethalWhenDestroyed) {
        this.partName = partName;
        this.boneName = boneName;
        this.baseHitbox = baseHitbox;
        this.preciseHitboxes = createPreciseHitboxesFromAABB(baseHitbox);
        this.partType = PartType.DESTRUCTIBLE;
        this.maxHealth = maxHealth;
        this.currentHealth = maxHealth;
        this.damageMultiplierToMain = damageMultiplierToMain;
        this.lethalWhenDestroyed = lethalWhenDestroyed;
    }

    // 不可破坏模块构造器
    public BodyPart(String partName, String boneName, AABB baseHitbox,
                    float damageMultiplierToMain) {
        this.partName = partName;
        this.boneName = boneName;
        this.baseHitbox = baseHitbox;
        this.preciseHitboxes = createPreciseHitboxesFromAABB(baseHitbox);
        this.partType = PartType.INDESTRUCTIBLE;
        this.maxHealth = 0;
        this.currentHealth = 0;
        this.damageMultiplierToMain = damageMultiplierToMain;
        this.lethalWhenDestroyed = false;
    }

    // 精确碰撞箱构造器（可破坏）
    public BodyPart(String partName, String boneName, List<AABB> preciseHitboxes,
                    float maxHealth, float damageMultiplierToMain, boolean lethalWhenDestroyed) {
        this.partName = partName;
        this.boneName = boneName;
        this.preciseHitboxes = preciseHitboxes;
        this.baseHitbox = calculateBoundingBox(preciseHitboxes);
        this.partType = PartType.DESTRUCTIBLE;
        this.maxHealth = maxHealth;
        this.currentHealth = maxHealth;
        this.damageMultiplierToMain = damageMultiplierToMain;
        this.lethalWhenDestroyed = lethalWhenDestroyed;
    }

    // 精确碰撞箱构造器（不可破坏）
    public BodyPart(String partName, String boneName, List<AABB> preciseHitboxes,
                    float damageMultiplierToMain) {
        this.partName = partName;
        this.boneName = boneName;
        this.preciseHitboxes = preciseHitboxes;
        this.baseHitbox = calculateBoundingBox(preciseHitboxes);
        this.partType = PartType.INDESTRUCTIBLE;
        this.maxHealth = 0;
        this.currentHealth = 0;
        this.damageMultiplierToMain = damageMultiplierToMain;
        this.lethalWhenDestroyed = false;
    }
    // 设置致命性
    public void setLethalWhenDestroyed(boolean lethalWhenDestroyed) {
        this.lethalWhenDestroyed = lethalWhenDestroyed;
    }

    // 处理伤害，返回对主体的伤害值
    public float takeDamage(float damage) {
        float damageToMain = damage * damageMultiplierToMain;

        if (partType == PartType.DESTRUCTIBLE && !isDestroyed()) {
            // 可破坏模块：先扣除模块血量
            currentHealth = Math.max(0, currentHealth - damage);

            // 如果模块被破坏，应用额外的对主体伤害
            if (isDestroyed()) {
                damageToMain += calculateDestructionDamage();
            }
        }

        return damageToMain;
    }

    private float calculateDestructionDamage() {
        // 模块被破坏时对主体造成的额外伤害
        // 可以是固定值或基于最大血量的百分比
        return maxHealth * 0.1f; // 示例：最大血量的10%
    }

    // Getter/Setter
    public String getPartName() { return partName; }
    public String getBoneName() { return boneName; }
    public AABB getBaseHitbox() { return baseHitbox; }
    public List<AABB> getPreciseHitboxes() { return preciseHitboxes; }
    public PartType getPartType() { return partType; }
    public boolean isLethalWhenDestroyed() { return lethalWhenDestroyed; }
    public float getCurrentHealth() { return currentHealth; }
    public float getMaxHealth() { return maxHealth; }
    public float getDamageMultiplierToMain() { return damageMultiplierToMain; }
    public boolean isDestroyed() {
        return partType == PartType.DESTRUCTIBLE && currentHealth <= 0;
    }
    public int getHitboxCount() { return preciseHitboxes.size(); }

    // 从单个AABB创建多个小碰撞箱（保持不变）
    private List<AABB> createPreciseHitboxesFromAABB(AABB aabb) {
        List<AABB> hitboxes = new ArrayList<>();
        double width = aabb.maxX - aabb.minX;
        double height = aabb.maxY - aabb.minY;
        double depth = aabb.maxZ - aabb.minZ;

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
}