package example.contentpack.hudlayout.layout;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 拖动结果（相对原版位置的偏移，GUI 缩放单位）的持久化。
 *
 * <p>稀疏存储：没动过的组不写键；{@code R} 重置即删键。文件在
 * {@code config/catoxidesbattlerebuild/hud-layout.json}，打包后仍可手改。
 * 运行时只读不可变快照，写入后整体替换——渲染线程无锁读取。
 */
public final class LayoutStore {

    private static final String TAG = "HudLayout";
    private static final int SCHEMA_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile Map<String, int[]> offsets = Map.of();
    private static volatile boolean loaded;

    private LayoutStore() {
    }

    public static void load() {
        if (loaded) {
            return;
        }
        loaded = true;

        Path file = file();
        if (!Files.isRegularFile(file)) {
            LogManager.clientInfo(TAG, "No saved HUD layout at %s; using vanilla positions", file);
            return;
        }

        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                LogManager.clientWarn(TAG, "HUD layout %s is not a JSON object; ignoring", file);
                return;
            }
            JsonObject root = parsed.getAsJsonObject();
            int schema = root.has("schemaVersion") ? root.get("schemaVersion").getAsInt() : -1;
            if (schema != SCHEMA_VERSION) {
                LogManager.clientWarn(TAG, "HUD layout %s has unsupported schemaVersion %s; ignoring", file, schema);
                return;
            }

            Map<String, int[]> read = new HashMap<>();
            JsonObject groups = root.has("groups") && root.get("groups").isJsonObject()
                    ? root.getAsJsonObject("groups") : new JsonObject();
            for (Map.Entry<String, JsonElement> entry : groups.entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject value = entry.getValue().getAsJsonObject();
                if (!value.has("x") || !value.has("y")) {
                    continue;
                }
                read.put(entry.getKey(), new int[]{value.get("x").getAsInt(), value.get("y").getAsInt()});
            }
            offsets = Map.copyOf(read);
            LogManager.clientInfo(TAG, "Loaded %d HUD group offset(s) from %s", read.size(), file);
        } catch (RuntimeException | IOException e) {
            LogManager.clientError(TAG, "Cannot read HUD layout " + file + ": " + e.getMessage());
        }
    }

    public static int x(String group) {
        int[] offset = offsets.get(group);
        return offset == null ? 0 : offset[0];
    }

    public static int y(String group) {
        int[] offset = offsets.get(group);
        return offset == null ? 0 : offset[1];
    }

    public static synchronized void set(String group, int dx, int dy) {
        Map<String, int[]> next = new HashMap<>(offsets);
        next.put(group, new int[]{dx, dy});
        offsets = Map.copyOf(next);
    }

    public static synchronized void reset(String group) {
        if (!offsets.containsKey(group)) {
            return;
        }
        Map<String, int[]> next = new HashMap<>(offsets);
        next.remove(group);
        offsets = Map.copyOf(next);
    }

    public static synchronized void save() {
        JsonObject groups = new JsonObject();
        offsets.forEach((group, offset) -> {
            JsonObject value = new JsonObject();
            value.addProperty("x", offset[0]);
            value.addProperty("y", offset[1]);
            groups.add(group, value);
        });

        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.add("groups", groups);

        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LogManager.clientError(TAG, "Cannot write HUD layout " + file + ": " + e.getMessage());
        }
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get()
                .resolve(CatoxidesBattleRebuildConstants.MODID)
                .resolve("hud-layout.json");
    }
}
