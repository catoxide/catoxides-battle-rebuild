package com.catoxide.catoxidesbattlerebuild.core.config;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 读取并合并内容包的配置声明。
 *
 * <h3>两份文件</h3>
 * <ul>
 *   <li><b>default</b>：包 jar 根目录的 {@value #JAR_ENTRY}。结构、类型、默认值的真源。</li>
 *   <li><b>current</b>：{@code config/catoxidesbattlerebuild/pack-configs/<packId>.json}。
 *       打包后仍可改；只允许覆盖 <b>default / comment / tier / min / max</b>，
 *       按键路径稀疏覆盖，未提到的选项回落到 default。</li>
 * </ul>
 *
 * <p>结构字段（配置 id、scope、文件名、分区、选项键、类型）只认 default：
 * current 里没有地方能写它们，因此不可能出现「两份文件结构不一致」。
 *
 * <p>所有失败都只影响<b>单个配置</b>：坏声明记 ERROR 并丢弃该配置，其它配置照常注册。
 */
public final class PackConfigDeclarations {

    public static final String JAR_ENTRY = "config-declarations.json";

    private static final String TAG = "PackConfig";
    private static final int SCHEMA_VERSION = 1;

    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z0-9_.-]+");
    private static final String[] TIERS = {"HOT", "WARM", "COLD"};
    private static final String[] TYPES = {"bool", "int", "double", "string", "string_list"};

    private static final Set<String> CONFIG_FIELDS = Set.of("id", "scope", "file", "defaultTier", "sections");
    private static final Set<String> SECTION_FIELDS = Set.of("key", "values");
    private static final Set<String> VALUE_FIELDS = Set.of("key", "type", "default", "comment", "tier", "min", "max");
    private static final Set<String> CURRENT_FIELDS = Set.of("schemaVersion", "basedOnDefaultHash", "overrides");
    private static final Set<String> OVERRIDE_FIELDS = Set.of("default", "comment", "tier", "min", "max");

    private PackConfigDeclarations() {
    }

    /**
     * 读取一个包的声明并应用 current 覆盖。
     *
     * @param jar         包 jar（null 或不存在 → 视为无声明）
     * @param packId      包 id（用于日志与翻译键）
     * @param currentFile {@code config/.../pack-configs/<packId>.json}，不存在则只用 default
     * @return 通过校验的配置声明；没有声明或整体损坏时返回空列表
     */
    public static List<ConfigDecl> load(File jar, String packId, Path currentFile) {
        byte[] defaultBytes = readJarEntry(jar, packId);
        if (defaultBytes == null) {
            return List.of();
        }

        List<ConfigDecl> decls = new ArrayList<>();
        try {
            JsonObject root = parseObject(new String(defaultBytes, StandardCharsets.UTF_8), packId + ":" + JAR_ENTRY);
            int schema = requireInt(root, "schemaVersion", JAR_ENTRY);
            if (schema != SCHEMA_VERSION) {
                throw new IllegalArgumentException("unsupported schemaVersion " + schema + " (expected " + SCHEMA_VERSION + ")");
            }
            warnUnknown(root, Set.of("schemaVersion", "configs"), JAR_ENTRY);

            JsonArray configs = requireArray(root, "configs", JAR_ENTRY);
            Set<String> configIds = new HashSet<>();
            for (JsonElement element : configs) {
                if (!element.isJsonObject()) {
                    LogManager.serverError(TAG, "Pack '%s': every entry of 'configs' must be an object", packId);
                    continue;
                }
                JsonObject configJson = element.getAsJsonObject();
                String label = packId + "/" + optString(configJson, "id", JAR_ENTRY, "<missing id>");
                try {
                    ConfigDecl decl = parseConfig(packId, configJson);
                    if (!configIds.add(decl.id())) {
                        throw new IllegalArgumentException("duplicate config id '" + decl.id() + "'");
                    }
                    decls.add(decl);
                } catch (RuntimeException e) {
                    LogManager.serverError(TAG, "Pack '%s': config declaration rejected: %s", label, e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            LogManager.serverError(TAG, "Pack '%s': invalid %s: %s", packId, JAR_ENTRY, e.getMessage());
            return List.of();
        }

        JsonObject current = readCurrent(packId, currentFile);
        if (current != null) {
            applyOverrides(packId, decls, current, currentFile, sha256Hex(defaultBytes));
        }
        return decls;
    }

    // ==================== default 解析 ====================

    private static ConfigDecl parseConfig(String packId, JsonObject json) {
        String id = requireIdentifier(json, "id", JAR_ENTRY);
        String label = packId + "/" + id;

        String scopeRaw = requireString(json, "scope", label);
        ConfigScope scope = ConfigScope.parse(scopeRaw);
        if (scope == null) {
            throw new IllegalArgumentException(label + ": unknown scope '" + scopeRaw + "' (expected CLIENT, COMMON or SERVER)");
        }

        String file = requireString(json, "file", label);
        if (!IDENTIFIER.matcher(file).matches() || file.contains("..")) {
            throw new IllegalArgumentException(label + ": invalid file name '" + file + "' (letters, digits, '.', '_', '-' only)");
        }

        ConfigTier defaultTier = ConfigTier.COLD;
        if (json.has("defaultTier")) {
            defaultTier = parseTier(json.get("defaultTier"), label + ".defaultTier");
        }
        warnUnknown(json, CONFIG_FIELDS, label);

        JsonArray sectionsJson = requireArray(json, "sections", label);
        if (sectionsJson.isEmpty()) {
            throw new IllegalArgumentException(label + ": 'sections' must not be empty");
        }

        List<SectionDecl> sections = new ArrayList<>();
        Set<String> sectionKeys = new HashSet<>();
        Set<String> valueKeys = new HashSet<>();
        for (JsonElement element : sectionsJson) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException(label + ": every entry of 'sections' must be an object");
            }
            JsonObject sectionJson = element.getAsJsonObject();
            String sectionKey = requireIdentifier(sectionJson, "key", label);
            if (!sectionKeys.add(sectionKey)) {
                throw new IllegalArgumentException(label + ": duplicate section '" + sectionKey + "'");
            }
            warnUnknown(sectionJson, SECTION_FIELDS, label + "/" + sectionKey);

            JsonArray valuesJson = requireArray(sectionJson, "values", label + "/" + sectionKey);
            if (valuesJson.isEmpty()) {
                throw new IllegalArgumentException(label + "/" + sectionKey + ": 'values' must not be empty");
            }

            List<ValueDecl> values = new ArrayList<>();
            for (JsonElement valueElement : valuesJson) {
                if (!valueElement.isJsonObject()) {
                    throw new IllegalArgumentException(label + "/" + sectionKey + ": every entry of 'values' must be an object");
                }
                values.add(parseValue(valueElement.getAsJsonObject(), label + "/" + sectionKey, defaultTier, scope, valueKeys));
            }
            sections.add(new SectionDecl(sectionKey, values));
        }

        return new ConfigDecl(packId, id, scope, file, defaultTier, sections);
    }

    private static ValueDecl parseValue(JsonObject json, String label, ConfigTier defaultTier,
                                        ConfigScope scope, Set<String> valueKeys) {
        String key = requireIdentifier(json, "key", label);
        String path = label + "/" + key;
        if (!valueKeys.add(key)) {
            throw new IllegalArgumentException(path + ": duplicate key in this config (keys are addressed without the section)");
        }

        String typeRaw = requireString(json, "type", path);
        ValueType type = ValueType.parse(typeRaw);
        if (type == null) {
            throw new IllegalArgumentException(path + ": unknown type '" + typeRaw + "' (expected " + String.join(", ", TYPES) + ")");
        }

        JsonElement defaultJson = json.get("default");
        if (defaultJson == null || defaultJson.isJsonNull()) {
            throw new IllegalArgumentException(path + ": missing 'default'");
        }
        Object defaultValue = readDefault(defaultJson, type, path);

        ConfigTier tier = defaultTier;
        if (json.has("tier")) {
            tier = parseTier(json.get("tier"), path + ".tier");
        }
        if (!tier.isValidFor(scope)) {
            throw new IllegalArgumentException(path + ": tier COLD cannot be used with scope SERVER "
                    + "(NeoForge rejects a GAME restart for server configs; use HOT or WARM)");
        }

        List<String> comment = parseComment(json.get("comment"), path);

        Double min = null;
        Double max = null;
        if (type == ValueType.INT || type == ValueType.DOUBLE) {
            min = optDouble(json, "min", path);
            max = optDouble(json, "max", path);
            if (min != null && max != null && min > max) {
                throw new IllegalArgumentException(path + ": 'min' (" + min + ") is greater than 'max' (" + max + ")");
            }
            checkRange(defaultValue, min, max, path);
        } else if (json.has("min") || json.has("max")) {
            LogManager.serverWarn(TAG, "%s: 'min'/'max' only apply to int/double and were ignored", path);
        }

        warnUnknown(json, VALUE_FIELDS, path);
        return new ValueDecl(key, type, defaultValue, comment, tier, min, max);
    }

    private static Object readDefault(JsonElement json, ValueType type, String path) {
        if (type == ValueType.STRING_LIST) {
            if (!json.isJsonArray()) {
                throw new IllegalArgumentException(path + ": default for string_list must be an array of strings");
            }
            List<String> values = new ArrayList<>();
            for (JsonElement element : json.getAsJsonArray()) {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException(path + ": every string_list element must be a string");
                }
                values.add(element.getAsString());
            }
            return List.copyOf(values);
        }

        if (!json.isJsonPrimitive()) {
            throw new IllegalArgumentException(path + ": default must be a JSON primitive for type " + type.name().toLowerCase());
        }
        JsonPrimitive primitive = json.getAsJsonPrimitive();
        return switch (type) {
            case BOOL -> {
                if (!primitive.isBoolean()) {
                    throw new IllegalArgumentException(path + ": default must be a boolean");
                }
                yield primitive.getAsBoolean();
            }
            case INT -> {
                if (!primitive.isNumber()) {
                    throw new IllegalArgumentException(path + ": default must be an integer");
                }
                double value = primitive.getAsDouble();
                if (value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException(path + ": default must be an integer, got " + primitive.getAsString());
                }
                yield (int) value;
            }
            case DOUBLE -> {
                if (!primitive.isNumber()) {
                    throw new IllegalArgumentException(path + ": default must be a number");
                }
                yield primitive.getAsDouble();
            }
            case STRING -> {
                if (!primitive.isString()) {
                    throw new IllegalArgumentException(path + ": default must be a string");
                }
                yield primitive.getAsString();
            }
            case STRING_LIST -> throw new IllegalStateException("handled above");
        };
    }

    private static void checkRange(Object defaultValue, Double min, Double max, String path) {
        if (min == null && max == null) {
            return;
        }
        double value = ((Number) defaultValue).doubleValue();
        if (min != null && value < min) {
            throw new IllegalArgumentException(path + ": default " + value + " is below 'min' " + min);
        }
        if (max != null && value > max) {
            throw new IllegalArgumentException(path + ": default " + value + " is above 'max' " + max);
        }
    }

    private static List<String> parseComment(JsonElement json, String path) {
        if (json == null || json.isJsonNull()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
            for (String line : json.getAsString().split("\n", -1)) {
                lines.add(line.stripTrailing());
            }
        } else if (json.isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray()) {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException(path + ": 'comment' must be a string or an array of strings");
                }
                lines.add(element.getAsString().stripTrailing());
            }
        } else {
            throw new IllegalArgumentException(path + ": 'comment' must be a string or an array of strings");
        }
        // NeoForge 对「全是空白行」的注释会抛异常；这里直接降级为「无注释」
        if (lines.stream().allMatch(String::isBlank)) {
            return List.of();
        }
        return List.copyOf(lines);
    }

    // ==================== current 覆盖 ====================

    private static JsonObject readCurrent(String packId, Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonObject root = parseObject(Files.readString(file, StandardCharsets.UTF_8), file.toString());
            int schema = requireInt(root, "schemaVersion", file.toString());
            if (schema != SCHEMA_VERSION) {
                LogManager.serverError(TAG, "Pack '%s': %s has unsupported schemaVersion %d; ignored",
                        packId, file, schema);
                return null;
            }
            return root;
        } catch (IOException | RuntimeException e) {
            LogManager.serverError(TAG, "Pack '%s': cannot read %s: %s", packId, file, e.getMessage());
            return null;
        }
    }

    private static void applyOverrides(String packId, List<ConfigDecl> decls, JsonObject current,
                                       Path file, String defaultHash) {
        String basedOn = optString(current, "basedOnDefaultHash", file.getFileName().toString(), null);
        if (basedOn != null && !basedOn.equalsIgnoreCase(defaultHash)) {
            LogManager.serverInfo(TAG,
                    "Pack '%s': %s was written against a different declaration default (hash mismatch). "
                            + "Overrides still win; entries it does not mention use the new defaults.",
                    packId, file.getFileName());
        }
        warnUnknown(current, CURRENT_FIELDS, file.getFileName().toString());

        JsonElement overridesJson = current.get("overrides");
        if (overridesJson == null) {
            LogManager.serverWarn(TAG,
                    "Pack '%s': %s has no 'overrides' object; ignored. Expected {\"schemaVersion\":1,\"overrides\":{\"<config>/<section>/<key>\": {...}}}",
                    packId, file.getFileName());
            return;
        }
        if (!overridesJson.isJsonObject()) {
            LogManager.serverError(TAG, "Pack '%s': %s 'overrides' must be an object; ignored", packId, file.getFileName());
            return;
        }

        JsonObject overrides = overridesJson.getAsJsonObject();
        Set<String> consumed = new HashSet<>();
        List<ConfigDecl> rebuilt = new ArrayList<>();

        for (ConfigDecl config : decls) {
            List<SectionDecl> newSections = new ArrayList<>();
            for (SectionDecl section : config.sections()) {
                List<ValueDecl> newValues = new ArrayList<>();
                for (ValueDecl value : section.values()) {
                    String path = config.id() + "/" + section.key() + "/" + value.key();
                    JsonElement overrideJson = overrides.get(path);
                    if (overrideJson == null) {
                        newValues.add(value);
                        continue;
                    }
                    consumed.add(path);
                    if (!overrideJson.isJsonObject()) {
                        LogManager.serverWarn(TAG, "Pack '%s': override '%s' must be an object; ignored", packId, path);
                        newValues.add(value);
                        continue;
                    }
                    newValues.add(applyValueOverride(packId, config, value, overrideJson.getAsJsonObject(), path));
                }
                newSections.add(new SectionDecl(section.key(), newValues));
            }
            rebuilt.add(new ConfigDecl(config.packId(), config.id(), config.scope(), config.file(),
                    config.defaultTier(), newSections));
        }

        for (String key : overrides.keySet()) {
            if (!consumed.contains(key)) {
                LogManager.serverWarn(TAG,
                        "Pack '%s': override '%s' matches no declared value; ignored (expected '<configId>/<sectionKey>/<valueKey>')",
                        packId, key);
            }
        }

        decls.clear();
        decls.addAll(rebuilt);
    }

    /**
     * 合并单条覆盖。任何一项非法 → 整条覆盖作废（回落到声明值），并记 WARN：
     * 半套用会让「我明明改了却没生效」变得难以解释。
     */
    private static ValueDecl applyValueOverride(String packId, ConfigDecl config, ValueDecl value,
                                                JsonObject overrideJson, String path) {
        Object defaultValue = value.defaultValue();
        List<String> comment = value.comment();
        ConfigTier tier = value.tier();
        Double min = value.min();
        Double max = value.max();

        for (String field : overrideJson.keySet()) {
            try {
                switch (field) {
                    case "default" -> defaultValue = readDefault(overrideJson.get(field), value.type(), path);
                    case "comment" -> comment = parseComment(overrideJson.get(field), path);
                    case "tier" -> {
                        ConfigTier parsed = parseTier(overrideJson.get(field), path + ".tier");
                        if (!parsed.isValidFor(config.scope())) {
                            throw new IllegalArgumentException("tier COLD cannot be used with scope SERVER");
                        }
                        tier = parsed;
                    }
                    case "min" -> {
                        requireNumeric(value, path, field);
                        min = requireDouble(overrideJson.get(field), path + ".min");
                    }
                    case "max" -> {
                        requireNumeric(value, path, field);
                        max = requireDouble(overrideJson.get(field), path + ".max");
                    }
                    default -> LogManager.serverWarn(TAG,
                            "Pack '%s': override '%s' has unknown field '%s'; ignored (editable: default/comment/tier/min/max)",
                            packId, path, field);
                }
            } catch (RuntimeException e) {
                LogManager.serverWarn(TAG, "Pack '%s': override '%s.%s' is invalid (%s); the whole override was ignored",
                        packId, path, field, e.getMessage());
                return value;
            }
        }

        if (min != null && max != null && min > max) {
            LogManager.serverWarn(TAG, "Pack '%s': override '%s' leaves min > max; the whole override was ignored", packId, path);
            return value;
        }
        try {
            checkRange(defaultValue, min, max, path);
        } catch (RuntimeException e) {
            LogManager.serverWarn(TAG, "Pack '%s': override '%s' is invalid (%s); the whole override was ignored",
                    packId, path, e.getMessage());
            return value;
        }
        return new ValueDecl(value.key(), value.type(), defaultValue, comment, tier, min, max);
    }

    private static void requireNumeric(ValueDecl value, String path, String field) {
        if (value.type() != ValueType.INT && value.type() != ValueType.DOUBLE) {
            throw new IllegalArgumentException(path + ": '" + field + "' only applies to int/double");
        }
    }

    // ==================== JSON helpers ====================

    private static JsonObject parseObject(String text, String label) {
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException(label + ": top level must be a JSON object");
            }
            return parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new IllegalArgumentException(label + ": malformed JSON: " + e.getMessage());
        }
    }

    private static String requireString(JsonObject json, String field, String label) {
        JsonElement element = json.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(label + ": missing string field '" + field + "'");
        }
        return element.getAsString();
    }

    private static String requireString(JsonElement element, String label) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(label + ": expected a string");
        }
        return element.getAsString();
    }

    private static String optString(JsonObject json, String field, String label, String fallback) {
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        return requireString(element, label + "." + field);
    }

    private static String requireIdentifier(JsonObject json, String field, String label) {
        String value = requireString(json, field, label);
        if (!IDENTIFIER.matcher(value).matches() || value.contains("..")) {
            throw new IllegalArgumentException(label + ": '" + field + "' must match [a-zA-Z0-9_.-]+, got '" + value + "'");
        }
        return value;
    }

    private static JsonArray requireArray(JsonObject json, String field, String label) {
        JsonElement element = json.get(field);
        if (element == null || !element.isJsonArray()) {
            throw new IllegalArgumentException(label + ": missing array field '" + field + "'");
        }
        return element.getAsJsonArray();
    }

    private static int requireInt(JsonObject json, String field, String label) {
        JsonElement element = json.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(label + ": missing integer field '" + field + "'");
        }
        return element.getAsInt();
    }

    private static Double optDouble(JsonObject json, String field, String label) {
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return requireDouble(element, label + "." + field);
    }

    private static Double requireDouble(JsonElement element, String label) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(label + ": expected a number");
        }
        return element.getAsDouble();
    }

    private static ConfigTier parseTier(JsonElement element, String label) {
        String raw = requireString(element, label);
        ConfigTier tier = ConfigTier.parse(raw);
        if (tier == null) {
            throw new IllegalArgumentException(label + ": unknown tier '" + raw + "' (expected " + String.join(", ", TIERS) + ")");
        }
        return tier;
    }

    private static void warnUnknown(JsonObject json, Set<String> known, String label) {
        for (String field : json.keySet()) {
            if (!known.contains(field)) {
                LogManager.serverWarn(TAG, "%s: unknown field '%s' ignored", label, field);
            }
        }
    }

    // ==================== IO ====================

    private static byte[] readJarEntry(File jar, String packId) {
        if (jar == null || !jar.isFile()) {
            return null;
        }
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry entry = zip.getEntry(JAR_ENTRY);
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            LogManager.serverError(TAG, "Pack '%s': cannot read %s: %s", packId, JAR_ENTRY, e.getMessage());
            return null;
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16));
                out.append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
