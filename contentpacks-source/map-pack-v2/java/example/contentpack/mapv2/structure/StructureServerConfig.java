package example.contentpack.mapv2.structure;

import com.catoxide.catoxidesbattlerebuild.core.config.PackConfig;

/** 结构图层的服务端配置（map-pack 的 server 分区）。 */
public final class StructureServerConfig {

    private static volatile PackConfig config;

    private StructureServerConfig() {
    }

    public static void bind(PackConfig packConfig) {
        config = packConfig;
    }

    public static boolean enabled() {
        return getBool("enabled", true);
    }

    /**
     * 低于该 spacing 的结构集不再参与候选计算（单位：区块）。
     * 原版 spacing=1~2 的集合（埋藏的宝藏、废弃矿井、下界化石）密度高两个数量级。
     */
    public static int minSpacing() {
        return clamp(getInt("minSpacing", 8), 1, 128);
    }

    public static int maxResults() {
        return clamp(getInt("maxResults", 256), 16, 4096);
    }

    public static int budgetMillis() {
        return clamp(getInt("budgetMillis", 8), 1, 100);
    }

    public static int requestsPerSecond() {
        return clamp(getInt("requestsPerSecond", 8), 1, 64);
    }

    /** 推测级的群系门。关掉会回到「只看放置数学」，误报明显变多。 */
    public static boolean verifyBiomes() {
        return getBool("verifyBiomes", true);
    }

    /** mod 定义的结构集走同一套算法，但图标/名称不保证正确（D93 只验收原版）。 */
    public static boolean showModdedSets() {
        return getBool("showModdedSets", false);
    }

    private static boolean getBool(String key, boolean fallback) {
        PackConfig local = config;
        return local == null ? fallback : local.getBoolean(key);
    }

    private static int getInt(String key, int fallback) {
        PackConfig local = config;
        return local == null ? fallback : local.getInt(key);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
