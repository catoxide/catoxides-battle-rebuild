package example.contentpack.mapv2.marker;

import com.catoxide.catoxidesbattlerebuild.core.config.PackConfig;

/** 标记的服务端配置（map-pack 的 server 分区）。 */
public final class MarkerServerConfig {

    private static volatile PackConfig config;

    private MarkerServerConfig() {
    }

    public static void bind(PackConfig packConfig) {
        config = packConfig;
    }

    public static boolean shareMarkers() {
        return getBool("shareMarkers", true);
    }

    public static boolean shareAllDimensions() {
        return getBool("shareAllDimensions", true);
    }

    /** 0 = 无限。 */
    public static int markerRange() {
        return getInt("markerRange", 0);
    }

    public static int pingSeconds() {
        return Math.max(1, getInt("pingSeconds", 10));
    }

    public static double deathMarkerRadius() {
        return Math.max(0.5, getDouble("deathMarkerRadius", 3.0));
    }

    private static boolean getBool(String key, boolean fallback) {
        PackConfig local = config;
        return local == null ? fallback : local.getBoolean(key);
    }

    private static int getInt(String key, int fallback) {
        PackConfig local = config;
        return local == null ? fallback : local.getInt(key);
    }

    private static double getDouble(String key, double fallback) {
        PackConfig local = config;
        return local == null ? fallback : local.getDouble(key);
    }
}
