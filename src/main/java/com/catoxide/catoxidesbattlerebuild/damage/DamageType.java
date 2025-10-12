package com.catoxide.catoxidesbattlerebuild.damage;

public enum DamageType {
    // 物理伤害类型
    PHYSICS("physics","物理"),
    // 元素伤害类型
    FIRE("fire", "火焰"),
    COLD("cold", "寒冷"),
    LIGHTNING("lightning", "闪电"),
    // TODO:魔法伤害类
    // 特殊伤害类型
    TRUE_DAMAGE("true_damage", "真实伤害");

    private final String id;
    private final String displayName;

    DamageType(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
}