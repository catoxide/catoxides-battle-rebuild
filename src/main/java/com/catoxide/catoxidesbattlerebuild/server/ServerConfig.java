package com.catoxide.catoxidesbattlerebuild.server;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.common.Mod;
import software.bernie.geckolib.GeckoLib;

/**
 * 服务端配置类，用于管理系统配置
 */
@Mod.EventBusSubscriber
public class ServerConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    // 实体追踪配置
    public static final ForgeConfigSpec.BooleanValue ENABLE_ENTITY_TRACKING;
    public static final ForgeConfigSpec.IntValue MAX_TRACKED_ENTITIES;
    public static final ForgeConfigSpec.BooleanValue TRACK_ANIMALS_ONLY;

    // 模型加载配置
    public static final ForgeConfigSpec.BooleanValue ENABLE_MODEL_CACHING;
    public static final ForgeConfigSpec.IntValue MODEL_LOAD_TIMEOUT;

    // 性能配置
    public static final ForgeConfigSpec.BooleanValue ENABLE_PERFORMANCE_MONITORING;
    public static final ForgeConfigSpec.IntValue UPDATE_INTERVAL;

    static {
        // 实体追踪配置
        BUILDER.comment("Entity Tracking Settings");
        BUILDER.push("entity_tracking");
        ENABLE_ENTITY_TRACKING = BUILDER
                .comment("Enable entity tracking")
                .define("enable_entity_tracking", true);
        MAX_TRACKED_ENTITIES = BUILDER
                .comment("Maximum number of entities to track")
                .defineInRange("max_tracked_entities", 1000, 1, 10000);
        TRACK_ANIMALS_ONLY = BUILDER
                .comment("Only track animal entities")
                .define("track_animals_only", true);
        BUILDER.pop();

        // 模型加载配置
        BUILDER.comment("Model Loading Settings");
        BUILDER.push("model_loading");
        ENABLE_MODEL_CACHING = BUILDER
                .comment("Enable model caching")
                .define("enable_model_caching", true);
        MODEL_LOAD_TIMEOUT = BUILDER
                .comment("Model load timeout in milliseconds")
                .defineInRange("model_load_timeout", 5000, 1000, 30000);
        BUILDER.pop();

        // 性能配置
        BUILDER.comment("Performance Settings");
        BUILDER.push("performance");
        ENABLE_PERFORMANCE_MONITORING = BUILDER
                .comment("Enable performance monitoring")
                .define("enable_performance_monitoring", false);
        UPDATE_INTERVAL = BUILDER
                .comment("Update interval in ticks")
                .defineInRange("update_interval", 1, 1, 20);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    /**
     * 初始化配置
     */
    public static void initialize() {
        GeckoLib.LOGGER.debug("ServerConfig initialized");
    }

    /**
     * 检查是否启用实体追踪
     * @return 是否启用
     */
    public static boolean isEntityTrackingEnabled() {
        return ENABLE_ENTITY_TRACKING.get();
    }

    /**
     * 获取最大追踪实体数量
     * @return 最大数量
     */
    public static int getMaxTrackedEntities() {
        return MAX_TRACKED_ENTITIES.get();
    }

    /**
     * 检查是否只追踪动物
     * @return 是否只追踪动物
     */
    public static boolean isTrackAnimalsOnly() {
        return TRACK_ANIMALS_ONLY.get();
    }

    /**
     * 检查是否启用模型缓存
     * @return 是否启用
     */
    public static boolean isModelCachingEnabled() {
        return ENABLE_MODEL_CACHING.get();
    }

    /**
     * 获取模型加载超时时间
     * @return 超时时间
     */
    public static int getModelLoadTimeout() {
        return MODEL_LOAD_TIMEOUT.get();
    }

    /**
     * 检查是否启用性能监控
     * @return 是否启用
     */
    public static boolean isPerformanceMonitoringEnabled() {
        return ENABLE_PERFORMANCE_MONITORING.get();
    }

    /**
     * 获取更新间隔
     * @return 更新间隔
     */
    public static int getUpdateInterval() {
        return UPDATE_INTERVAL.get();
    }
}
