package example.contentpack.compass.client;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 顶部罗盘配置（ModConfigSpec → toml，游戏内可编辑 + /reload 生效）。
 * <p>纯客户端 overlay：配置类型 {@code CLIENT}，只于 {@link CompassOverlay#render} 读取。
 * 默认值 = 当前（无配置）时的样子：均匀 15° 刻度 / 全罗盘 / 正常厚度 / 黑色磨砂背景。
 *
 * 配置落在 {@code run\client\config\compass-pack-client.toml}。
 */
public class CompassConfig {

    public static final ModConfigSpec SPEC;

    // ===== 运行值（load 后刷新；初始值 = 当前默认，保证未加载时行为与现状一致） =====
    public static volatile boolean fovTicks = false;          // 均匀15°刻度（当前） vs 按FOV模拟实际刻度
    public static volatile boolean numberOnly = false;        // 简化版：只显示当前方位数字（隐藏罗盘条）
    public static volatile boolean thickBar = false;          // 正常/薄条（当前） vs 厚条
    public static volatile boolean frostedBackground = true;  // 黑色磨砂背景（当前） vs 无背景

    // ===== 配置定义（toml） =====
    private static final ModConfigSpec.ConfigValue<Boolean> FOV_TICKS_CFG;
    private static final ModConfigSpec.ConfigValue<Boolean> NUMBER_ONLY_CFG;
    private static final ModConfigSpec.ConfigValue<Boolean> THICK_BAR_CFG;
    private static final ModConfigSpec.ConfigValue<Boolean> FROSTED_BG_CFG;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("Skyrim Compass HUD - top bar compass overlay (client-only)");
        b.comment("Defaults match the current (no-config) appearance.");
        b.comment("");
        b.comment("fovTicks=false: fixed 110-degree window, 15-degree ticks (current).");
        b.comment("fovTicks=true:  the bar spans your camera field of view, so tick");
        b.comment("               spacing simulates real angular spacing.");
        FOV_TICKS_CFG = b.define("fovTicks", false);

        b.comment("numberOnly=true:  show only the current bearing number (hide bar/ticks).");
        NUMBER_ONLY_CFG = b.define("numberOnly", false);

        b.comment("thickBar=true:  taller bar; false: normal/thin bar (current).");
        THICK_BAR_CFG = b.define("thickBar", false);

        b.comment("frostedBackground=true:  frosted black bg (current); false: no background.");
        FROSTED_BG_CFG = b.define("frostedBackground", true);

        SPEC = b.build();
    }

    /** 配置加载/重载时刷新运行值（ModConfigEvent.Loading，客户端）。 */
    public static void load() {
        fovTicks = FOV_TICKS_CFG.get();
        numberOnly = NUMBER_ONLY_CFG.get();
        thickBar = THICK_BAR_CFG.get();
        frostedBackground = FROSTED_BG_CFG.get();
        // clientStartup 只接受 (tag, message)；带 format 的日志走 clientInfo
        LogManager.clientInfo("CompassConfig",
                "Loaded: fovTicks=%b numberOnly=%b thickBar=%b frostedBackground=%b",
                fovTicks, numberOnly, thickBar, frostedBackground);
    }
}
