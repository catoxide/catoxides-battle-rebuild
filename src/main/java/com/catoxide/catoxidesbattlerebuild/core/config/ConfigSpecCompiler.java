package com.catoxide.catoxidesbattlerebuild.core.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把声明编译成 NeoForge 的 {@link ModConfigSpec}。
 * <p>这是声明层与 FML 之间唯一的耦合点：包代码永远看不到 {@code ModConfigSpec}。
 */
public final class ConfigSpecCompiler {

    private ConfigSpecCompiler() {
    }

    public static Compiled compile(ConfigDecl decl) {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        Map<String, ModConfigSpec.ConfigValue<?>> values = new LinkedHashMap<>();

        for (SectionDecl section : decl.sections()) {
            // 必须在 push 之前设置：NeoForge 在 push 时把当前翻译键记成该层级的名字。
            // 否则分区标题退化成 "<modid>.configuration.<sectionKey>"，多个包的同名分区还会互相覆盖。
            builder.translation(decl.sectionTranslationKey(section.key()));
            builder.push(section.key());
            for (ValueDecl value : section.values()) {
                if (!value.comment().isEmpty()) {
                    builder.comment(value.comment().toArray(String[]::new));
                }
                // 顺序固定：comment → 档位 → 翻译键 → define（每次 define 后 Builder 会重置上下文）
                value.tier().applyTo(builder);
                builder.translation(decl.translationKey(section.key(), value.key()));
                values.put(value.key(), define(builder, value));
            }
            builder.pop();
        }

        return new Compiled(builder.build(), Map.copyOf(values));
    }

    @SuppressWarnings("unchecked")
    private static ModConfigSpec.ConfigValue<?> define(ModConfigSpec.Builder builder, ValueDecl value) {
        return switch (value.type()) {
            // 必须是 primitive boolean：选到泛型 define(String, T) 会返回裸 ConfigValue<Boolean>，
            // 内置配置屏只认 BooleanValue，否则显示「此值不能在 UI 中编辑」。
            case BOOL -> builder.define(value.key(), (boolean) value.defaultValue());
            case INT -> builder.defineInRange(value.key(), (Integer) value.defaultValue(),
                    intMin(value.min()), intMax(value.max()));
            case DOUBLE -> builder.defineInRange(value.key(), (Double) value.defaultValue(),
                    value.min() == null ? -Double.MAX_VALUE : value.min(),
                    value.max() == null ? Double.MAX_VALUE : value.max());
            case STRING -> builder.define(value.key(), (String) value.defaultValue());
            case STRING_LIST -> builder.defineListAllowEmpty(value.key(), (List<String>) value.defaultValue(),
                    () -> "", o -> o instanceof String);
        };
    }

    private static int intMin(Double bound) {
        if (bound == null) {
            return Integer.MIN_VALUE;
        }
        // 声明里的 min/max 是 double；向区间内取整，避免 min=0.5 时把 0 放进来
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.ceil(bound)));
    }

    private static int intMax(Double bound) {
        if (bound == null) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.floor(bound)));
    }

    /**
     * @param spec   编译好的 spec
     * @param values 选项键 → ConfigValue（键不含分区，声明期已保证同配置内唯一）
     */
    public record Compiled(ModConfigSpec spec, Map<String, ModConfigSpec.ConfigValue<?>> values) {
    }
}
