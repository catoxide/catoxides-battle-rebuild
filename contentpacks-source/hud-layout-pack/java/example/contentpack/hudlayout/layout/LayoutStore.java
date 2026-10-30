package example.contentpack.hudlayout.layout;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 玩家摆位的持久化：每组的偏移（相对原版位置，GUI 缩放单位）+ 移动轴约束。
 *
 * <p>稀疏存储：既没动过也没约束的组不写键，{@code R} 重置即删键。文件在
 * {@code config/catoxidesbattlerebuild/hud-layout.json}，打包后仍可手改。
 * 运行时只读不可变快照，写入后整体替换——渲染线程无锁读取。
 *
 * <p>schema 2 在每组成员里多了 {@code axis}。加载同时接受 1 和 2（1 视作 {@link MoveAxis#FREE}），
 * 保存一律写 2 —— 老文件里的偏移不会因为升版而丢，玩家自己摆好的位置是资产。
 */
public final class LayoutStore {

    private static final String TAG = "HudLayout";
    private static final int SCHEMA_VERSION = 2;
    /** 仍可读的最低版本；升 schema 时把上一版加进来，别让玩家重摆一次。 */
    private static final int MIN_READABLE_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 一组的全部可持久化状态。 */
    public record State(int x, int y, MoveAxis axis) {

        State withAxis(MoveAxis next) {
            return new State(next.limitX(x), next.limitY(y), next);
        }

        /** 全零 + 无约束 = 与「没写过这一组」等价，不必占一个键。 */
        boolean isDefault() {
            return x == 0 && y == 0 && axis == MoveAxis.FREE;
        }
    }

    private static volatile Map<String, State> states = Map.of();
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
            Map<String, State> read = decode(parsed.getAsJsonObject(), file);
            if (read != null) {
                states = Map.copyOf(read);
                LogManager.clientInfo(TAG, "Loaded %d HUD group(s) of layout from %s", read.size(), file);
            }
        } catch (RuntimeException | IOException e) {
            LogManager.clientError(TAG, "Cannot read HUD layout " + file + ": " + e.getMessage());
        }
    }

    /**
     * 解析文件内容；包成一层让自检能不带文件系统地验证版本迁移（因此是 public，别当 API 用）。
     *
     * @return {@code null} 表示整份文件不可用（版本不认识），调用方保持原状态
     */
    @Nullable
    public static Map<String, State> decode(JsonObject root, @Nullable Path fileForLog) {
        int schema = root.has("schemaVersion") ? root.get("schemaVersion").getAsInt() : -1;
        if (schema < MIN_READABLE_VERSION || schema > SCHEMA_VERSION) {
            LogManager.clientWarn(TAG, "HUD layout %s has unsupported schemaVersion %s; ignoring",
                    fileForLog, schema);
            return null;
        }

        Map<String, State> read = new HashMap<>();
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
            int x = value.get("x").getAsInt();
            int y = value.get("y").getAsInt();
            MoveAxis axis = readAxis(value, entry.getKey(), fileForLog);
            read.put(entry.getKey(), new State(x, y, axis));
        }
        return read;
    }

    /** schema 1 没有 {@code axis} 键 —— 缺失即 {@link MoveAxis#FREE}，写错才告警。 */
    private static MoveAxis readAxis(JsonObject value, String group, @Nullable Path fileForLog) {
        if (!value.has("axis") || !value.get("axis").isJsonPrimitive()) {
            return MoveAxis.FREE;
        }
        String name = value.get("axis").getAsString();
        MoveAxis axis = MoveAxis.fromJson(name);
        if (axis == null) {
            LogManager.clientWarn(TAG, "HUD layout %s: group '%s' has unknown axis '%s'; treating as free",
                    fileForLog, group, name);
            return MoveAxis.FREE;
        }
        return axis;
    }

    public static int x(String group) {
        State state = states.get(group);
        return state == null ? 0 : state.x();
    }

    public static int y(String group) {
        State state = states.get(group);
        return state == null ? 0 : state.y();
    }

    public static MoveAxis axis(String group) {
        State state = states.get(group);
        return state == null ? MoveAxis.FREE : state.axis();
    }

    /** 写偏移；被锁住的轴当场折回 0，调用方不必自己判断约束。 */
    public static synchronized void set(String group, int dx, int dy) {
        MoveAxis axis = axis(group);
        write(group, new State(axis.limitX(dx), axis.limitY(dy), axis));
    }

    /**
     * 改约束。锁上某个轴时该轴当场折回原位置——否则「只能上下移动」会留下一个
     * 拖不动也清不掉的横偏移，看起来像卡住了。
     */
    public static synchronized void setAxis(String group, MoveAxis axis) {
        State current = states.get(group);
        write(group, (current == null ? new State(0, 0, MoveAxis.FREE) : current).withAxis(axis));
    }

    /** 只清偏移，保留玩家选的约束。 */
    public static synchronized void reset(String group) {
        State current = states.get(group);
        if (current == null) {
            return;
        }
        write(group, new State(0, 0, current.axis()));
    }

    private static void write(String group, State state) {
        Map<String, State> next = new HashMap<>(states);
        if (state.isDefault()) {
            next.remove(group);
        } else {
            next.put(group, state);
        }
        states = Map.copyOf(next);
    }

    public static synchronized void save() {
        JsonObject groups = new JsonObject();
        states.forEach((group, state) -> {
            JsonObject value = new JsonObject();
            value.addProperty("x", state.x());
            value.addProperty("y", state.y());
            value.addProperty("axis", state.axis().jsonName());
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
