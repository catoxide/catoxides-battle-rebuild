package com.catoxide.catoxidesbattlerebuild.mob.bodypartsystem;

import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.Map;

public class HitboxConfigurator {
    private final Map<String, HitboxConfiguration> configurations = new HashMap<>();

    public HitboxConfigurator() {
        setupDefaultConfigurations();
    }

    private void setupDefaultConfigurations() {
        // 头部配置
        configurations.put("head", new HitboxConfiguration()
                .setDamageMultiplier(2.0f)
                .setCriticalHit(true)
                .setLethalWhenDestroyed(true));

        // 躯干配置
        configurations.put("torso", new HitboxConfiguration()
                .setDamageMultiplier(1.0f)
                .setLethalWhenDestroyed(false));

        // 手臂配置
        configurations.put("arm_left", new HitboxConfiguration()
                .setDamageMultiplier(0.6f)
                .setLethalWhenDestroyed(false));
        configurations.put("arm_right", new HitboxConfiguration()
                .setDamageMultiplier(0.6f)
                .setLethalWhenDestroyed(false));

        // 腿部配置
        configurations.put("leg_left", new HitboxConfiguration()
                .setDamageMultiplier(0.7f)
                .setLethalWhenDestroyed(false));
        configurations.put("leg_right", new HitboxConfiguration()
                .setDamageMultiplier(0.7f)
                .setLethalWhenDestroyed(false));
    }

    public HitboxConfiguration getConfiguration(String partName) {
        return configurations.getOrDefault(partName, new HitboxConfiguration());
    }

    public static class HitboxConfiguration {
        private float damageMultiplier = 1.0f;
        private boolean criticalHit = false;
        private boolean lethalWhenDestroyed = false;
        private AABB customBounds = null;
        private Quaternionf customRotation = null;

        // Getter 和 Setter 方法
        public float getDamageMultiplier() { return damageMultiplier; }
        public HitboxConfiguration setDamageMultiplier(float multiplier) {
            this.damageMultiplier = multiplier;
            return this;
        }

        public boolean isCriticalHit() { return criticalHit; }
        public HitboxConfiguration setCriticalHit(boolean critical) {
            this.criticalHit = critical;
            return this;
        }

        public boolean isLethalWhenDestroyed() { return lethalWhenDestroyed; }
        public HitboxConfiguration setLethalWhenDestroyed(boolean lethal) {
            this.lethalWhenDestroyed = lethal;
            return this;
        }

        public AABB getCustomBounds() { return customBounds; }
        public HitboxConfiguration setCustomBounds(AABB bounds) {
            this.customBounds = bounds;
            return this;
        }

        public Quaternionf getCustomRotation() { return customRotation; }
        public HitboxConfiguration setCustomRotation(Quaternionf rotation) {
            this.customRotation = rotation;
            return this;
        }
    }
}