package com.catoxide.catoxidesbattlerebuild.server.temp;

import org.joml.Vector3f;
import org.joml.Quaternionf;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 受击盒配置类
 */
public class HitboxConfig {
    private final String configName;
    private final Map<String, BoneConfig> boneConfigs = new ConcurrentHashMap<>();

    public HitboxConfig(String configName) {
        this.configName = configName;
    }

    /**
     * 获取配置名称
     */
    public String getConfigName() {
        return configName;
    }

    /**
     * 添加骨骼配置
     */
    public void addBoneConfig(BoneConfig config) {
        boneConfigs.put(config.getBoneName(), config);
    }

    /**
     * 获取骨骼配置
     */
    public BoneConfig getBoneConfig(String boneName) {
        return boneConfigs.get(boneName);
    }

    /**
     * 获取所有骨骼配置
     */
    public Collection<BoneConfig> getAllBoneConfigs() {
        return boneConfigs.values();
    }

    /**
     * 检查骨骼是否启用受击盒
     */
    public boolean isBoneEnabled(String boneName) {
        BoneConfig config = boneConfigs.get(boneName);
        return config != null && config.isEnabled();
    }

    /**
     * 从JSON创建配置
     */
    public static HitboxConfig fromJson(String json, String configName) {
        HitboxConfig config = new HitboxConfig(configName);

        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            if (root.has("bone_hitboxes")) {
                JsonObject bones = root.getAsJsonObject("bone_hitboxes");

                for (String boneName : bones.keySet()) {
                    JsonObject boneJson = bones.getAsJsonObject(boneName);

                    BoneConfig boneConfig = BoneConfig.fromJson(boneName, boneJson);
                    if (boneConfig != null) {
                        config.addBoneConfig(boneConfig);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return config;
    }

    /**
     * 骨骼配置内部类
     */
    public static class BoneConfig {
        private final String boneName;
        private final Vector3f center;
        private final Vector3f size;
        private final Quaternionf orientation;
        private final boolean enabled;

        private float damageMultiplier = 1.0f;
        private boolean critical = false;
        private boolean armored = false;
        private String hitSound = "entity.hit";
        private String particleEffect = "hit";

        public BoneConfig(String boneName, Vector3f center, Vector3f size,
                          Quaternionf orientation, boolean enabled) {
            this.boneName = boneName;
            this.center = new Vector3f(center);
            this.size = new Vector3f(size);
            this.orientation = new Quaternionf(orientation);
            this.enabled = enabled;
        }

        // 链式设置器
        public BoneConfig setDamageMultiplier(float multiplier) {
            this.damageMultiplier = multiplier;
            return this;
        }

        public BoneConfig setCritical(boolean critical) {
            this.critical = critical;
            return this;
        }

        public BoneConfig setArmored(boolean armored) {
            this.armored = armored;
            return this;
        }

        public BoneConfig setHitSound(String hitSound) {
            this.hitSound = hitSound;
            return this;
        }

        public BoneConfig setParticleEffect(String particleEffect) {
            this.particleEffect = particleEffect;
            return this;
        }

        // Getters
        public String getBoneName() { return boneName; }
        public Vector3f getCenter() { return new Vector3f(center); }
        public Vector3f getSize() { return new Vector3f(size); }
        public Quaternionf getOrientation() { return new Quaternionf(orientation); }
        public boolean isEnabled() { return enabled; }
        public float getDamageMultiplier() { return damageMultiplier; }
        public boolean isCritical() { return critical; }
        public boolean isArmored() { return armored; }
        public String getHitSound() { return hitSound; }
        public String getParticleEffect() { return particleEffect; }

        /**
         * 从JSON创建骨骼配置
         */
        public static BoneConfig fromJson(String boneName, JsonObject json) {
            try {
                // 解析中心点
                Vector3f center = new Vector3f(0, 0, 0);
                if (json.has("center")) {
                    JsonObject centerJson = json.getAsJsonObject("center");
                    center = new Vector3f(
                            centerJson.get("x").getAsFloat(),
                            centerJson.get("y").getAsFloat(),
                            centerJson.get("z").getAsFloat()
                    );
                }

                // 解析尺寸
                Vector3f size = new Vector3f(0.2f, 0.2f, 0.2f);
                if (json.has("size")) {
                    JsonObject sizeJson = json.getAsJsonObject("size");
                    size = new Vector3f(
                            sizeJson.get("x").getAsFloat(),
                            sizeJson.get("y").getAsFloat(),
                            sizeJson.get("z").getAsFloat()
                    );
                }

                // 解析方向 (欧拉角)
                Quaternionf orientation = new Quaternionf();
                if (json.has("rotation")) {
                    JsonObject rotJson = json.getAsJsonObject("rotation");
                    float rx = (float) Math.toRadians(rotJson.get("x").getAsFloat());
                    float ry = (float) Math.toRadians(rotJson.get("y").getAsFloat());
                    float rz = (float) Math.toRadians(rotJson.get("z").getAsFloat());
                    orientation.rotationXYZ(rx, ry, rz);
                }

                boolean enabled = json.get("enabled").getAsBoolean();

                BoneConfig config = new BoneConfig(boneName, center, size, orientation, enabled);

                // 可选参数
                if (json.has("damage_multiplier")) {
                    config.setDamageMultiplier(json.get("damage_multiplier").getAsFloat());
                }
                if (json.has("critical")) {
                    config.setCritical(json.get("critical").getAsBoolean());
                }
                if (json.has("armored")) {
                    config.setArmored(json.get("armored").getAsBoolean());
                }
                if (json.has("hit_sound")) {
                    config.setHitSound(json.get("hit_sound").getAsString());
                }
                if (json.has("particle_effect")) {
                    config.setParticleEffect(json.get("particle_effect").getAsString());
                }

                return config;

            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }
    }
}

