package example.contentpack.hudlayout.ui;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 容器布局的持久化：每块屏幕面板一条「相对原版位置的偏移 + 效果列锚定」，
 * 每个屏内元素（{@code core.ui.UiEditTargets} 登记的）一条「相对它自己基准位置的偏移」。
 *
 * <p>文件在 {@code config/catoxidesbattlerebuild/ui-layout.json}，打包后仍可手改；
 * 每次打开容器屏时按修改时间重读一次。编辑模式（{@link UiEditMode}）拖动时只改内存，
 * 松手才落盘。
 *
 * <p>稀疏存储：没写的条目按 {@link UiPanelLayout#DEFAULT} 处理，等于原版。
 * 元素没有效果列，{@code elements} 段只读写 {@code x}/{@code y}。
 */
public final class UiLayoutStore {

    /** 一份文件的两个段：{@code panels} 按屏 id，{@code elements} 按元素 id。 */
    public record Layout(Map<ResourceLocation, UiPanelLayout> panels,
                         Map<ResourceLocation, UiPanelLayout> elements) {

        public static final Layout EMPTY = new Layout(Map.of(), Map.of());
    }

    private static final String TAG = "UiLayout";
    private static final int SCHEMA_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile Layout layout = Layout.EMPTY;
    private static long stamp = Long.MIN_VALUE;

    private UiLayoutStore() {
    }

    /** 文件没变就什么都不做；没文件时清空（删掉文件 = 回到原版）。 */
    public static void reloadIfChanged() {
        Path file = file();
        try {
            long current = Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : -1L;
            if (current == stamp) {
                return;
            }
            stamp = current;
            if (current < 0) {
                layout = Layout.EMPTY;
                return;
            }

            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                LogManager.clientWarn(TAG, "UI layout %s is not a JSON object; keeping previous values", file);
                return;
            }
            Layout read = decode(parsed.getAsJsonObject(), file);
            if (read != null) {
                layout = read;
                LogManager.clientInfo(TAG, "Loaded %d container panel(s) and %d element(s) of layout from %s",
                        read.panels().size(), read.elements().size(), file);
            }
        } catch (RuntimeException | IOException e) {
            LogManager.clientError(TAG, "Cannot read UI layout " + file + ": " + e.getMessage());
        }
    }

    public static UiPanelLayout get(@Nullable ResourceLocation panelId) {
        return panelId == null
                ? UiPanelLayout.DEFAULT
                : layout.panels().getOrDefault(panelId, UiPanelLayout.DEFAULT);
    }

    /** 编辑模式用：只改内存，{@link #save()} 才落盘。默认布局不进表（和稀疏文件同一个语义）。 */
    public static void set(ResourceLocation panelId, UiPanelLayout value) {
        layout = new Layout(merge(layout.panels(), panelId, value), layout.elements());
    }

    public static UiPanelLayout getElement(@Nullable ResourceLocation elementId) {
        return elementId == null
                ? UiPanelLayout.DEFAULT
                : layout.elements().getOrDefault(elementId, UiPanelLayout.DEFAULT);
    }

    public static void setElement(ResourceLocation elementId, UiPanelLayout value) {
        layout = new Layout(layout.panels(), merge(layout.elements(), elementId, value));
    }

    private static Map<ResourceLocation, UiPanelLayout> merge(Map<ResourceLocation, UiPanelLayout> current,
                                                              ResourceLocation id, UiPanelLayout value) {
        Map<ResourceLocation, UiPanelLayout> next = new HashMap<>(current);
        if (value.isDefault()) {
            next.remove(id);
        } else {
            next.put(id, value);
        }
        return Map.copyOf(next);
    }

    public static synchronized void save() {
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(encode(layout)), StandardCharsets.UTF_8);
            // 自己写的文件不该再被当作「外部改动」重读一遍
            stamp = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException | RuntimeException e) {
            LogManager.clientError(TAG, "Cannot write UI layout " + file + ": " + e.getMessage());
        }
    }

    /**
     * 纯序列化：自检拿它做 encode → decode 往返。
     *
     * <p>条目按 id 排序输出，手改出的 diff 才稳定；默认布局不写（删文件 = 回到原版）。
     */
    static JsonObject encode(Layout source) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.add("panels", encodeEntries(source.panels(), true));
        root.add("elements", encodeEntries(source.elements(), false));
        return root;
    }

    private static JsonObject encodeEntries(Map<ResourceLocation, UiPanelLayout> entries, boolean withEffects) {
        List<ResourceLocation> ids = new ArrayList<>(entries.keySet());
        ids.sort(Comparator.comparing(ResourceLocation::toString));

        JsonObject json = new JsonObject();
        for (ResourceLocation id : ids) {
            UiPanelLayout value = entries.get(id);
            if (value == null || value.isDefault()) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("x", value.x());
            entry.addProperty("y", value.y());
            UiEffectsAnchor effects = value.effects();
            if (withEffects && effects != null) {
                JsonObject effectsJson = new JsonObject();
                effectsJson.addProperty("anchor", effects.anchor().jsonName());
                effectsJson.addProperty("gapX", effects.gapX());
                effectsJson.addProperty("gapY", effects.gapY());
                effectsJson.addProperty("maxLength", effects.maxLength());
                entry.add("effects", effectsJson);
            }
            json.add(id.toString(), entry);
        }
        return json;
    }

    /**
     * 解析文件内容；包成一层让自检能不带文件系统地验证容错（因此是 public，别当 API 用）。
     *
     * @return {@code null} 表示整份文件不可用（版本不认识），调用方保持原状态
     */
    public static @Nullable Layout decode(JsonObject root, @Nullable Path fileForLog) {
        int schema = root.has("schemaVersion") ? root.get("schemaVersion").getAsInt() : -1;
        if (schema != SCHEMA_VERSION) {
            LogManager.clientWarn(TAG, "UI layout %s has unsupported schemaVersion %s; ignoring",
                    fileForLog, schema);
            return null;
        }
        return new Layout(
                readEntries(root, "panels", true, fileForLog),
                readEntries(root, "elements", false, fileForLog));
    }

    private static Map<ResourceLocation, UiPanelLayout> readEntries(JsonObject root, String key,
                                                                    boolean withEffects, @Nullable Path fileForLog) {
        JsonObject section = root.has(key) && root.get(key).isJsonObject()
                ? root.getAsJsonObject(key) : new JsonObject();
        Map<ResourceLocation, UiPanelLayout> read = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : section.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(entry.getKey());
            if (id == null) {
                LogManager.clientWarn(TAG, "UI layout %s: '%s' is not a valid %s id; skipped",
                        fileForLog, entry.getKey(), key);
                continue;
            }

            JsonObject json = entry.getValue().getAsJsonObject();
            if (withEffects) {
                warnUnknownAnchor(json, entry.getKey(), fileForLog);
                read.put(id, UiPanelLayout.fromJson(json));
            } else {
                read.put(id, UiPanelLayout.offsetFromJson(json));
            }
        }
        return read;
    }

    /** 手写 {@code "anchor": "left"} 这种：退回右上角，但要说一句，别让它悄悄失效。 */
    private static void warnUnknownAnchor(JsonObject panel, String panelId, @Nullable Path fileForLog) {
        JsonElement effects = panel.get("effects");
        if (effects == null || !effects.isJsonObject()) {
            return;
        }
        JsonObject json = effects.getAsJsonObject();
        if (json.has("anchor") && UiEffectsAnchor.anchor(json) == null) {
            LogManager.clientWarn(TAG, "UI layout %s: panel '%s' has unknown effects.anchor '%s'; using top-right",
                    fileForLog, panelId, json.get("anchor"));
        }
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get()
                .resolve(CatoxidesBattleRebuildConstants.MODID)
                .resolve("ui-layout.json");
    }
}
