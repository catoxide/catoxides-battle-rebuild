package com.catoxide.catoxidesbattlerebuild.core.registry;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 方块状态参数目录（单一真源）
 * <p>
 * 目录文件：{@code assets/catoxidesbattlerebuild/editor/block_properties.json}（随主 mod 打包）。
 * <ul>
 *   <li>Java 侧：{@link BlockPropertyRegistry} 启动时按目录预注册标准参数；</li>
 *   <li>编辑器侧：contentpack-editor 直接读同一份文件作为「可选参数」列表
 *       （{@code tools/contentpack-editor/src/core/itemBlockSchema.js}）。</li>
 * </ul>
 * 新增一个自定义参数变成「标准选项」的流程 = 往目录文件加一条目（两边同时生效），
 * 即计划里「后续注册的自定义参数陆续加入可选选项」的落点。
 *
 * <p>本类同时负责按 {@link BlockPropertySpec} 现场构建
 * {@link Property} 实例（批量注册物品/方块时，包内 JSON 内联声明的自定义参数走这条路，
 * 无需包写 Java）。
 */
public final class BlockPropertyCatalog {

    public static final String RESOURCE_PATH = "/assets/catoxidesbattlerebuild/editor/block_properties.json";

    /** 原版 StateDefinition 的参数名/取值名白名单（Builder.add 会校验） */
    static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9_]+$");

    private static volatile List<BlockPropertySpec> specs;

    private BlockPropertyCatalog() {
    }

    /** 目录全部条目（懒加载，线程安全） */
    public static synchronized List<BlockPropertySpec> getSpecs() {
        if (specs == null) {
            specs = load();
        }
        return specs;
    }

    /** 按名字查目录条目（标准参数） */
    public static Optional<BlockPropertySpec> find(String name) {
        for (BlockPropertySpec spec : getSpecs()) {
            if (spec.name().equals(name)) {
                return Optional.of(spec);
            }
        }
        return Optional.empty();
    }

    private static List<BlockPropertySpec> load() {
        List<BlockPropertySpec> result = new ArrayList<>();
        try (InputStream in = BlockPropertyCatalog.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                throw new IllegalStateException("missing block property catalog resource: " + RESOURCE_PATH);
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("properties");
            if (arr != null) {
                for (JsonElement el : arr) {
                    JsonObject o = el.getAsJsonObject();
                    String name = str(o, "name", null);
                    String type = str(o, "type", null);
                    if (name == null || type == null) {
                        LogManager.serverError("BlockPropertyCatalog", "catalog entry without name/type skipped: %s", o);
                        continue;
                    }
                    List<String> values = new ArrayList<>();
                    if (o.has("values") && o.get("values").isJsonArray()) {
                        for (JsonElement v : o.getAsJsonArray("values")) {
                            values.add(v.getAsString());
                        }
                    }
                    int min = o.has("min") ? o.get("min").getAsInt() : 0;
                    int max = o.has("max") ? o.get("max").getAsInt() : 15;
                    String def = str(o, "default", null);
                    String labelZh = str(o, "labelZh", null);
                    String desc = str(o, "description", null);
                    result.add(new BlockPropertySpec(name, type, values, min, max, def, labelZh, desc));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read block property catalog", e);
        }
        LogManager.serverInfo("BlockPropertyCatalog", "Loaded %d standard block property spec(s) from %s",
                result.size(), RESOURCE_PATH);
        return List.copyOf(result);
    }

    /**
     * 按声明构建 {@link Property} 实例。
     * <p>校验失败抛 {@link IllegalArgumentException}（消息含参数名，注册器会把它归并进问题清单）。
     */
    public static Property<?> buildProperty(BlockPropertySpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("null block property spec");
        }
        String name = spec.name();
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("property name '" + name + "' 不满足原版 StateDefinition.NAME_PATTERN ^[a-z0-9_]+$");
        }
        for (String v : spec.values()) {
            if (v == null || !NAME_PATTERN.matcher(v).matches()) {
                throw new IllegalArgumentException("property '" + name + "' 取值 '" + v + "' 不满足 ^[a-z0-9_]+$");
            }
        }
        return switch (spec.type()) {
            case BlockPropertySpec.TYPE_DIRECTION -> {
                List<Direction> dirs = new ArrayList<>();
                for (String v : spec.values()) {
                    dirs.add(Direction.byName(v));
                }
                yield DirectionProperty.create(name, dirs.toArray(Direction[]::new));
            }
            case BlockPropertySpec.TYPE_AXIS -> {
                List<Direction.Axis> axes = new ArrayList<>();
                for (String v : spec.values()) {
                    axes.add(Direction.Axis.valueOf(v.toUpperCase(java.util.Locale.ROOT)));
                }
                yield EnumProperty.create(name, Direction.Axis.class, axes);
            }
            case BlockPropertySpec.TYPE_BOOLEAN -> BooleanProperty.create(name);
            case BlockPropertySpec.TYPE_INT -> {
                if (spec.max() <= spec.min()) {
                    throw new IllegalArgumentException("property '" + name + "' int 范围非法：max(" + spec.max() + ") 必须 > min(" + spec.min() + ")（原版 IntegerProperty 硬校验）");
                }
                if (spec.min() < 0) {
                    throw new IllegalArgumentException("property '" + name + "' int min 不能为负（原版 IntegerProperty 硬校验）");
                }
                yield IntegerProperty.create(name, spec.min(), spec.max());
            }
            case BlockPropertySpec.TYPE_ENUM -> new StringEnumProperty(name, spec.values());
            default -> throw new IllegalArgumentException("property '" + name + "' 未知类型: " + spec.type());
        };
    }

    /** 校验声明的完整性（不构建实例）。返回问题列表，空 = 合法。 */
    public static List<String> validate(BlockPropertySpec spec) {
        List<String> problems = new ArrayList<>();
        if (spec == null) {
            problems.add("空参数声明");
            return problems;
        }
        if (spec.name() == null || !NAME_PATTERN.matcher(spec.name()).matches()) {
            problems.add("参数名 '" + spec.name() + "' 非法（必须 ^[a-z0-9_]+$）");
            return problems;
        }
        switch (spec.type()) {
            case BlockPropertySpec.TYPE_DIRECTION, BlockPropertySpec.TYPE_AXIS, BlockPropertySpec.TYPE_ENUM -> {
                if (!spec.hasValues() || spec.values().size() < 2) {
                    problems.add("参数 '" + spec.name() + "' 需要 ≥2 个取值（原版 StateDefinition 拒绝 ≤1 取值的属性）");
                }
                // values 来自 List.copyOf（不可变），ListN.contains(null) 会抛 NPE，不能用 contains
                if (spec.values().stream().anyMatch(java.util.Objects::isNull)) {
                    problems.add("参数 '" + spec.name() + "' 含空取值");
                }
            }
            case BlockPropertySpec.TYPE_INT -> {
                if (spec.max() <= spec.min()) {
                    problems.add("参数 '" + spec.name() + "' int 范围非法：max 必须 > min");
                }
                if (spec.min() < 0) {
                    problems.add("参数 '" + spec.name() + "' int min 不能为负");
                }
            }
            case BlockPropertySpec.TYPE_BOOLEAN -> {
                // 无需取值；但声明的默认值必须是 true/false（1.21.1 BooleanProperty.getValue 只认这两个）
                if (spec.defaultValue() != null
                        && !"true".equals(spec.defaultValue()) && !"false".equals(spec.defaultValue())) {
                    problems.add("参数 '" + spec.name() + "' boolean 默认值必须是 true/false: " + spec.defaultValue());
                }
            }
            default -> problems.add("参数 '" + spec.name() + "' 未知类型: " + spec.type());
        }
        if (spec.defaultValue() != null && spec.hasValues()) {
            if (!spec.values().contains(spec.defaultValue())) {
                problems.add("参数 '" + spec.name() + "' 默认值 '" + spec.defaultValue() + "' 不在取值集合里");
            }
        }
        return problems;
    }

    private static String str(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }
}
