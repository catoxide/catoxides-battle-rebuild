package com.catoxide.catoxidesbattlerebuild.core.registry;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据驱动方块（批量注册的无 BE 方块载体）
 * <p>
 * 状态空间（完整 blockstate 定义，含自定义参数）由构造前的
 * {@code ThreadLocal} 声明注入 —— 原版 {@link Block} 构造器内部就会调用
 * {@link #createBlockStateDefinition(StateDefinition.Builder)} 并据此生成
 * {@link StateDefinition}，子类实例字段在 {@code super()} 时必然未初始化，
 * 所以走「工厂先挂 ThreadLocal → 构造 → 工厂摘除」的固定流程
 * （注册发生在 mod 总线的单线程 RegisterEvent 内，无并发问题）。
 *
 * <p>使用方式：一律经 {@link #of(BlockBehaviour.Properties, Collection)} 或
 * {@link #of(BlockBehaviour.Properties, Collection, Map)}，
 * 不要直接 {@code new}（直接 new 会得到无参数的单状态方块）。
 *
 * <h3>默认状态（1.21.1 陷阱）</h3>
 * 原版 {@code Block} 构造器注册 {@code stateDefinition.any()}（状态笛卡尔积<b>首项</b>）
 * 为默认状态。{@code BooleanProperty} 的取值迭代序是 {@code [true, false]}，
 * 所以不处理的话 boolean 参数默认是 <b>true</b>。原版惯例是显式注册 false
 * （如 BellBlock 构造器 {@code registerDefaultState(any().setValue(POWERED, false))}）。
 * 本类覆写 {@link #registerDefaultState(BlockState)}：优先用包声明的 {@code default}，
 * 未声明的 boolean 参数按惯例落 false（见 {@link #withDeclaredDefaults(BlockState)}）。
 */
public class DataDrivenBlock extends Block {

    private final List<Property<?>> properties;

    protected DataDrivenBlock(BlockBehaviour.Properties properties) {
        super(properties);
        DataDrivenBlockPending.Pending pending = DataDrivenBlockPending.get();
        this.properties = pending != null ? List.copyOf(pending.properties().values()) : List.of();
        if (pending != null) {
            // 1.21.1 陷阱：原版构造器把 any()（笛卡尔积首项，boolean 首值 true）注册为默认状态，
            // 而 registerDefaultState 是 final 无法覆写 —— 照原版惯例（BellBlock 构造器同款写法）
            // 在 super() 之后重新注册：声明默认值 + boolean 缺省 false。
            this.registerDefaultState(withDeclaredDefaults(this.stateDefinition.any()));
        }
    }

    /**
     * 构建一个数据驱动方块（完整状态空间 = 各参数取值的笛卡尔积）。
     *
     * @param properties      材质/物理参数
     * @param stateProperties 状态参数（可为空集合 = 单状态方块）
     */
    public static DataDrivenBlock of(BlockBehaviour.Properties properties, Collection<Property<?>> stateProperties) {
        return of(properties, stateProperties, null);
    }

    /**
     * 构建一个数据驱动方块（带声明默认值）。
     *
     * @param properties       材质/物理参数
     * @param stateProperties  状态参数（可为空集合 = 单状态方块）
     * @param declaredDefaults 参数名 → 声明默认值（包 JSON {@code default}；可空 = 无声明）
     */
    public static DataDrivenBlock of(BlockBehaviour.Properties properties, Collection<Property<?>> stateProperties,
                                     Map<String, String> declaredDefaults) {
        DataDrivenBlockPending.set(toPropertyMap(stateProperties), declaredDefaults);
        try {
            return new DataDrivenBlock(properties);
        } finally {
            DataDrivenBlockPending.remove();
        }
    }

    static Map<String, Property<?>> toPropertyMap(Collection<Property<?>> stateProperties) {
        Map<String, Property<?>> map = new HashMap<>();
        if (stateProperties != null) {
            for (Property<?> p : stateProperties) {
                map.put(p.getName(), p);
            }
        }
        return map;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        DataDrivenBlockPending.Pending pending = DataDrivenBlockPending.get();
        if (pending != null) {
            pending.properties().values().forEach(builder::add);
        }
    }

    /**
     * 把声明默认值应用到状态上（供 {@link DataDrivenEntityBlock} 复用）。
     * 规则：
     * <ul>
     *   <li>声明了 {@code default} 且取值合法 → 用声明值；</li>
     *   <li>未声明的 {@link BooleanProperty} → {@code false}（原版惯例）；</li>
     *   <li>其余保持 {@code any()} 首项值（int 从 min 起、enum 按声明序 —— 合理缺省）。</li>
     * </ul>
     */
    static BlockState withDeclaredDefaults(BlockState state) {
        DataDrivenBlockPending.Pending pending = DataDrivenBlockPending.get();
        if (pending == null) {
            return state;
        }
        Map<String, String> defaults = pending.defaults();
        for (Map.Entry<String, Property<?>> e : pending.properties().entrySet()) {
            String declared = defaults != null ? defaults.get(e.getKey()) : null;
            if (declared == null && e.getValue() instanceof BooleanProperty) {
                declared = "false";
            }
            if (declared != null) {
                state = applyDefault(state, e.getValue(), declared);
            }
        }
        return state;
    }

    /** 泛型方法统一通配符捕获：取值名解析与 setValue 在同一类型域内 */
    private static <T extends Comparable<T>> BlockState applyDefault(BlockState state, Property<T> p, String declared) {
        return p.getValue(declared).map(v -> state.setValue(p, v)).orElse(state);
    }

    /** 本方块声明的状态参数（按声明序） */
    public List<Property<?>> getStateProperties() {
        return properties;
    }
}
