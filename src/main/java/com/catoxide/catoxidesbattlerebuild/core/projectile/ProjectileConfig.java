package com.catoxide.catoxidesbattlerebuild.core.projectile;

import net.minecraft.world.phys.Vec3;

/**
 * 投射物配置数据类。
 * <p>定义投射物的飞行物理参数和战斗属性。
 * <p>后续可迁移到 JSON 配置（ContentPack 驱动）。
 *
 * @param baseSpeed        初始发射速度（格/刻）
 * @param gravity          重力加速度（格/刻²，正数向下）
 * @param airResistance    空气阻力系数（每刻速度乘以该值，1.0 = 无阻尼）
 * @param baseDamage       基础伤害
 * @param armorPenetration 穿甲值
 * @param piercing         贯穿值
 * @param maxLifetime      最大存活刻数（超时自动销毁）
 * @param collisionRadius  骨骼碰撞半径（用于射线-球体检测）
 */
public record ProjectileConfig(
        float baseSpeed,
        float gravity,
        float airResistance,
        float baseDamage,
        float armorPenetration,
        float piercing,
        int maxLifetime,
        float collisionRadius
) {
    /** 默认箭矢配置 */
    public static final ProjectileConfig DEFAULT_ARROW = new ProjectileConfig(
            3.0f,    // 初始速度
            0.05f,   // 重力
            0.99f,   // 空气阻力
            5.0f,    // 基础伤害
            5.0f,    // 穿甲
            5.0f,    // 贯穿
            600,     // 30 秒
            0.35f    // 骨骼碰撞半径
    );

    /**
     * 构造器，提供默认值。
     */
    public ProjectileConfig {
        if (baseSpeed < 0) baseSpeed = DEFAULT_ARROW.baseSpeed();
        if (gravity < 0) gravity = DEFAULT_ARROW.gravity();
        if (airResistance <= 0 || airResistance > 1) airResistance = DEFAULT_ARROW.airResistance();
        if (baseDamage <= 0) baseDamage = DEFAULT_ARROW.baseDamage();
        if (maxLifetime <= 0) maxLifetime = DEFAULT_ARROW.maxLifetime();
        if (collisionRadius <= 0) collisionRadius = DEFAULT_ARROW.collisionRadius();
    }

    /**
     * 构建器，方便子类/JSON 解析构造。
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private float baseSpeed = DEFAULT_ARROW.baseSpeed();
        private float gravity = DEFAULT_ARROW.gravity();
        private float airResistance = DEFAULT_ARROW.airResistance();
        private float baseDamage = DEFAULT_ARROW.baseDamage();
        private float armorPenetration = DEFAULT_ARROW.armorPenetration();
        private float piercing = DEFAULT_ARROW.piercing();
        private int maxLifetime = DEFAULT_ARROW.maxLifetime();
        private float collisionRadius = DEFAULT_ARROW.collisionRadius();

        public Builder baseSpeed(float baseSpeed) { this.baseSpeed = baseSpeed; return this; }
        public Builder gravity(float gravity) { this.gravity = gravity; return this; }
        public Builder airResistance(float airResistance) { this.airResistance = airResistance; return this; }
        public Builder baseDamage(float baseDamage) { this.baseDamage = baseDamage; return this; }
        public Builder armorPenetration(float armorPenetration) { this.armorPenetration = armorPenetration; return this; }
        public Builder piercing(float piercing) { this.piercing = piercing; return this; }
        public Builder maxLifetime(int maxLifetime) { this.maxLifetime = maxLifetime; return this; }
        public Builder collisionRadius(float collisionRadius) { this.collisionRadius = collisionRadius; return this; }

        public ProjectileConfig build() {
            return new ProjectileConfig(baseSpeed, gravity, airResistance, baseDamage,
                    armorPenetration, piercing, maxLifetime, collisionRadius);
        }
    }
}
