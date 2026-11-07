package example.contentpack.mapv2.network;

import com.mojang.logging.LogUtils;
import example.contentpack.mapv2.reveal.RevealChunkSet;
import example.contentpack.mapv2.reveal.RevealGrid;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 服务端维护「所有玩家探过的范围」。
 *
 * <p>每秒把每个在线玩家周围 {@link RevealConfig#radiusChunks()} 个区块并入持久化集合，
 * 新增的格按维度广播给同维度玩家；玩家进入维度时另发一次全量快照。
 * 集合跨重启保留（{@link RevealStore}），所以探索过的边界不会因为玩家离开而收缩。
 *
 * <p>半径 0 = 关闭：不建存储、不回快照，客户端保持旧行为（预测铺满视口）。
 */
public final class RevealServerService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SAMPLE_INTERVAL_TICKS = 20;
    private static final int SAVE_INTERVAL_TICKS = 1200;
    private static final long[] EMPTY = new long[0];

    private static RevealStore store;
    private static MinecraftServer activeServer;
    private static boolean listenersInstalled;
    private static int ticks;

    private RevealServerService() {
    }

    public static void init(MinecraftServer server) {
        if (RevealConfig.radiusChunks() <= 0) {
            return;
        }
        activeServer = server;
        if (store == null) {
            Path root = server.getWorldPath(new LevelResource("map_pack_revealed"));
            store = new RevealStore(root);
            LOGGER.info("[MapPack] reveal radius={} chunks root={}",
                    RevealConfig.radiusChunks(), root);
        }
        installListeners();
    }

    /** 客户端进入维度时请求一次快照。 */
    public static void handleRequest(ServerPlayer player, MapRevealRequestPayload request) {
        if (store == null || activeServer == null) {
            return;
        }
        ResourceLocation dimension = request.dimension();
        if (!player.level().dimension().location().equals(dimension)) {
            return;
        }
        RevealChunkSet set = store.get(dimension);
        if (set.isEmpty()) {
            // 空快照也是一次有效载荷：告诉客户端「闸门开着，只是这里还没探过」
            PacketDistributor.sendToPlayer(player, new MapRevealPayload(dimension, EMPTY, EMPTY));
            return;
        }
        long[] keys = new long[set.size()];
        long[] masks = new long[set.size()];
        int index = 0;
        for (Long2LongMap.Entry entry : set.cells().long2LongEntrySet()) {
            keys[index] = entry.getLongKey();
            masks[index] = entry.getLongValue();
            index++;
        }
        sendBatches(payload -> PacketDistributor.sendToPlayer(player, payload),
                dimension, keys, masks);
        LOGGER.info("[MapPack] reveal snapshot {} cells={} to {}",
                dimension, set.size(), player.getGameProfile().getName());
    }

    private static void installListeners() {
        if (listenersInstalled) {
            return;
        }
        listenersInstalled = true;
        NeoForge.EVENT_BUS.addListener(
                (ServerTickEvent.Post event) -> tick(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> stop());
    }

    private static void tick(MinecraftServer server) {
        if (store == null || server != activeServer) {
            return;
        }
        ticks++;
        if (ticks % SAMPLE_INTERVAL_TICKS == 0) {
            sample(server);
        }
        if (ticks % SAVE_INTERVAL_TICKS == 0) {
            store.save();
        }
    }

    private static void sample(MinecraftServer server) {
        int radius = RevealConfig.radiusChunks();
        if (radius <= 0) {
            return;
        }
        Map<ResourceLocation, Map<Long, Long>> changed = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.serverLevel();
            ResourceLocation dimension = level.dimension().location();
            ChunkPos center = player.chunkPosition();
            int minCellX = RevealGrid.cellOfChunk(center.x - radius);
            int maxCellX = RevealGrid.cellOfChunk(center.x + radius);
            int minCellZ = RevealGrid.cellOfChunk(center.z - radius);
            int maxCellZ = RevealGrid.cellOfChunk(center.z + radius);
            RevealChunkSet set = store.get(dimension);
            Map<Long, Long> delta = changed.computeIfAbsent(dimension, ignored -> new HashMap<>());
            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                    long mask = RevealGrid.maskForCell(cellX, cellZ, center.x, center.z, radius);
                    if (mask == 0L) {
                        continue;
                    }
                    long added = set.add(cellX, cellZ, mask);
                    if (added != 0L) {
                        delta.merge(RevealGrid.key(cellX, cellZ), added, (a, b) -> a | b);
                    }
                }
            }
        }
        if (changed.isEmpty()) {
            return;
        }
        store.markDirty();
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        for (Map.Entry<ResourceLocation, Map<Long, Long>> entry : changed.entrySet()) {
            Map<Long, Long> delta = entry.getValue();
            if (delta.isEmpty()) {
                continue;
            }
            List<ServerPlayer> recipients = new ArrayList<>();
            for (ServerPlayer player : players) {
                if (player.level().dimension().location().equals(entry.getKey())) {
                    recipients.add(player);
                }
            }
            if (recipients.isEmpty()) {
                continue;
            }
            long[] keys = new long[delta.size()];
            long[] masks = new long[delta.size()];
            int index = 0;
            for (Map.Entry<Long, Long> cell : delta.entrySet()) {
                keys[index] = cell.getKey();
                masks[index] = cell.getValue();
                index++;
            }
            for (ServerPlayer player : recipients) {
                sendBatches(payload -> PacketDistributor.sendToPlayer(player, payload),
                        entry.getKey(), keys, masks);
            }
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[MapPack] reveal delta dimensions={}", changed.size());
        }
    }

    private static void sendBatches(Consumer<MapRevealPayload> sink,
                                    ResourceLocation dimension, long[] keys, long[] masks) {
        for (int from = 0; from < keys.length; from += MapRevealPayload.MAX_ENTRIES) {
            int to = Math.min(keys.length, from + MapRevealPayload.MAX_ENTRIES);
            sink.accept(new MapRevealPayload(dimension,
                    Arrays.copyOfRange(keys, from, to),
                    Arrays.copyOfRange(masks, from, to)));
        }
    }

    private static void stop() {
        if (store != null) {
            store.save();
        }
        store = null;
        activeServer = null;
        ticks = 0;
    }
}