package com.catoxide.catoxidesbattlerebuild.core.config;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;

import java.util.List;
import java.util.Locale;

/**
 * 一个内容包配置文件的完整声明。
 *
 * @param packId      所属内容包
 * @param id          包内配置 id（如 "hud"），包代码用它取句柄
 * @param scope       作用域
 * @param file        TOML 文件名（不含 .toml）；沿用旧文件名可保留玩家已有配置
 * @param defaultTier 未单独声明档位的选项继承它
 * @param sections    分区
 */
public record ConfigDecl(
        String packId,
        String id,
        ConfigScope scope,
        String file,
        ConfigTier defaultTier,
        List<SectionDecl> sections
) {
    public ConfigDecl {
        sections = List.copyOf(sections);
    }

    /**
     * 内置配置屏读取的翻译键。
     * <p>模式：{@code <modid>.configuration.<packId>.<configId>.<section>.<key>}。
     * 包在自己的 {@code assets/<ns>/lang/*.json} 里提供同名键即可显示本地化名称。
     */
    public String translationKey(String sectionKey, String valueKey) {
        return sectionTranslationKey(sectionKey) + "." + valueKey;
    }

    /**
     * 分区标题的翻译键。内置配置屏还会查它的 {@code .button} 变体。
     */
    public String sectionTranslationKey(String sectionKey) {
        return translationPrefix() + "." + sectionKey;
    }

    /**
     * 内置配置屏「配置文件按钮 / 页面标题」的翻译键前缀。
     * <p>NeoForge 用 {@code getFileName()}（含 {@code .toml}）把非字母数字折叠成点，
     * 这里必须保持完全一致，否则名字不会生效。
     */
    public String fileTranslationKey() {
        String sanitized = (file + ".toml")
                .replaceAll("[^a-zA-Z0-9]+", ".")
                .replaceFirst("^\\.", "")
                .replaceFirst("\\.$", "")
                .toLowerCase(Locale.ENGLISH);
        return CatoxidesBattleRebuildConstants.MODID + ".configuration.section." + sanitized;
    }

    private String translationPrefix() {
        return CatoxidesBattleRebuildConstants.MODID + ".configuration." + packId + "." + id;
    }
}
