package com.catoxide.catoxidesbattlerebuild.server.geometry;

import org.joml.Vector3f;
import org.joml.Quaternionf;

/**
 * 骨骼受击盒配置
 */
public record BoneHitboxConfig(
        String boneName,
        Vector3f center,          // 局部空间中心点
        Vector3f size,            // 尺寸
        Quaternionf rotation,     // 局部旋转
        boolean enabled,          // 是否启用
        float damageMultiplier,   // 伤害倍率
        boolean isCritical,       // 是否为暴击区域
        boolean isArmored,        // 是否为装甲区域
        String hitSound,          // 受击音效
        String particleEffect,    // 粒子效果
        float cooldown           // 冷却时间
) {

    public static Builder builder(String boneName) {
        return new Builder(boneName);
    }

    public static class Builder {
        private final String boneName;
        private Vector3f center = new Vector3f();
        private Vector3f size = new Vector3f(0.5f, 0.5f, 0.5f);
        private Quaternionf rotation = new Quaternionf();
        private boolean enabled = true;
        private float damageMultiplier = 1.0f;
        private boolean isCritical = false;
        private boolean isArmored = false;
        private String hitSound = "entity.hit";
        private String particleEffect = "hit";
        private float cooldown = 0.1f;

        public Builder(String boneName) {
            this.boneName = boneName;
        }

        public Builder center(Vector3f center) {
            this.center = center;
            return this;
        }

        public Builder size(Vector3f size) {
            this.size = size;
            return this;
        }

        public Builder rotation(Quaternionf rotation) {
            this.rotation = rotation;
            return this;
        }

        public Builder fromCube(CubeCollection cube) {
            this.center = cube.pivot();
            this.size = cube.size();
            return this;
        }

        public BoneHitboxConfig build() {
            return new BoneHitboxConfig(
                    boneName, center, size, rotation, enabled,
                    damageMultiplier, isCritical, isArmored,
                    hitSound, particleEffect, cooldown
            );
        }
    }
}