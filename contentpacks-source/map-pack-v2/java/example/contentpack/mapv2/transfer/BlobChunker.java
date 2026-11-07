package example.contentpack.mapv2.transfer;

/**
 * 把一段字节切成网络分片。纯逻辑，不碰世界/渲染/网络类。
 *
 * <p>分片上限固定，接收侧才能用一个常量拒绝异常包；单个瓦片压缩后
 * 一般远小于 {@link #MAX_BLOB_BYTES}，这里是防滥用而不是性能参数。
 */
public final class BlobChunker {

    /** 每片字节数：远低于 NeoForge 单包 1 MiB 上限，也控制单 tick 的发送量。 */
    public static final int CHUNK_BYTES = 24 * 1024;
    /** 单个 blob 上限 8 MiB：当前瓦片格式解压后约 2.3 MB，压缩后远低于此。 */
    public static final int MAX_BLOB_BYTES = 8 * 1024 * 1024;
    public static final int MAX_PARTS = (MAX_BLOB_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES;

    private BlobChunker() {
    }

    /** 空 blob 返回 0；调用方把 0 当作「没有内容」。 */
    public static int parts(int totalBytes) {
        if (totalBytes <= 0) {
            return 0;
        }
        return (totalBytes + CHUNK_BYTES - 1) / CHUNK_BYTES;
    }

    public static int partLength(int totalBytes, int part) {
        int start = part * CHUNK_BYTES;
        return Math.max(0, Math.min(CHUNK_BYTES, totalBytes - start));
    }

    /** 复制第 {@code part} 片；越界直接返回空数组（接收侧会按总长度校验）。 */
    public static byte[] part(byte[] blob, int part) {
        int start = part * CHUNK_BYTES;
        if (blob == null || part < 0 || start >= blob.length) {
            return new byte[0];
        }
        int length = partLength(blob.length, part);
        byte[] chunk = new byte[length];
        System.arraycopy(blob, start, chunk, 0, length);
        return chunk;
    }
}
