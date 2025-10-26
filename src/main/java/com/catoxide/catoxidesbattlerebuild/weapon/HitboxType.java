package com.catoxide.catoxidesbattlerebuild.weapon;

// HitboxType.java
public enum HitboxType {
    SWEEP(0.3, 1.2),      // 横扫攻击
    THRUST(0.1, 0.8),     // 刺击
    SLASH(0.2, 1.0),      // 劈砍
    BLUNT(0.4, 1.5);      // 钝器

    private final double expansion;
    private final double length;

    HitboxType(double expansion, double length) {
        this.expansion = expansion;
        this.length = length;
    }

    public double getExpansion() { return expansion; }
    public double getLength() { return length; }
}
