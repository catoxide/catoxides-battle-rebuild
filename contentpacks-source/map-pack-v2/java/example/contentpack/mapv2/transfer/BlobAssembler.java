package example.contentpack.mapv2.transfer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 入站分片重组。纯逻辑，只认「传输 id + 片号 + 元数据 + 字节」。
 *
 * <p>所有异常都<b>丢弃整个传输</b>而不是忽略单片：分片协议一旦出现元数据不一致、
 * 重复片或越界，继续拼出来的数据也不可信，直接让上层重来。
 *
 * <p>内存有界：同时在传的传输数、单传输总字节、单片刻度都设上限；
 * 超时未完成的传输由 {@link #expire(long)} 回收（调用方每 tick 扫一次）。
 */
public final class BlobAssembler {

    public static final long TIMEOUT_NANOS = 30_000_000_000L;
    public static final int MAX_CONCURRENT = 4;

    public enum Status {
        /** 还缺分片。 */
        INCOMPLETE,
        /** 完整，{@link Result#data()} 可用。 */
        COMPLETE,
        /** 协议违规或资源超限，该传输已丢弃。 */
        REJECTED
    }

    public record Result(Status status, byte[] data, String error) {

        static Result incomplete() {
            return new Result(Status.INCOMPLETE, null, null);
        }

        static Result complete(byte[] data) {
            return new Result(Status.COMPLETE, data, null);
        }

        static Result rejected(String error) {
            return new Result(Status.REJECTED, null, error);
        }
    }

    private static final class Pending {
        private final int parts;
        private final int totalBytes;
        private final long startedNanos;
        private final byte[][] chunks;
        private int received;
        private long bytes;

        private Pending(int parts, int totalBytes, long startedNanos) {
            this.parts = parts;
            this.totalBytes = totalBytes;
            this.startedNanos = startedNanos;
            this.chunks = new byte[parts][];
        }
    }

    private final Map<UUID, Pending> pending = new LinkedHashMap<>();
    private final int maxConcurrent;
    private final long timeoutNanos;

    public BlobAssembler() {
        this(MAX_CONCURRENT, TIMEOUT_NANOS);
    }

    public BlobAssembler(int maxConcurrent, long timeoutNanos) {
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.timeoutNanos = Math.max(1, timeoutNanos);
    }

    /**
     * 收一片。
     *
     * <p>{@code parts <= 0} 不是分片，是上层约定的「目标不存在」标记，调用方自己处理，
     * 不应交给这里。
     */
    public Result accept(UUID transferId, int part, int parts, int totalBytes, byte[] data,
                         long nowNanos) {
        if (transferId == null) {
            return Result.rejected("missing transfer id");
        }
        if (parts <= 0 || part < 0 || part >= parts) {
            return Result.rejected("part out of range: " + part + "/" + parts);
        }
        if (totalBytes <= 0 || totalBytes > BlobChunker.MAX_BLOB_BYTES) {
            return Result.rejected("bad blob size: " + totalBytes);
        }
        if (parts > BlobChunker.MAX_PARTS) {
            return Result.rejected("too many parts: " + parts);
        }
        if (data == null || data.length == 0 || data.length > BlobChunker.CHUNK_BYTES) {
            return Result.rejected("bad part length: " + (data == null ? -1 : data.length));
        }

        Pending transfer = pending.get(transferId);
        if (transfer == null) {
            expire(nowNanos);
            if (pending.size() >= maxConcurrent) {
                return Result.rejected("too many concurrent transfers");
            }
            transfer = new Pending(parts, totalBytes, nowNanos);
            pending.put(transferId, transfer);
        } else if (transfer.parts != parts || transfer.totalBytes != totalBytes) {
            pending.remove(transferId);
            return Result.rejected("metadata changed");
        }

        if (transfer.chunks[part] != null) {
            pending.remove(transferId);
            return Result.rejected("duplicate part " + part);
        }
        if (transfer.bytes + data.length > transfer.totalBytes) {
            pending.remove(transferId);
            return Result.rejected("blob overflow");
        }

        transfer.chunks[part] = data;
        transfer.received++;
        transfer.bytes += data.length;

        if (transfer.received < transfer.parts) {
            return Result.incomplete();
        }
        pending.remove(transferId);
        if (transfer.bytes != transfer.totalBytes) {
            return Result.rejected("incomplete blob: " + transfer.bytes + "/" + transfer.totalBytes);
        }
        return Result.complete(join(transfer.chunks, transfer.totalBytes));
    }

    /** 回收超时传输，返回回收数量。 */
    public int expire(long nowNanos) {
        int dropped = 0;
        Iterator<Map.Entry<UUID, Pending>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Pending transfer = iterator.next().getValue();
            if (nowNanos - transfer.startedNanos >= timeoutNanos) {
                iterator.remove();
                dropped++;
            }
        }
        return dropped;
    }

    public int inFlight() {
        return pending.size();
    }

    public void clear() {
        pending.clear();
    }

    private static byte[] join(byte[][] chunks, int totalBytes) {
        byte[] blob = new byte[totalBytes];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, blob, offset, chunk.length);
            offset += chunk.length;
        }
        return blob;
    }

    /** 诊断用：当前在传的传输 id（测试/日志），不暴露内部数组。 */
    public List<UUID> transfers() {
        return new ArrayList<>(pending.keySet());
    }
}
