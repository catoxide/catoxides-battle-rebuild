package example.contentpack.compass.client;

import com.catoxide.catoxidesbattlerebuild.core.config.PackConfig;

/**
 * 顶部罗盘配置的运行入口。
 *
 * <p>选项声明与默认值在包 jar 的 {@code config-declarations.json}
 * （CLIENT / {@code compass-pack-client.toml} / 全部 HOT），本类不再写 spec。
 * 每次读取都走 {@link PackConfig}，因此样式改动重载后立刻生效，不需要在这里缓存数值。
 */
public final class CompassConfig {

    private static volatile PackConfig config;

    private CompassConfig() {
    }

    /** 由 {@code CompassPack#register} 在 register 阶段绑定。 */
    public static void bind(PackConfig packConfig) {
        config = packConfig;
    }

    public static boolean fovTicks() {
        return config.getBoolean("fovTicks");
    }

    public static boolean numberOnly() {
        return config.getBoolean("numberOnly");
    }

    public static boolean thickBar() {
        return config.getBoolean("thickBar");
    }

    public static boolean frostedBackground() {
        return config.getBoolean("frostedBackground");
    }
}
