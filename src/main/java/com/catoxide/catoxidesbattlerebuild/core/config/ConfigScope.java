package com.catoxide.catoxidesbattlerebuild.core.config;

import net.neoforged.fml.config.ModConfig;

/**
 * 配置作用域：决定 TOML 落在哪里、何时加载。
 * <ul>
 *   <li>{@link #CLIENT}：{@code config/<file>.toml}，客户端启动加载</li>
 *   <li>{@link #COMMON}：{@code config/<file>.toml}，双端加载</li>
 *   <li>{@link #SERVER}：{@code <world>/serverconfig/<file>.toml}，覆盖同名的 {@code config/}，退出世界时卸载</li>
 * </ul>
 */
public enum ConfigScope {
    CLIENT(ModConfig.Type.CLIENT),
    COMMON(ModConfig.Type.COMMON),
    SERVER(ModConfig.Type.SERVER);

    private final ModConfig.Type type;

    ConfigScope(ModConfig.Type type) {
        this.type = type;
    }

    public ModConfig.Type toModConfigType() {
        return type;
    }

    /** @return 匹配的 scope，未知返回 null */
    public static ConfigScope parse(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "CLIENT" -> CLIENT;
            case "COMMON" -> COMMON;
            case "SERVER" -> SERVER;
            default -> null;
        };
    }
}
