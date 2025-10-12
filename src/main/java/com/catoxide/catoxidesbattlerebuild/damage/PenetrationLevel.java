package com.catoxide.catoxidesbattlerebuild.damage;

import com.catoxide.catoxidesbattlerebuild.armor.ArmorClass;

public enum PenetrationLevel {
    NONE(0, "无穿透"),      // 无法穿透任何护甲
    LIGHT(1, "轻穿透"),     // 穿透轻甲
    MEDIUM(2, "中穿透"),    // 穿透轻甲、中甲
    HEAVY(3, "重穿透"),     // 穿透轻、中、重甲
    ARMOR_PIERCING(4, "穿甲"), // 穿透轻、中、重、强化甲
    IGNORE_ARMOR(5, "无视护甲"); // 完全无视护甲

    private final int level;
    private final String displayName;

    PenetrationLevel(int level, String displayName) {
        this.level = level;
        this.displayName = displayName;
    }

    public int getLevel() { return level; }
    public String getDisplayName() { return displayName; }

    // 检查是否能穿透特定护甲等级
    public boolean canPenetrate(ArmorClass armorClass) {
        return this.level >= armorClass.getLevel();
    }

    // 获取穿透效果系数（当无法完全穿透时）
    public float getPenetrationEffectiveness(ArmorClass armorClass) {
        if (canPenetrate(armorClass)) {
            return 1.0f; // 完全穿透
        } else {
            // 根据穿透差距计算部分穿透效果
            int gap = armorClass.getLevel() - this.level;
            return Math.max(0.1f, 1.0f - (gap * 0.3f)); // 每级差距减少30%效果
        }
    }
}