package com.catoxide.catoxidesbattlerebuild.core.registry;

import net.minecraft.world.level.block.state.properties.Property;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 字符串枚举方块状态参数（自定义参数的通用载体）
 * <p>
 * 原版 1.21.1 的 {@code EnumProperty} 要求值类型必须是
 * {@code Enum<T> & StringRepresentable} —— 包作者无法在不写 Java 的情况下自定义取值集合。
 * 而 {@code Property<T extends Comparable<T>>} 是公开抽象类（protected 构造器），
 * 因此基座在这里补一个 String 值版本：
 *
 * <ul>
 *   <li>取值就是 JSON 里声明的字符串（如 {@code "idle"/"charging"/"overcharged"}）；</li>
 *   <li>blockstate 文件（{@code assets/<ns>/blockstate/<block>.json}）里的
 *       {@code prop=取值} 与原版块完全一致 —— 编辑器导出的原版文件零转换；</li>
 *   <li>游戏侧只按字符串名解析，不关心背后的 Java 类型。</li>
 * </ul>
 *
 * <p>取值名必须满足原版 {@code StateDefinition.NAME_PATTERN}（{@code ^[a-z0-9_]+$}），
 * 由 {@link BlockPropertyCatalog#buildProperty(BlockPropertySpec)} 校验。
 */
public final class StringEnumProperty extends Property<String> {

    private final List<String> values;

    public StringEnumProperty(String name, List<String> values) {
        super(name, String.class);
        this.values = List.copyOf(values);
    }

    @Override
    public Collection<String> getPossibleValues() {
        return values;
    }

    @Override
    public String getName(String value) {
        return value;
    }

    @Override
    public Optional<String> getValue(String name) {
        return values.contains(name) ? Optional.of(name) : Optional.empty();
    }
}
