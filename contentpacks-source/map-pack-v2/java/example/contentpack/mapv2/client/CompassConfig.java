package example.contentpack.mapv2.client;

import com.catoxide.catoxidesbattlerebuild.core.config.PackConfig;

/**
 * 罗盘配置读值入口（原 compass-pack，配置并入 map-pack 的 compass 分区）。
 */
public final class CompassConfig {

    private static volatile PackConfig config;

    private CompassConfig() {
    }

    public static void bind(PackConfig packConfig) {
        config = packConfig;
    }

    /** 罗盘条宽度（GUI 像素），再受屏幕宽度限制，避免窄屏时溢出。 */
    public static int barWidth(int screenWidth) {
        int width = Math.max(120, Math.min(600, getInt("width", 300)));
        return Math.min(width, screenWidth - 24);
    }

    public static boolean fovTicks() {
        return get("fovTicks", false);
    }

    public static boolean numberOnly() {
        return get("numberOnly", false);
    }

    public static boolean thickBar() {
        return get("thickBar", false);
    }

    public static boolean frostedBackground() {
        return get("frostedBackground", true);
    }

    /** 0 = 透视跟随 FOV，1 = 中心对齐 + 自由缩放，2 = 线性（原版）。 */
    public static int alignMode() {
        return Math.max(0, Math.min(2, getInt("alignMode", 0)));
    }

    /** centerOnly 模式下使用的参考 FOV。 */
    public static int referenceFov() {
        return Math.max(30, Math.min(120, getInt("referenceFov", 70)));
    }

    /** centerOnly 模式下的横向缩放（1.0 = 按参考 FOV 铺满）。 */
    public static double compassScale() {
        double value = getDouble("compassScale", 1.0);
        return Math.max(0.4, Math.min(2.5, value));
    }

    /** 罗盘上标记符号的放大倍数（1.0 = 原始大小）。 */
    public static double markerScale() {
        double value = getDouble("markerScale", 1.5);
        return Math.max(0.5, Math.min(3.0, value));
    }

    /** 0 = 无，1 = 阴影，2 = 四向描边。 */
    public static int textStyle() {
        return Math.max(0, Math.min(2, getInt("textStyle", 1)));
    }

    private static boolean get(String key, boolean fallback) {
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
