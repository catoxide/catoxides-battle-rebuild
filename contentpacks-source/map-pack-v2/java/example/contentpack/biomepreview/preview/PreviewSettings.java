package example.contentpack.biomepreview.preview;

import com.catoxide.catoxidesbattlerebuild.core.config.PackConfig;

/**
 * 预览档位：0=关，1=4 格，2=8 格，3=16 格。
 *
 * <p>地图层的实际步长由服务端决定：客户端只请求 {@link BiomeRegionGrid#MIN_STEP}，
 * 服务端取 {@link #stepBlocks()} 与 {@code maxCellsPerAxis} 预算的较大值下发给客户端；
 * 客户端自己的档位只用于开关（0 与非 0）。
 *
 * <p>选项声明在包 jar 根的 {@code config-declarations.json}（COMMON / HOT，
 * {@code biome-preview-pack.toml}），每次读取都走 {@link PackConfig}，改完立刻生效。
 */
public final class PreviewSettings {

    public static final int TIER_OFF = 0;

    private static volatile PackConfig config;

    private PreviewSettings() {
    }

    /** 由 {@code MapV2Pack#register} 在 register 阶段绑定。 */
    public static void bind(PackConfig packConfig) {
        config = packConfig;
    }

    public static int tier() {
        PackConfig local = config;
        return local == null ? TIER_OFF : local.getInt("resolutionTier");
    }

    /** 预测功能总开关（{@code resolutionTier = 0} 时整层关闭）。 */
    public static boolean enabled() {
        return tier() != TIER_OFF;
    }

    /** @return 服务端最小采样步长（方块）；0 = 关闭 */
    public static int stepBlocks() {
        return switch (tier()) {
            case 1 -> 4;
            case 2 -> 8;
            case 3 -> 16;
            default -> 0;
        };
    }

    /** 圆形预测半径（方块）。 */
    public static int radiusBlocks() {
        PackConfig local = config;
        int configured = local == null ? 1024 : local.getInt("radiusBlocks");
        return Math.max(256, Math.min(8192, configured));
    }

    /** 单块区域每轴最多采样点数；步长会按它自动放大。 */
    public static int maxCellsPerAxis() {
        PackConfig local = config;
        int configured = local == null ? 513 : local.getInt("maxCellsPerAxis");
        return Math.max(33, Math.min(513, configured));
    }

    /** 是否把收到的预测区域按世界固化到磁盘。 */
    public static boolean persist() {
        PackConfig local = config;
        return local == null || local.getBoolean("persist");
    }
}
