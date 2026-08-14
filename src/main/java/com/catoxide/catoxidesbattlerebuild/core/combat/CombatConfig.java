package com.catoxide.catoxidesbattlerebuild.core.combat;

import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 战斗（穿深）系统配置（toml，游戏内可改）。
 * <p>穿深体系核心底层玩法的调参入口：穿甲阈值、伤害因子区间。
 * 默认值与原有硬编码一致，改配置即可调整平衡（无需重编译）。
 */
public final class CombatConfig {

    public static final ModConfigSpec SPEC;
    private static final String TAG = "CombatConfig";

    /** 穿甲阈值：穿透值需超过 护甲+阈值 才能达到满伤害 */
    public static final ModConfigSpec.DoubleValue PENETRATION_THRESHOLD;
    /** 未穿甲（穿透 ≤ 护甲）时的最低伤害因子 */
    public static final ModConfigSpec.DoubleValue MIN_PENETRATION_FACTOR;
    /** 满穿甲（穿透 ≥ 护甲+阈值）时的伤害因子 */
    public static final ModConfigSpec.DoubleValue MAX_PENETRATION_FACTOR;
    /** 贯穿消耗系数（贯穿值对抗部位抗贯穿的消耗） */
    public static final ModConfigSpec.DoubleValue PIERCING_DEPLETION_RATE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        PENETRATION_THRESHOLD = builder
                .comment("穿甲阈值：穿透值需超过(护甲+此值)才能达到满伤害",
                        "Default: 10.0")
                .defineInRange("penetration_threshold", 10.0, 0.0, 100.0);

        MIN_PENETRATION_FACTOR = builder
                .comment("未穿甲(穿透<=护甲)时最低伤害因子",
                        "Default: 0.25")
                .defineInRange("min_penetration_factor", 0.25, 0.0, 1.0);

        MAX_PENETRATION_FACTOR = builder
                .comment("满穿甲(穿透>=护甲+阈值)时伤害因子",
                        "Default: 1.0")
                .defineInRange("max_penetration_factor", 1.0, 0.0, 1.0);

        PIERCING_DEPLETION_RATE = builder
                .comment("贯穿值消耗速率：剩余贯穿低于(此值×初始贯穿)时停止穿透",
                        "Default: 0.0(禁用)")
                .defineInRange("piercing_depletion_rate", 0.0, 0.0, 1.0);

        SPEC = builder.build();
    }

    private CombatConfig() {
    }

    /** 注册到 mod 容器（服务端配置，toml 落盘可改） */
    public static void register(net.neoforged.fml.ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, SPEC, "catoxides-combat.toml");
        // 注意：此处不能调用 *.get() —— config 尚未加载（加载在 FMLCommonSetup 之后），
        // get() 会抛 IllegalStateException 导致 mod 实例创建失败。值在战斗时惰性读取。
        com.catoxide.catoxidesbattlerebuild.util.LogManager.serverInfo(TAG,
                "Registered combat config (catoxides-combat.toml, values loaded lazily)");
    }
}
