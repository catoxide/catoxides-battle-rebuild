package example.contentpack.map.client;

import com.mojang.logging.LogUtils;
import example.contentpack.map.MapConfig;
import example.contentpack.map.MapStorage;
import example.contentpack.map.MapStore;
import example.contentpack.map.network.MapWorldIdRequestPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

/**
 * 当前世界的身份（客户端侧）。
 *
 * <p>身份分两层：
 * <ul>
 *   <li>{@code worldId}（存档 UUID）——正常匹配，不打扰；</li>
 *   <li>{@code seed + generator}——UUID 对不上时（复制存档/回档/打包部署）找候选，
 *       由玩家选择是否继承，继承方式是把对方地图复制过来。</li>
 * </ul>
 * 拿到世界信息之前不采集、不渲染。
 */
public final class MapWorld {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long REQUEST_INTERVAL_NANOS = 1_000_000_000L;

    private static volatile UUID worldId;
    private static long seed;
    private static String generator = "";
    private static String worldName = "";
    private static MapWorldMeta meta;
    private static boolean existingMap;

    private static long lastRequestNanos;
    private static List<MapWorldMeta> pendingCandidates = List.of();

    private MapWorld() {
    }

    public static UUID id() {
        return worldId;
    }

    public static long seed() {
        return seed;
    }

    public static String worldName() {
        return worldName;
    }

    /** 本地是否已有这个世界的地图（登录提示用）。 */
    public static boolean hasExistingMap() {
        return existingMap;
    }

    public static void reset() {
        worldId = null;
        seed = 0L;
        generator = "";
        worldName = "";
        meta = null;
        existingMap = false;
        lastRequestNanos = 0;
        pendingCandidates = List.of();
    }

    /** 每秒最多请求一次；连接不存在时不发。 */
    public static void requestIfDue() {
        if (worldId != null || Minecraft.getInstance().getConnection() == null) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastRequestNanos < REQUEST_INTERVAL_NANOS) {
            return;
        }
        lastRequestNanos = now;
        PacketDistributor.sendToServer(new MapWorldIdRequestPayload());
    }

    /** 收到服务端下发的世界信息。 */
    public static void receive(UUID id, long worldSeed, String worldGenerator, String name) {
        if (id == null) {
            return;
        }
        boolean changed = worldId == null || !worldId.equals(id);
        if (worldId != null && !worldId.equals(id)) {
            // 同一进程内换了世界：内存里的地图属于旧世界
            MapStore.clearAll();
            MapMinimap.resetLayerOverride();
        }
        worldId = id;
        seed = worldSeed;
        generator = worldGenerator == null ? "" : worldGenerator;
        worldName = name == null ? "" : name;

        boolean hadDirectory = Files.isDirectory(MapStorage.worldDir(id));
        meta = MapWorldMeta.loadOrCreate(id, seed, generator, worldName);
        meta.save();
        existingMap = hadDirectory;

        if (!changed) {
            return;
        }
        LOGGER.info("[MapPack] world id={} seed={} generator={} name={} ({})",
                id, seed, generator, worldName, existingMap ? "existing dir" : "new");

        if (!meta.inheritAsked() && MapConfig.inheritPrompt()) {
            scanCandidatesAsync();
        } else {
            announce(existingMap
                    ? "catoxidesbattlerebuild.map.existing_world"
                    : "catoxidesbattlerebuild.map.new_world");
        }
    }

    /** 每客户端 tick 调用：有候选就弹选择屏（只在地图/其它界面关闭时）。 */
    public static void tickScreen() {
        if (pendingCandidates.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        List<MapWorldMeta> candidates = pendingCandidates;
        pendingCandidates = List.of();
        minecraft.setScreen(new MapInheritScreen(candidates));
    }

    /** 继承某个世界的地图：复制成当前世界自己的数据，两份世界互不依赖。 */
    public static void inheritFrom(UUID source) {
        MapWorldMeta current = meta;
        UUID target = worldId;
        if (current == null || target == null || source == null) {
            return;
        }
        MapStorage.flushAll();
        announce("catoxidesbattlerebuild.map.inherit_started");
        MapStorage.copyWorldData(source, target, () -> {
            meta = current.withInherited(source);
            meta.save();
            MapStore.clearAll();
            MapStorage.clear();
            announce("catoxidesbattlerebuild.map.inherit_done");
            LOGGER.info("[MapPack] inherited map from {}", source);
        });
    }

    public static void skipInheritance() {
        if (meta == null) {
            return;
        }
        meta = meta.withAsked();
        meta.save();
        announce("catoxidesbattlerebuild.map.new_world");
    }

    /** 当前世界的可选继承来源（给 UI 显示用）。 */
    public static List<UUID> inheritedFrom() {
        return meta == null ? List.of() : meta.inheritedFrom();
    }

    private static void scanCandidatesAsync() {
        UUID self = worldId;
        long worldSeed = seed;
        String worldGenerator = generator;
        MapStorage.ioExecute(() -> {
            List<MapWorldMeta> candidates = MapWorldMeta.findCandidates(self, worldSeed, worldGenerator);
            if (candidates.isEmpty()) {
                Minecraft.getInstance().execute(() -> {
                    if (meta != null && !meta.inheritAsked()) {
                        meta = meta.withAsked();
                        meta.save();
                    }
                });
                return;
            }
            LOGGER.info("[MapPack] found {} inheritable map(s) with same seed+generator", candidates.size());
            Minecraft.getInstance().execute(() -> pendingCandidates = candidates);
        });
    }

    private static void announce(String translationKey) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable(translationKey), false);
        }
    }
}
