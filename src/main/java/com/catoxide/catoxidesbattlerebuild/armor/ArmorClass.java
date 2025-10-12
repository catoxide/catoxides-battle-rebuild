package com.catoxide.catoxidesbattlerebuild.armor;

public enum ArmorClass {
    NONE(0, "无甲"),      // 没有护甲保护
    LIGHT(1, "轻甲"),     // 基础保护
    MEDIUM(2, "中甲"),    // 中等保护
    HEAVY(3, "重甲"),     // 高级保护
    FORTIFIED(4, "强化甲"), // 特殊强化护甲
    INVULNERABLE(5, "无敌"); // 完全免疫（特殊状态）

    private final int level;
    private final String displayName;

    ArmorClass(int level, String displayName) {
        this.level = level;
        this.displayName = displayName;
    }

    public int getLevel() { return level; }
    public String getDisplayName() { return displayName; }

    // 检查是否能被穿透
    public boolean canBePenetratedBy(int penetrationLevel) {
        return penetrationLevel >= this.level;
    }

    // 获取护甲等级对应的减免系数
    public float getDamageReduction() {
        return switch (this) {
            case NONE -> 0.0f;
            case LIGHT -> 0.2f;
            case MEDIUM -> 0.4f;
            case HEAVY -> 0.6f;
            case FORTIFIED -> 0.8f;
            case INVULNERABLE -> 1.0f; // 100%减免（无敌）
        };
    }
}
