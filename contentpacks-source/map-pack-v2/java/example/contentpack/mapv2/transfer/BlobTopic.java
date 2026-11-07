package example.contentpack.mapv2.transfer;

/**
 * 一个「按 key 寻址的字节块」主题。服务端注册实现，客户端注册接收器。
 *
 * <p>冲突策略固定为<b>最新写入胜</b>：{@link #store} 直接覆盖，返回新版本号；
 * 不做权限/所有者判断（客户端可信），但 key 与内容格式由实现校验。
 */
public interface BlobTopic {

    /** 主题名（小写标识符）；客户端请求里带的就是它。 */
    String id();

    /**
     * 查一块。
     *
     * @param knownRevision 调用方已有的版本；服务端用 mtime 毫秒值当版本
     * @return {@code null} = 不存在；{@link Blob#unchanged()} = 不比对方新，不用发
     */
    Blob fetch(String key, long knownRevision);

    /**
     * 覆盖写入，返回新版本号；{@code -1} 表示拒绝（key/内容格式不合法）。
     * 由单线程执行器串行调用，实现不必加锁。
     */
    long store(String key, byte[] data);

    /** 返回错误描述；{@code null} = key 合法。 */
    default String validateKey(String key) {
        return key == null || key.isEmpty() ? "empty key" : null;
    }

    record Blob(long revision, byte[] data) {

        /** 数据为 {@code null} 表示「版本不比对方新」，不是空块。 */
        public boolean unchanged() {
            return data == null;
        }
    }
}
