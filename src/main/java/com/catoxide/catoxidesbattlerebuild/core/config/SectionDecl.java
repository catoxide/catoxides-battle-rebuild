package com.catoxide.catoxidesbattlerebuild.core.config;

import java.util.List;

/**
 * 配置分区：对应 TOML 里的一个表，也是内置配置屏里的一层。
 */
public record SectionDecl(String key, List<ValueDecl> values) {
    public SectionDecl {
        values = List.copyOf(values);
    }
}
