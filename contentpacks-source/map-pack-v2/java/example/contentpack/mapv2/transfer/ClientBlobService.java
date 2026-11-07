package example.contentpack.mapv2.transfer;

import com.mojang.logging.LogUtils;
import example.contentpack.mapv2.network.BlobChunkPayload;
import example.contentpack.mapv2.network.BlobRequestPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 客户端分发端点：按 topic 注册接收器，负责请求去重、入站重组、上传排队。
 *
 * <p>只在服务端明确宣称支持时工作（{@link #setEnabled}）——避免向没装本包的
 * 服务端发未知 payload。所有方法都在客户端主线程调用：网络回调经
 * {@code enqueueWork}，tick 与上传由主线程驱动。
 *
 * <p>上行每 tick 最多 {@link #UPLOADS_PER_TICK} 片、队列上限 {@link #MAX_OUTBOX_BYTES}；
 * 溢出时丢弃本次上传，由地图侧的下一次保存重试。
 */
public final class ClientBlobService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int UPLOADS_PER_TICK = 2;
    private static final long MAX_OUTBOX_BYTES = 8L * 1024 * 1024;
    /** 与 {@link BlobAssembler#MAX_CONCURRENT} 对齐：多发的请求会被接收侧拒收并反复重试。 */
    private static final int MAX_OUTSTANDING = BlobAssembler.MAX_CONCURRENT;
    private static final long REQUEST_TIMEOUT_NANOS = 45_000_000_000L;

    public interface Sink {
        /** 完整块到达；{@code data} 非空。 */
        void accept(String key, long revision, byte[] data);
    }

    private static final Map<String, Sink> SINKS = new HashMap<>();
    private static final BlobAssembler ASSEMBLER = new BlobAssembler();
    /** 已发请求、还没答复：key → 发送时刻。 */
    private static final Map<String, Long> PENDING = new HashMap<>();
    /** 本次会话已有结论（收到或服务端也没有）：不再问。 */
    private static final Set<String> DONE = new HashSet<>();
    private static final ArrayDeque<BlobChunkPayload> OUTBOX = new ArrayDeque<>();

    private static boolean enabled;
    private static long outboxBytes;
    private static int diagDownloads = 10;

    private ClientBlobService() {
    }

    public static void registerSink(String topic, Sink sink) {
        SINKS.put(topic, sink);
    }

    /** 服务端世界信息里带回的开关；关掉时清空所有在途状态。 */
    public static void setEnabled(boolean value) {
        if (enabled == value) {
            return;
        }
        enabled = value;
        reset();
        LOGGER.info("[MapPack] server tile sharing {}", enabled ? "enabled" : "disabled");
    }

    public static boolean enabled() {
        return enabled;
    }

    /** 要一块；同一会话内同 key 只问一次，未答复的超时后允许重问。 */
    public static void request(String topic, String key) {
        if (!enabled || !SINKS.containsKey(topic)) {
            return;
        }
        String requestKey = requestKey(topic, key);
        if (DONE.contains(requestKey) || PENDING.containsKey(requestKey)) {
            return;
        }
        if (PENDING.size() >= MAX_OUTSTANDING) {
            return;
        }
        PENDING.put(requestKey, System.nanoTime());
        PacketDistributor.sendToServer(new BlobRequestPayload(topic, key, 0L));
    }

    public static void receive(BlobChunkPayload payload) {
        if (!enabled) {
            return;
        }
        String requestKey = requestKey(payload.topic(), payload.key());
        if (payload.isAbsentMarker()) {
            // 服务端也没有：本次会话不再问同一 key
            complete(requestKey);
            return;
        }
        BlobAssembler.Result result = ASSEMBLER.accept(payload.transferId(), payload.part(),
                payload.parts(), payload.totalBytes(), payload.data(), System.nanoTime());
        if (result.status() == BlobAssembler.Status.REJECTED) {
            LOGGER.warn("[MapPack] download {}/{} rejected: {}",
                    payload.topic(), payload.key(), result.error());
            PENDING.remove(requestKey);
            return;
        }
        if (result.status() != BlobAssembler.Status.COMPLETE) {
            return;
        }
        complete(requestKey);
        Sink sink = SINKS.get(payload.topic());
        if (sink == null) {
            return;
        }
        if (diagDownloads > 0) {
            diagDownloads--;
            LOGGER.info("[MapPack] download {}/{} {} bytes", payload.topic(), payload.key(),
                    result.data().length);
        }
        sink.accept(payload.key(), payload.revision(), result.data());
    }

    /** 排队上传一块；返回 false = 队列满/未开启，调用方稍后重试。 */
    public static boolean upload(String topic, String key, long revision, byte[] data) {
        if (!enabled || data == null || data.length == 0
                || data.length > BlobChunker.MAX_BLOB_BYTES) {
            return false;
        }
        int parts = BlobChunker.parts(data.length);
        if (parts <= 0 || outboxBytes + data.length > MAX_OUTBOX_BYTES) {
            return false;
        }
        UUID transferId = UUID.randomUUID();
        for (int part = 0; part < parts; part++) {
            OUTBOX.add(new BlobChunkPayload(transferId, topic, key, part, parts,
                    data.length, revision, BlobChunker.part(data, part)));
        }
        outboxBytes += data.length;
        return true;
    }

    /** 每客户端 tick 调用。 */
    public static void tick() {
        long now = System.nanoTime();
        ASSEMBLER.expire(now);
        dropTimedOutRequests(now);
        if (OUTBOX.isEmpty()) {
            return;
        }
        int sent = 0;
        while (!OUTBOX.isEmpty() && sent < UPLOADS_PER_TICK) {
            BlobChunkPayload payload = OUTBOX.poll();
            outboxBytes -= payload.data().length;
            PacketDistributor.sendToServer(payload);
            sent++;
        }
    }

    /** 清会话状态；topic 接收器是注册信息，不随会话清掉。 */
    public static void reset() {
        ASSEMBLER.clear();
        PENDING.clear();
        DONE.clear();
        OUTBOX.clear();
        outboxBytes = 0;
        diagDownloads = 10;
    }

    private static void complete(String requestKey) {
        PENDING.remove(requestKey);
        DONE.add(requestKey);
    }

    private static void dropTimedOutRequests(long nowNanos) {
        Iterator<Map.Entry<String, Long>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (nowNanos - entry.getValue() >= REQUEST_TIMEOUT_NANOS) {
                iterator.remove();
            }
        }
    }

    private static String requestKey(String topic, String key) {
        return topic + '\u0000' + key;
    }
}
