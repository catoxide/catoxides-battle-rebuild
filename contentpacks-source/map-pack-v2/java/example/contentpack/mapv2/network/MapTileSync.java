package example.contentpack.mapv2.network;

import com.mojang.logging.LogUtils;
import example.contentpack.mapv2.MapStorage;
import example.contentpack.mapv2.transfer.BlobTopic;
import example.contentpack.mapv2.transfer.ClientBlobService;
import example.contentpack.mapv2.transfer.DiskBlobStore;
import example.contentpack.mapv2.transfer.ServerBlobService;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 地图瓦片主题：把「按 512 分区存盘的同构瓦片」挂到通用 blob 分发上。
 *
 * <p>key = {@code 维度|层|x|z}；服务端只做格式校验（MPT1 头）后按 key 哈希落盘，
 * 不解析像素。客户端缺块时请求、收到后走 {@link MapStorage#acceptRemoteTile}，
 * 与本地采集共用同一套磁盘格式。
 *
 * <p>专用服务器始终开启（受 {@code sync.shareTiles} 控制）；集成服务器在「对局域网开放」后
 * 由 tick 监听检测 {@link MinecraftServer#isPublished()} 翻转，并把世界信息重播一遍，
 * 让主机客户端也开始上传。单机未开放局域网时分发保持关闭，不产生第二份瓦片数据。
 */
public final class MapTileSync {

    public static final String TOPIC = "map.tile";
    /** 同一块瓦片两次上传的最小间隔：采集会持续改像素，不能每 20 tick 全推一遍。 */
    private static final long UPLOAD_COOLDOWN_NANOS = 60_000_000_000L;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, Long> LAST_UPLOAD = new HashMap<>();

    private static boolean listenersInstalled;
    /** 本服务端上一次广播的共享状态；只在翻转时重播世界信息。 */
    private static boolean lastShared;

    private MapTileSync() {
    }

    /** 服务端世界启动后注册存储；是否真正对外分发由 {@link #sharing} 决定。 */
    public static void initServer(MinecraftServer server) {
        if (!TileSyncConfig.shareTiles()) {
            return;
        }
        Path root = server.getWorldPath(new LevelResource("map_pack_tiles"));
        ServerBlobService.register(new TileTopic(new DiskBlobStore(TOPIC, root)));
        lastShared = sharing(server);
        installListeners();
        LOGGER.info("[MapPack] tile sharing root={} dedicated={}",
                root, server.isDedicatedServer());
    }

    /** 专用服务器恒为开启；集成服务器在开放局域网后变为开启。 */
    public static boolean sharing(MinecraftServer server) {
        return TileSyncConfig.shareTiles()
                && (server.isDedicatedServer() || server.isPublished());
    }

    private static void installListeners() {
        if (listenersInstalled) {
            return;
        }
        listenersInstalled = true;
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
            MinecraftServer server = event.getServer();
            boolean now = sharing(server);
            if (now == lastShared) {
                return;
            }
            lastShared = now;
            LOGGER.info("[MapPack] tile sharing {} (dedicated={}, published={})",
                    now ? "enabled" : "disabled", server.isDedicatedServer(), server.isPublished());
            MapNetwork.broadcastWorldId(server);
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> lastShared = false);
    }

    /** 客户端注册接收器；与世界无关，只注册一次。 */
    public static void registerClient() {
        ClientBlobService.registerSink(TOPIC, (key, revision, data) -> {
            TileRef ref = parse(key);
            if (ref == null) {
                return;
            }
            MapStorage.acceptRemoteTile(ref.dimension(), ref.layer(), ref.x(), ref.z(),
                    revision, data);
        });
    }

    /** 本地缺块时调用；去重/限流都在 {@link ClientBlobService}。 */
    public static void requestTile(ResourceLocation dimension, String layer, int tileX, int tileZ) {
        ClientBlobService.request(TOPIC, key(dimension, layer, tileX, tileZ));
    }

    /** 落盘成功后调用；带每块 60 秒冷却。 */
    public static void offerUpload(ResourceLocation dimension, String layer, int tileX, int tileZ,
                                   byte[] data) {
        if (!ClientBlobService.enabled()) {
            return;
        }
        String key = key(dimension, layer, tileX, tileZ);
        long now = System.nanoTime();
        Long last = LAST_UPLOAD.get(key);
        if (last != null && now - last < UPLOAD_COOLDOWN_NANOS) {
            return;
        }
        if (ClientBlobService.upload(TOPIC, key, 0L, data)) {
            LAST_UPLOAD.put(key, now);
        }
    }

    /** 换世界/退出时清冷却表。 */
    public static void reset() {
        LAST_UPLOAD.clear();
    }

    public static String key(ResourceLocation dimension, String layer, int tileX, int tileZ) {
        return dimension + "|" + layer + "|" + tileX + "|" + tileZ;
    }

    static TileRef parse(String key) {
        if (key == null) {
            return null;
        }
        int zSep = key.lastIndexOf('|');
        int xSep = zSep <= 0 ? -1 : key.lastIndexOf('|', zSep - 1);
        int layerSep = xSep <= 0 ? -1 : key.lastIndexOf('|', xSep - 1);
        if (layerSep <= 0) {
            return null;
        }
        try {
            ResourceLocation dimension = ResourceLocation.tryParse(key.substring(0, layerSep));
            if (dimension == null) {
                return null;
            }
            String layer = key.substring(layerSep + 1, xSep);
            int x = Integer.parseInt(key.substring(xSep + 1, zSep));
            int z = Integer.parseInt(key.substring(zSep + 1));
            return new TileRef(dimension, layer, x, z);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public record TileRef(ResourceLocation dimension, String layer, int x, int z) {
    }

    /** 只接受能通过 MPT1 头校验的字节，避免把坏数据转给其它客户端。 */
    private record TileTopic(DiskBlobStore store) implements BlobTopic {

        @Override
        public String id() {
            return store.id();
        }

        @Override
        public Blob fetch(String key, long knownRevision) {
            return store.fetch(key, knownRevision);
        }

        @Override
        public long store(String key, byte[] data) {
            if (!MapStorage.looksLikeTile(data)) {
                LOGGER.warn("[MapPack] reject malformed tile upload: {}", key);
                return -1L;
            }
            return store.store(key, data);
        }

        @Override
        public String validateKey(String key) {
            return parse(key) == null ? "bad tile key" : null;
        }
    }
}
