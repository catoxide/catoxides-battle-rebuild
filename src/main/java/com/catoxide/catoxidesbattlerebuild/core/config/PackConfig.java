package com.catoxide.catoxidesbattlerebuild.core.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <p>内容包侧的配置句柄：按选项键读值（以及为「游戏内可调」的宿主写值），不暴露任何 FML 类型。
 *
 * <p>每次读取都走 {@code ConfigValue#get()}（内部有缓存），因此：
 * <ul>
 *   <li>HOT 选项：重载后立刻读到新值</li>
 *   <li>WARM 选项：退出/重进世界后才读到新值</li>
 *   <li>COLD 选项：重启游戏后才读到新值</li>
 * </ul>
 * 不要在包里再缓存一份——那会把 HOT 降级成 COLD。
 *
 * <p>写入见 {@link #setInt} 等：FML 的 {@code set} 对 HOT 选项会当场更新缓存值，
 * 所以同一次拖动里下一帧就能读到新值（不需要重载）；WARM/COLD 只改文件，仍按上表的档位生效。
 */
public final class PackConfig {

    private final String packId;
    private final String id;
    private final String fileName;
    private final Map<String, ModConfigSpec.ConfigValue<?>> values;

    PackConfig(String packId, String id, String fileName, Map<String, ModConfigSpec.ConfigValue<?>> values) {
        this.packId = packId;
        this.id = id;
        this.fileName = fileName;
        this.values = values;
    }

    public String id() {
        return id;
    }

    public String fileName() {
        return fileName;
    }

    public boolean getBoolean(String key) {
        return require(key, Boolean.class);
    }

    public int getInt(String key) {
        return require(key, Integer.class);
    }

    public double getDouble(String key) {
        return require(key, Double.class);
    }

    public String getString(String key) {
        return require(key, String.class);
    }

    public List<String> getStringList(String key) {
        Object raw = raw(key);
        if (!(raw instanceof List<?> list)) {
            throw typeError(key, "a list of strings", raw);
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof String text)) {
                throw typeError(key, "a list of strings", raw);
            }
            out.add(text);
        }
        return List.copyOf(out);
    }

    // ============================================================
    // 写入通道：给「游戏内可调」的宿主（HUD 布局面板等）用
    // ============================================================

    /**
     * 选项的运行时元数据。全部从已编译的配置里读出来，<b>不是第二份声明</b>——
     * 所以 {@code config-declarations.json} 改了区间或档位，面板不用改任何 Java 代码。
     *
     * @param kind           声明出来的类型
     * @param min            区间下界；无区间时是 {@code Double.NEGATIVE_INFINITY}
     * @param max            区间上界；无区间时是 {@code Double.POSITIVE_INFINITY}
     * @param defaultValue   声明里的默认值（「恢复默认」用）
     * @param needsRestart   档位不是 HOT：写完内存值不会立刻生效，界面要说明
     * @param translationKey 内置配置屏用的显示名键，面板直接复用
     */
    public record OptionInfo(Kind kind, double min, double max, double defaultValue,
                             boolean needsRestart, @Nullable String translationKey) {

        /** 区间两侧都真实声明过 —— 能画成拖拽条的前提。 */
        public boolean isBounded() {
            return Double.isFinite(min) && Double.isFinite(max) && min < max;
        }

        public double clamp(double value) {
            return isBounded() ? Math.max(min, Math.min(max, value)) : value;
        }

        public enum Kind {
            BOOL,
            INT,
            DOUBLE,
            STRING,
            STRING_LIST
        }
    }

    /**
     * 读选项元数据。未知键抛 {@link IllegalStateException}，与读值路径一致——
     * 调用方（面板）应在解析阶段捕获并跳过，不是每个键都保证存在。
     */
    public OptionInfo info(String key) {
        ModConfigSpec.ConfigValue<?> value = held(key);
        ModConfigSpec.ValueSpec spec = value.getSpec();
        OptionInfo.Kind kind = kindOf(value, spec);
        double[] bounds = boundsOf(spec);
        double fallback = toDouble(value.getDefault(), 0.0);
        boolean restarts = spec.restartType() != ModConfigSpec.RestartType.NONE;
        return new OptionInfo(kind, bounds[0], bounds[1], fallback, restarts, spec.getTranslationKey());
    }

    public void setBoolean(String key, boolean value) {
        ((ModConfigSpec.BooleanValue) held(key, OptionInfo.Kind.BOOL)).set(value);
    }

    /** 写入整数；超出声明区间的输入先钳到区间内（FML 的 {@code set} 自己不钳）。 */
    public void setInt(String key, int value) {
        ModConfigSpec.IntValue held = (ModConfigSpec.IntValue) held(key, OptionInfo.Kind.INT);
        double clamped = info(key).clamp(value);
        held.set((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, clamped)));
    }

    /** 写入浮点；超出声明区间的输入先钳到区间内。 */
    public void setDouble(String key, double value) {
        ModConfigSpec.DoubleValue held = (ModConfigSpec.DoubleValue) held(key, OptionInfo.Kind.DOUBLE);
        held.set(info(key).clamp(value));
    }

    /**
     * 把当前内存值写回 TOML（整份文件，任一选项都指向同一个 spec）。
     *
     * <p>{@link #setInt} 等只改内存，<b>不写盘也不触发事件</b>：HOT 选项的同帧生效靠的就是
     * 内存值，所以拖拽过程中可以每帧写，落盘留到拖动结束再调一次。
     *
     * @throws IllegalStateException 配置尚未被 FML 装载（此时 FML 自己的前置检查会失败）
     */
    public void save() {
        for (ModConfigSpec.ConfigValue<?> value : values.values()) {
            try {
                value.save();
            } catch (RuntimeException e) {
                throw new IllegalStateException("Cannot save config '" + packId + "/" + id
                        + "' (" + e.getMessage() + "); is it loaded yet?", e);
            }
            return;
        }
    }

    private ModConfigSpec.ConfigValue<?> held(String key, OptionInfo.Kind expected) {
        ModConfigSpec.ConfigValue<?> value = held(key);
        OptionInfo.Kind actual = kindOf(value, value.getSpec());
        if (actual != expected) {
            throw new IllegalStateException("Config key '" + key + "' in '" + packId + "/" + id
                    + "' is declared as " + actual + ", not " + expected);
        }
        return value;
    }

    private ModConfigSpec.ConfigValue<?> held(String key) {
        ModConfigSpec.ConfigValue<?> value = values.get(key);
        if (value == null) {
            throw new IllegalStateException("Unknown config key '" + key + "' in '" + packId + "/" + id
                    + "'; declared keys: " + values.keySet());
        }
        return value;
    }

    private static OptionInfo.Kind kindOf(ModConfigSpec.ConfigValue<?> value, ModConfigSpec.ValueSpec spec) {
        if (value instanceof ModConfigSpec.BooleanValue) {
            return OptionInfo.Kind.BOOL;
        }
        if (value instanceof ModConfigSpec.IntValue) {
            return OptionInfo.Kind.INT;
        }
        if (value instanceof ModConfigSpec.DoubleValue) {
            return OptionInfo.Kind.DOUBLE;
        }
        return spec instanceof ModConfigSpec.ListValueSpec ? OptionInfo.Kind.STRING_LIST : OptionInfo.Kind.STRING;
    }

    /**
     * 声明区间。没写区间的选项由 {@code ConfigSpecCompiler} 填成 {@code Integer.MIN_VALUE/MAX_VALUE}
     * 或 {@code ±Double.MAX_VALUE} 这类哨兵值，这里一律折成无穷，让调用方一眼看出「不能拖」。
     *
     * <p>{@code getRange()} 的类型参数受 {@code V extends Comparable<? super V>} 约束：写成
     * {@code Range<Object>} 编译不过，写成 {@code Range<Integer>} 又会在读出 Double 边界时抛 CCE，
     * 所以取通配符，边界只当 {@code Object} 交给 {@link #toBound} 换算。
     */
    private static double[] boundsOf(ModConfigSpec.ValueSpec spec) {
        ModConfigSpec.Range<?> range = spec.getRange();
        if (range == null) {
            return new double[]{Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};
        }
        return new double[]{toBound(range.getMin(), true), toBound(range.getMax(), false)};
    }

    private static double toBound(@Nullable Object value, boolean isMin) {
        if (value == null) {
            return isMin ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        double numeric = toDouble(value, 0.0);
        boolean sentinel = isMin
                ? numeric <= Integer.MIN_VALUE || numeric <= -Double.MAX_VALUE
                : numeric >= Integer.MAX_VALUE || numeric >= Double.MAX_VALUE;
        return sentinel ? (isMin ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY) : numeric;
    }

    private static double toDouble(@Nullable Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private <T> T require(String key, Class<T> type) {
        Object raw = raw(key);
        if (!type.isInstance(raw)) {
            throw typeError(key, type.getSimpleName(), raw);
        }
        return type.cast(raw);
    }

    private Object raw(String key) {
        ModConfigSpec.ConfigValue<?> value = values.get(key);
        if (value == null) {
            throw new IllegalStateException("Unknown config key '" + key + "' in '" + packId + "/" + id
                    + "'; declared keys: " + values.keySet());
        }
        // 配置尚未加载时 get() 自身会抛出带说明的 IllegalStateException
        return value.get();
    }

    private IllegalStateException typeError(String key, String expected, Object actual) {
        return new IllegalStateException("Config key '" + key + "' in '" + packId + "/" + id
                + "' is not " + expected + " (got " + (actual == null ? "null" : actual.getClass().getSimpleName()) + ")");
    }
}
