package com.catoxide.catoxidesbattlerebuild.core.registry;

import java.util.List;

/**
 * 方块状态参数声明（blockstate 自定义参数的规范形式）
 * <p>
 * 同时是三种场景的统一描述：
 * <ol>
 *   <li><b>参数目录</b>（{@code assets/catoxidesbattlerebuild/editor/block_properties.json}）
 *       里的标准参数条目 —— Java 与编辑器共读同一份文件；</li>
 *   <li>包内 {@code blocks/*.json} 里 {@code properties[]} 的内联自定义参数
 *       （批量注册时按此现场构建 {@link net.minecraft.world.level.block.state.properties.Property}，
 *       不需要包写任何 Java）；</li>
 *   <li>编辑器「方块形态」页签的可选选项来源（目录文件 + 包内已用过的自定义参数）。</li>
 * </ol>
 *
 * @param name          参数名。必须满足原版 {@code StateDefinition.NAME_PATTERN}：{@code ^[a-z0-9_]+$}
 * @param type          {@code direction} | {@code axis} | {@code boolean} | {@code int} | {@code enum}
 * @param values        取值集合（direction/axis/enum 必填且 ≥2 个；boolean 忽略；int 忽略）
 * @param min           int 类型最小值（含），缺省 0
 * @param max           int 类型最大值（含），缺省 15。必须 &gt; min（原版 IntegerProperty 硬校验）
 * @param defaultValue  默认值（字符串形式），必须属于 values 区间
 * @param labelZh       中文显示名（编辑器用，可空）
 * @param description   说明（编辑器用，可空）
 */
public record BlockPropertySpec(
        String name,
        String type,
        List<String> values,
        int min,
        int max,
        String defaultValue,
        String labelZh,
        String description
) {
    public static final String TYPE_DIRECTION = "direction";
    public static final String TYPE_AXIS = "axis";
    public static final String TYPE_BOOLEAN = "boolean";
    public static final String TYPE_INT = "int";
    public static final String TYPE_ENUM = "enum";

    /** 方向类取值（DirectionProperty 支持 6 向） */
    public static final List<String> DIRECTION_VALUES = List.of("north", "south", "west", "east", "up", "down");
    /** 水平方向子集（barrel/ladder/door 一类） */
    public static final List<String> HORIZONTAL_DIRECTION_VALUES = List.of("north", "south", "west", "east");
    /** 轴向取值（Direction.Axis） */
    public static final List<String> AXIS_VALUES = List.of("x", "y", "z");

    public boolean hasValues() {
        return values != null && !values.isEmpty();
    }
}
