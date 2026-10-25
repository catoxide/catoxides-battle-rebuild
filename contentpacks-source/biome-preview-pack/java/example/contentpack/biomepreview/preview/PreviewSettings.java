package example.contentpack.biomepreview.preview;

import com.catoxide.catoxidesbattlerebuild.core.config.PackConfig;

/**
 * 预览档位：0=关，1=4 格，2=8 格，3=16 格。
 * <p>选项声明在包 jar 根的 {@code config-declarations.json}（COMMON / HOT，
 * {@code biome-preview-pack.toml}），每次读取都走 {@link PackConfig}，改完立刻生效。
 */
public final class PreviewSettings {

    public static final int TIER_OFF = 0;

    private static volatile PackConfig config;

    private PreviewSettings() {
    }

    /** 由 {@code BiomePreviewPack#register} 在 register 阶段绑定。 */
    public static void bind(PackConfig packConfig) {
        config = packConfig;
    }

    public static int tier() {
        PackConfig local = config;
        return local == null ? TIER_OFF : local.getInt("resolutionTier");
    }

    /** @return 采样步长（方块）；0 = 关闭 */
    public static int stepBlocks() {
        return switch (tier()) {
            case 1 -> 4;
            case 2 -> 8;
            case 3 -> 16;
            default -> 0;
        };
    }
}
