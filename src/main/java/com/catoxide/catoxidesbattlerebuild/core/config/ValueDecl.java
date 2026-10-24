package com.catoxide.catoxidesbattlerebuild.core.config;

import java.util.List;

/**
 * 单个配置选项的声明。
 *
 * @param key          包代码查询用的键（同一配置内唯一，不是 TOML 路径）
 * @param type         值类型
 * @param defaultValue 默认值（运行时类型：Boolean/Integer/Double/String/List&lt;String&gt;）
 * @param comment      TOML 与内置配置屏 tooltip 里显示的说明；空列表表示不写注释
 * @param tier         生效档位
 * @param min          数值下界；仅 INT/DOUBLE，null 表示不限
 * @param max          数值上界；仅 INT/DOUBLE，null 表示不限
 */
public record ValueDecl(
        String key,
        ValueType type,
        Object defaultValue,
        List<String> comment,
        ConfigTier tier,
        Double min,
        Double max
) {
    public ValueDecl {
        comment = List.copyOf(comment);
    }
}
