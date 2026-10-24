package com.catoxide.catoxidesbattlerebuild.core.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Locale;

/**
 * 配置档位：决定「改完什么时候生效」。未声明时一律 {@link #COLD}。
 * <ul>
 *   <li>{@link #HOT} → {@code RestartType.NONE}：重载即生效（缓存立刻清空）</li>
 *   <li>{@link #WARM} → {@code RestartType.WORLD}：退出/重进世界后生效</li>
 *   <li>{@link #COLD} → {@code RestartType.GAME}：重启游戏后生效</li>
 * </ul>
 *
 * <p>NeoForge 禁止 SERVER 配置使用 GAME 重启（{@code validateSpec} 直接抛异常），
 * 所以 SERVER + COLD 在声明期就被拒绝，而不是静默降级。
 */
public enum ConfigTier {
    HOT,
    WARM,
    COLD;

    /** 把档位写进 Builder 的下一个值；必须在 define 之前调用。 */
    public void applyTo(ModConfigSpec.Builder builder) {
        switch (this) {
            case WARM -> builder.worldRestart();
            case COLD -> builder.gameRestart();
            case HOT -> {
                // NONE 是 NeoForge 默认值，不需要显式调用
            }
        }
    }

    public boolean isValidFor(ConfigScope scope) {
        return !(scope == ConfigScope.SERVER && this == COLD);
    }

    /** @return 匹配的档位，未知返回 null */
    public static ConfigTier parse(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "HOT" -> HOT;
            case "WARM" -> WARM;
            case "COLD" -> COLD;
            default -> null;
        };
    }
}
