package com.catoxide.catoxidesbattlerebuild.server.hitboxsystem;


import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;
import org.joml.Quaternionf;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 骨骼受击盒配置注册表
 */
public class BoneHitboxRegistry {
    private static final BoneHitboxRegistry INSTANCE = new BoneHitboxRegistry();

    // 模型位置 -> 配置
    private final Map<ResourceLocation, HitboxConfig> configMap =
            new ConcurrentHashMap<>();

    // 默认配置
    private final HitboxConfig defaultConfig = createDefaultConfig();

    private BoneHitboxRegistry() {
        loadDefaultConfigs();
    }

    public static BoneHitboxRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * 注册模型配置
     */
    public void registerConfig(ResourceLocation modelLocation, HitboxConfig config) {
        configMap.put(modelLocation, config);
    }

    /**
     * 获取模型配置
     */
    public HitboxConfig getConfig(ResourceLocation modelLocation) {
        return configMap.get(modelLocation);
    }

    /**
     * 获取默认配置
     */
    public HitboxConfig getDefaultConfig() {
        return defaultConfig;
    }

    /**
     * 加载默认配置
     */
    private void loadDefaultConfigs() {
        // 示例：为常见骨骼添加配置
        HitboxConfig humanoidConfig = new HitboxConfig("humanoid");

        // 头部 - 暴击区域
        humanoidConfig.addBoneConfig(new HitboxConfig.BoneConfig(
                "head",
                new Vector3f(0, 0, 0),
                new Vector3f(0.3f, 0.3f, 0.3f),
                new Quaternionf(),
                true
        ).setDamageMultiplier(2.0f).setCritical(true));

        // 身体 - 标准区域
        humanoidConfig.addBoneConfig(new HitboxConfig.BoneConfig(
                "body",
                new Vector3f(0, -0.2f, 0),
                new Vector3f(0.4f, 0.6f, 0.2f),
                new Quaternionf(),
                true
        ).setDamageMultiplier(1.0f));

        // 手臂
        humanoidConfig.addBoneConfig(new HitboxConfig.BoneConfig(
                "rightArm",
                new Vector3f(0.2f, 0, 0),
                new Vector3f(0.15f, 0.6f, 0.15f),
                new Quaternionf(),
                true
        ).setDamageMultiplier(0.7f));

        humanoidConfig.addBoneConfig(new HitboxConfig.BoneConfig(
                "leftArm",
                new Vector3f(-0.2f, 0, 0),
                new Vector3f(0.15f, 0.6f, 0.15f),
                new Quaternionf(),
                true
        ).setDamageMultiplier(0.7f));

        // 腿部
        humanoidConfig.addBoneConfig(new HitboxConfig.BoneConfig(
                "rightLeg",
                new Vector3f(0.1f, -0.3f, 0),
                new Vector3f(0.15f, 0.6f, 0.15f),
                new Quaternionf(),
                true
        ).setDamageMultiplier(0.8f));

        humanoidConfig.addBoneConfig(new HitboxConfig.BoneConfig(
                "leftLeg",
                new Vector3f(-0.1f, -0.3f, 0),
                new Vector3f(0.15f, 0.6f, 0.15f),
                new Quaternionf(),
                true
        ).setDamageMultiplier(0.8f));

        // 注册到常见模型
        registerConfig(new ResourceLocation("minecraft", "geo/humanoid.geo.json"), humanoidConfig);
    }

    /**
     * 创建默认配置
     */
    private HitboxConfig createDefaultConfig() {
        HitboxConfig config = new HitboxConfig("default");

        // 默认骨骼配置（如果未指定）
        config.addBoneConfig(new HitboxConfig.BoneConfig(
                "default_bone",
                new Vector3f(0, 0, 0),
                new Vector3f(0.2f, 0.2f, 0.2f),
                new Quaternionf(),
                true
        ).setDamageMultiplier(1.0f));

        return config;
    }

    /**
     * 从JSON文件加载配置
     */
    public void loadConfigFromJson(ResourceLocation configLocation) {
        // TODO: 实现JSON配置加载
        // 可以从资源包加载骨骼受击盒配置
    }
}
