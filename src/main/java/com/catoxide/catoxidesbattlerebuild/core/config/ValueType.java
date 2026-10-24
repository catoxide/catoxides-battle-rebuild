package com.catoxide.catoxidesbattlerebuild.core.config;

import java.util.Locale;

/**
 * 声明支持的配置值类型（JSON 里的 {@code "type"}）。
 * <p>刻意保持最小集合：每多一种类型，编辑器、校验与 TOML 映射都要各加一条。
 */
public enum ValueType {
    BOOL,
    INT,
    DOUBLE,
    STRING,
    STRING_LIST;

    /** @return 匹配的类型，未知返回 null */
    public static ValueType parse(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "bool", "boolean" -> BOOL;
            case "int", "integer" -> INT;
            case "double", "float" -> DOUBLE;
            case "string", "str" -> STRING;
            case "string_list", "stringlist", "list" -> STRING_LIST;
            default -> null;
        };
    }
}
