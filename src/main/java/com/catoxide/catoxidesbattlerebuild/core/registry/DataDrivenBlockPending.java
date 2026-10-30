package com.catoxide.catoxidesbattlerebuild.core.registry;

import net.minecraft.world.level.block.state.properties.Property;

import java.util.Map;

/**
 * 数据驱动方块构造期间的「待注入状态参数」线程局部
 * <p>
 * 原版 {@code Block} 构造器内部就调用（虚方法）{@code createBlockStateDefinition}
 * 并生成 {@code StateDefinition}，而子类实例字段在 {@code super()} 返回前必然未初始化 ——
 * 因此数据驱动方块的参数表只能经线程局部传递：
 * <pre>
 * PENDING.set(map);
 * try {   return new DataDrivenBlock(props);   } // super() 内读到 map
 * finally { PENDING.remove(); }
 * </pre>
 * 注册全部发生在 mod 总线 RegisterEvent 的单线程内，无并发、无重入（属性构造不建块）。
 * 仅供 {@link DataDrivenBlock} / {@link DataDrivenEntityBlock} 使用。
 *
 * <p>{@link Pending#defaults()} 携带各参数的<b>声明默认值</b>（包 JSON {@code default} 字段），
 * 供 {@code registerDefaultState} 覆盖原版 {@code any()}（笛卡尔积首项，boolean 首值为 true）
 * —— 与原版惯例一致（BellBlock 等显式注册 POWERED=false）。
 */
final class DataDrivenBlockPending {

    /** 待注入数据：状态参数表（name → Property）+ 声明默认值（name → 取值字符串，可空） */
    record Pending(Map<String, Property<?>> properties, Map<String, String> defaults) {
        static Pending of(Map<String, Property<?>> properties) {
            return new Pending(properties, null);
        }
    }

    private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();

    private DataDrivenBlockPending() {
    }

    static Pending get() {
        return PENDING.get();
    }

    static void set(Map<String, Property<?>> properties) {
        PENDING.set(Pending.of(properties));
    }

    static void set(Map<String, Property<?>> properties, Map<String, String> defaults) {
        PENDING.set(new Pending(properties, defaults));
    }

    static void remove() {
        PENDING.remove();
    }
}
