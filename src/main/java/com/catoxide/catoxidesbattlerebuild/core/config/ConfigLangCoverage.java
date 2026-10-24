package com.catoxide.catoxidesbattlerebuild.core.config;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 强制每个配置在 {@code assets/<namespace>/lang/en_us.json} 里提供显示名。
 *
 * <p>内置配置屏对缺翻译的键会退化成原始键名（选项）或通用文字（文件按钮/分区按钮），
 * 玩家看到的就是 {@code fovTicks} 这种东西。因此缺名不是「能跑但难看」，而是声明错误：
 * 桥接层直接拒绝注册该配置。
 *
 * <p>英文名是底线；中英双语包再额外提供 {@code zh_cn.json} 即可（那里缺键会回落到英文）。
 * 只认 jar 根目录的 {@code assets/}（§9.4）——放在 {@code resources/} 下的文件运行时不会被挂载。
 */
public final class ConfigLangCoverage {

    private static final String TAG = "PackConfig";
    private static final Pattern EN_US = Pattern.compile("assets/[^/]+/lang/en_us\\.json");

    private ConfigLangCoverage() {
    }

    /**
     * @param langFound {@code false} 表示整个包里没有可用的 {@code en_us.json}
     * @param required  该配置应提供的全部显示名键
     * @param missing   缺失或为空的键（{@code langFound} 为 false 时等于 {@code required}）
     */
    public record Result(boolean langFound, List<String> required, List<String> missing) {

        public boolean complete() {
            return langFound && missing.isEmpty();
        }

        public String missingSummary() {
            return String.join(", ", missing);
        }
    }

    public static Result check(File jar, ConfigDecl decl) {
        Set<String> required = requiredKeys(decl);
        JsonObject translations = readEnUs(jar);
        if (translations == null) {
            return new Result(false, List.copyOf(required), List.copyOf(required));
        }

        List<String> missing = new ArrayList<>();
        for (String key : required) {
            if (!hasText(translations.get(key))) {
                missing.add(key);
            }
        }
        return new Result(true, List.copyOf(required), List.copyOf(missing));
    }

    /**
     * 内置配置屏实际查询的键：文件按钮 + 页面标题、每个分区的标题/按钮、每个选项。
     */
    static Set<String> requiredKeys(ConfigDecl decl) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(decl.fileTranslationKey());
        keys.add(decl.fileTranslationKey() + ".title");
        for (SectionDecl section : decl.sections()) {
            keys.add(decl.sectionTranslationKey(section.key()));
            keys.add(decl.sectionTranslationKey(section.key()) + ".button");
            for (ValueDecl value : section.values()) {
                keys.add(decl.translationKey(section.key(), value.key()));
            }
        }
        return keys;
    }

    private static boolean hasText(JsonElement element) {
        return element != null
                && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isString()
                && !element.getAsString().isBlank();
    }

    /**
     * 合并包内所有命名空间的 {@code en_us.json}（Minecraft 的语言表本身就是全局的，键不按命名空间隔离）。
     *
     * @return null 表示一个都没找到；坏文件记 ERROR 后跳过
     */
    private static JsonObject readEnUs(File jar) {
        if (jar == null || !jar.isFile()) {
            return null;
        }

        JsonObject merged = new JsonObject();
        boolean found = false;
        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !EN_US.matcher(entry.getName()).matches()) {
                    continue;
                }
                found = true;
                try (InputStream in = zip.getInputStream(entry)) {
                    JsonElement parsed = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    if (!parsed.isJsonObject()) {
                        LogManager.serverError(TAG, "Pack lang file '%s' must contain a JSON object; ignored", entry.getName());
                        continue;
                    }
                    for (var translation : parsed.getAsJsonObject().entrySet()) {
                        merged.add(translation.getKey(), translation.getValue());
                    }
                } catch (RuntimeException e) {
                    LogManager.serverError(TAG, "Pack lang file '%s' is not valid JSON (%s); ignored",
                            entry.getName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            LogManager.serverError(TAG, "Cannot read pack jar '%s' for English display names: %s",
                    jar.getName(), e.getMessage());
            return null;
        }
        return found ? merged : null;
    }
}
