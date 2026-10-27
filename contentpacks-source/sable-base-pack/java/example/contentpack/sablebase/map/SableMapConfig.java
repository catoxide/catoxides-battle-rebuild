package example.contentpack.sablebase.map;

import com.catoxide.catoxidesbattlerebuild.core.config.PackConfig;

/**
 * sable-map 配置读值入口。选项声明在 jar 根 {@code config-declarations.json}，
 * 全部 HOT，每次读取都走 {@link PackConfig}，改完立即生效。
 */
public final class SableMapConfig {

    private static volatile PackConfig config;

    private SableMapConfig() {
    }

    public static void bind(PackConfig packConfig) {
        config = packConfig;
    }

    public static boolean enabled() {
        return getBool("enabled", true);
    }

    public static boolean showShips() {
        return getBool("showShips", true);
    }

    public static boolean showEntities() {
        return getBool("showEntities", true);
    }

    public static boolean showOnMinimap() {
        return getBool("showOnMinimap", true);
    }

    public static boolean showOnFullmap() {
        return getBool("showOnFullmap", true);
    }

    public static double opacity() {
        return clamp(getDouble("opacity", 1.0), 0.2, 1.0);
    }

    public static int maxTexture() {
        return clamp(getInt("maxTexture", 384), 64, 1024);
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
