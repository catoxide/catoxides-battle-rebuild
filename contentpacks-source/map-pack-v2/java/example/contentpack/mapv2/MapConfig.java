package example.contentpack.mapv2;

import com.catoxide.catoxidesbattlerebuild.core.config.PackConfig;

/**
 * 地图配置读值入口。
 *
 * <p>选项声明在包 jar 的 {@code config-declarations.json}（CLIENT / {@code map-pack-client.toml}，
 * 全部 HOT），每次读取都走 {@link PackConfig}，改完立即生效，不在这里缓存数值。
 */
public final class MapConfig {

    private static volatile PackConfig config;

    private MapConfig() {
    }

    /** 由 {@code MapPack#register} 在 register 阶段绑定。 */
    public static void bind(PackConfig packConfig) {
        config = packConfig;
    }

    public static boolean enabled() {
        PackConfig local = config;
        return local == null || local.getBoolean("enabled");
    }

    /** 小地图边长（像素），配置范围内钳制。 */
    public static int minimapSize() {
        return clamp(getInt("size", 128), 64, 256);
    }

    /** 小地图每像素覆盖的方块数。 */
    public static int blocksPerPixel() {
        return clamp(getInt("blocksPerPixel", 2), 1, 8);
    }

    /** 世界地图在 1x 缩放下的视野宽度（方块）。 */
    public static int fullscreenBlocks() {
        return clamp(getInt("fullscreenBlocks", 1024), 256, 8192);
    }

    /** 缩小时瓦片锐化强度（0 = 关，100 = 最强）。画布档位下纹理恒为整数倍最近邻，默认关。 */
    public static int zoomSharpen() {
        return clamp(getInt("zoomSharpen", 0), 0, 100);
    }

    public static boolean showBiome() {
        return getBool("showBiome", true);
    }

    public static boolean showTime() {
        return getBool("showTime", true);
    }

    public static boolean showBlockInfo() {
        return getBool("showBlockInfo", true);
    }

    public static boolean showBiomePrediction() {
        return getBool("showBiomePrediction", true);
    }

    public static boolean predictionHover() {
        return getBool("predictionHover", true);
    }

    public static boolean predictionOutline() {
        return getBool("predictionOutline", true);
    }

    public static boolean markerSound() {
        return getBool("markerSound", true);
    }

    /** 新世界发现同 seed 的已有地图时，是否弹窗询问继承。 */
    public static boolean inheritPrompt() {
        return getBool("inheritPrompt", true);
    }

    private static int getInt(String key, int fallback) {
        PackConfig local = config;
        return local == null ? fallback : local.getInt(key);
    }

    private static boolean getBool(String key, boolean fallback) {
        PackConfig local = config;
        return local == null ? fallback : local.getBoolean(key);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
