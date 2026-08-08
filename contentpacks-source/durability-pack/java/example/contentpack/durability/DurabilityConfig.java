package example.contentpack.durability;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * 耐久包配置（ModConfigSpec → toml，游戏内可编辑 + reload 生效）
 * <p>经主 mod 的 ContentPackContext.registerConfig 注册（contentpack 不是 mod，
 * 配置通过主 mod 的 ModContainer 挂到 NeoForge 配置系统）。
 */
public class DurabilityConfig {

    public static final ModConfigSpec SPEC;

    // ===== 运行值（加载后生效） =====
    public static double mendingChance = 0.2;
    public static double anvilChance = 0.2;
    public static double craftingChance = 0.3;
    public static List<? extends String> exemptItems = List.of();

    // ===== 配置定义（toml） =====
    private static final ModConfigSpec.ConfigValue<Double> MENDING_CFG;
    private static final ModConfigSpec.ConfigValue<Double> ANVIL_CFG;
    private static final ModConfigSpec.ConfigValue<Double> CRAFTING_CFG;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> EXEMPT_CFG;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("Durability degradation chances (0.0 = never, 1.0 = always)")
                .push("degradation");
        MENDING_CFG = b.comment("Mending repair: chance to reduce max durability by 1 per repaired point")
                .defineInRange("mendingChance", 0.2, 0.0, 1.0);
        ANVIL_CFG = b.comment("Anvil repair: chance to reduce max durability per repaired point")
                .defineInRange("anvilChance", 0.2, 0.0, 1.0);
        CRAFTING_CFG = b.comment("Crafting repair: chance to reduce max durability per repaired point")
                .defineInRange("craftingChance", 0.3, 0.0, 1.0);
        b.pop();
        EXEMPT_CFG = b.comment("Items never degrade (format: modid:itemid)")
                .defineListAllowEmpty("exemptItems", List.of(), o -> o instanceof String);
        SPEC = b.build();
    }

    /** 配置加载/重载时刷新运行值（ModConfigEvent.Loading） */
    public static void load() {
        mendingChance = clamp(MENDING_CFG.get());
        anvilChance = clamp(ANVIL_CFG.get());
        craftingChance = clamp(CRAFTING_CFG.get());
        exemptItems = EXEMPT_CFG.get();
        LogManager.serverInfo("DurabilityConfig",
                "Loaded: Mending=%.0f%% Anvil=%.0f%% Crafting=%.0f%% exempt=%d",
                mendingChance * 100, anvilChance * 100, craftingChance * 100, exemptItems.size());
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
