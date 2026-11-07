package example.contentpack.mapv2.transfer;

import com.mojang.logging.LogUtils;
import example.contentpack.mapv2.network.BlobChunkPayload;
import example.contentpack.mapv2.network.BlobRequestPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 服务端分发中心：按 topic 存取块，给每个玩家限速下发，接收客户端上传。
 *
 * <p>线程模型：
 * <ul>
 *   <li>网络回调 → {@code enqueueWork} 到服务端主线程，只做重组/入队；</li>
 *   <li>磁盘读写全在单线程执行器，读完再 {@code server.execute} 回主线程入队；</li>
 *   <li>发送在 {@code ServerTickEvent.Post}，每 tick 每玩家最多 {@link #CHUNKS_PER_TICK} 片，
 *       并按 {@link TransferBudget} 限字节，避免一次性把视口内的瓦片全推出去。</li>
 * </ul>
 *
 * <p>冲突策略：上传直接覆盖（最新写入胜）。这里不做权限判断，只做格式校验：
 * topic 必须注册、key 由 topic 校验、分片元数据由 {@link BlobAssembler} 校验。
 */
public final class ServerBlobService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CHUNKS_PER_TICK = 4;
    private static final long MAX_QUEUED_BYTES = 8L * 1024 * 1024;
    private static final int REQUESTS_PER_SECOND = 16;
    private static final long SECOND_NANOS = 1_000_000_000L;

    private static final Map<String, BlobTopic> TOPICS = new ConcurrentHashMap<>();
    private static final Map<UUID, Peer> PEERS = new HashMap<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "map-pack-blobs");
        thread.setDaemon(true);
        return thread;
    });

    private static boolean initialized;
    private static long bytesPerSecond = 256L * 1024L;
    private static int diagTransfers = 10;

    private ServerBlobService() {
    }

    public static void init(long outboundBytesPerSecond) {
        bytesPerSecond = Math.max(16L * 1024L, outboundBytesPerSecond);
        if (initialized) {
            return;
        }
        initialized = true;
        NeoForge.EVENT_BUS.addListener(
                (ServerTickEvent.Post event) -> flush(event.getServer()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                PEERS.remove(player.getUUID());
            }
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            PEERS.clear();
            TOPICS.clear();
        });
    }

    public static void register(BlobTopic topic) {
        TOPICS.put(topic.id(), topic);
        LOGGER.info("[MapPack] blob topic '{}' ready", topic.id());
    }

    /** 客户端请求下载。 */
    public static void handleRequest(ServerPlayer player, BlobRequestPayload request) {
        BlobTopic topic = TOPICS.get(request.topic());
        if (topic == null) {
            return;
        }
        String problem = topic.validateKey(request.key());
        if (problem != null) {
            return;
        }
        Peer peer = peer(player.getUUID());
        long now = System.nanoTime();
        if (!peer.allowRequest(now)) {
            return;
        }
        UUID playerId = player.getUUID();
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        String topicId = request.topic();
        String key = request.key();
        long knownRevision = request.knownRevision();
        IO.execute(() -> {
            BlobTopic.Blob blob = topic.fetch(key, knownRevision);
            server.execute(() -> {
                ServerPlayer online = server.getPlayerList().getPlayer(playerId);
                if (online != null) {
                    enqueue(online, topicId, key, blob);
                }
            });
        });
    }

    /** 客户端上传分片。 */
    public static void handleChunk(ServerPlayer player, BlobChunkPayload payload) {
        BlobTopic topic = TOPICS.get(payload.topic());
        if (topic == null || payload.isAbsentMarker()) {
            return;
        }
        String problem = topic.validateKey(payload.key());
        if (problem != null) {
            return;
        }
        Peer peer = peer(player.getUUID());
        BlobAssembler.Result result = peer.incoming.accept(payload.transferId(), payload.part(),
                payload.parts(), payload.totalBytes(), payload.data(), System.nanoTime());
        if (result.status() == BlobAssembler.Status.REJECTED) {
            LOGGER.warn("[MapPack] upload rejected topic={} key={}: {}",
                    payload.topic(), payload.key(), result.error());
            return;
        }
        if (result.status() != BlobAssembler.Status.COMPLETE) {
            return;
        }
        byte[] data = result.data();
        String topicId = payload.topic();
        String key = payload.key();
        if (diagTransfers > 0) {
            diagTransfers--;
            LOGGER.info("[MapPack] upload {}/{} {} bytes from {}",
                    topicId, key, data.length, player.getGameProfile().getName());
        }
        IO.execute(() -> topic.store(key, data));
    }

    private static void enqueue(ServerPlayer player, String topicId, String key,
                               BlobTopic.Blob blob) {
        Peer peer = peer(player.getUUID());
        if (blob == null) {
            peer.offer(BlobChunkPayload.absent(topicId, key));
            return;
        }
        if (blob.unchanged()) {
            return;
        }
        byte[] data = blob.data();
        int parts = BlobChunker.parts(data.length);
        if (parts <= 0) {
            peer.offer(BlobChunkPayload.absent(topicId, key));
            return;
        }
        if (peer.queuedBytes + data.length > MAX_QUEUED_BYTES) {
            // 队列满就丢这次请求；客户端下次进入视野会重新请求
            return;
        }
        UUID transferId = UUID.randomUUID();
        for (int part = 0; part < parts; part++) {
            peer.offer(new BlobChunkPayload(transferId, topicId, key, part, parts,
                    data.length, blob.revision(), BlobChunker.part(data, part)));
        }
        if (diagTransfers > 0) {
            diagTransfers--;
            LOGGER.info("[MapPack] download {}/{} {} bytes ({} part(s)) to {}",
                    topicId, key, data.length, parts, player.getGameProfile().getName());
        }
    }

    private static void flush(MinecraftServer server) {
        if (PEERS.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Peer peer = PEERS.get(player.getUUID());
            if (peer == null) {
                continue;
            }
            peer.incoming.expire(now);
            int sent = 0;
            while (!peer.outbound.isEmpty() && sent < CHUNKS_PER_TICK) {
                BlobChunkPayload payload = peer.outbound.peek();
                int wireBytes = payload.data().length + 64;
                if (!peer.budget.tryTake(wireBytes, now)) {
                    break;
                }
                peer.outbound.poll();
                peer.queuedBytes -= payload.data().length;
                PacketDistributor.sendToPlayer(player, payload);
                sent++;
            }
        }
    }

    private static Peer peer(UUID playerId) {
        return PEERS.computeIfAbsent(playerId, ignored -> new Peer());
    }

    private static final class Peer {
        private final BlobAssembler incoming = new BlobAssembler();
        private final TransferBudget budget = new TransferBudget(bytesPerSecond);
        private final ArrayDeque<BlobChunkPayload> outbound = new ArrayDeque<>();
        private long queuedBytes;
        private long requestWindowStartNanos;
        private int requestsInWindow;

        private boolean allowRequest(long nowNanos) {
            if (nowNanos - requestWindowStartNanos >= SECOND_NANOS) {
                requestWindowStartNanos = nowNanos;
                requestsInWindow = 0;
            }
            if (requestsInWindow >= REQUESTS_PER_SECOND) {
                return false;
            }
            requestsInWindow++;
            return true;
        }

        private void offer(BlobChunkPayload payload) {
            outbound.add(payload);
            queuedBytes += payload.data().length;
        }
    }
}
