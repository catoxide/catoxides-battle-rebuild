package example.contentpack.mapv2.network;

import com.catoxide.catoxidesbattlerebuild.core.config.PackConfig;

/** 地图瓦片共享的服务端配置（map-pack 的 server 分区）。 */
public final class TileSyncConfig {

    private static volatile PackConfig config;

    private TileSyncConfig() {
    }

    public static void bind(PackConfig packConfig) {
        config = packConfig;
    }

    public static boolean shareTiles() {
        PackConfig local = config;
        return local == null || local.getBoolean("shareTiles");
    }

    /** 每玩家下行上限；上行在客户端固定节流，服务端只限制重组大小。 */
    public static long bytesPerSecond() {
        PackConfig local = config;
        int kbps = local == null ? 256 : local.getInt("tileKbpsPerPlayer");
        return Math.max(16, Math.min(4096, kbps)) * 1024L;
    }
}
