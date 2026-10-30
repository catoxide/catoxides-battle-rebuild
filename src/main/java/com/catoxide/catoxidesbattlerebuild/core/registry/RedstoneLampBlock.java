package com.catoxide.catoxidesbattlerebuild.core.registry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Collection;
import java.util.Map;

/**
 * 红石灯行为方块（数据驱动版，1.21.1）
 * <p>
 * 行为与原版 {@code RedstoneLampBlock} 完全一致（字节码逐条对齐）：
 * <ul>
 *   <li>携带一个可配置的 {@link BooleanProperty}（声明缺省值，注册期默认 false）；</li>
 *   <li>{@code neighborChanged}：任一方块邻居的红石信号变化时重算——
 *       <b>点亮立即生效</b>（{@code setBlock flag 2}），<b>熄灭延迟 4 tick</b>
 *       （{@code scheduleTick} + {@code tick} 里复查信号，复刻原版红石灯的「余辉」闪烁）；</li>
 *   <li>放置时初值 = 放置处当前有无红石信号（{@code hasNeighborSignal}）；</li>
 *   <li>亮度由注册方通过 {@link BlockBehaviour.Properties#lightLevel} 按状态动态给出
 *       （点亮/未点亮两个等级），本类不持有亮度数值。</li>
 * </ul>
 *
 * <p>状态空间注入与 {@link DataDrivenBlock} 相同（ThreadLocal 声明 + 工厂方法），
 * 一律经 {@link #of(BlockBehaviour.Properties, BooleanProperty, Collection)} 创建。
 */
public class RedstoneLampBlock extends DataDrivenBlock {

    private final BooleanProperty litProperty;

    protected RedstoneLampBlock(BlockBehaviour.Properties properties, BooleanProperty litProperty) {
        super(properties);
        this.litProperty = litProperty;
    }

    /**
     * @param properties      材质/物理（含 lightLevel 动态亮度函数）
     * @param litProperty     要切换的布尔参数（必须包含在 stateProperties 中）
     * @param stateProperties 完整状态参数（含 litProperty；笛卡尔积 = blockstate 状态空间）
     */
    public static RedstoneLampBlock of(BlockBehaviour.Properties properties, BooleanProperty litProperty,
                                       Collection<Property<?>> stateProperties) {
        return of(properties, litProperty, stateProperties, null);
    }

    /**
     * 带声明默认值（参数名 → 包 JSON {@code default}；可空）。
     * 未声明时 lit 参数按 boolean 惯例默认 false（见 {@link DataDrivenBlock#withDeclaredDefaults(BlockState)}）。
     */
    public static RedstoneLampBlock of(BlockBehaviour.Properties properties, BooleanProperty litProperty,
                                       Collection<Property<?>> stateProperties,
                                       Map<String, String> declaredDefaults) {
        DataDrivenBlockPending.set(DataDrivenBlock.toPropertyMap(stateProperties), declaredDefaults);
        try {
            return new RedstoneLampBlock(properties, litProperty);
        } finally {
            DataDrivenBlockPending.remove();
        }
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState()
                .setValue(litProperty, context.getLevel().hasNeighborSignal(context.getClickedPos()));
    }

    /**
     * 1.21.1 签名（BlockBehaviour）：neighborChanged(state, level, pos, neighbor, neighborPos, isTick)。
     * 与原版 RedstoneLampBlock 逐条对应：点亮立即 setBlock(2)；熄灭 scheduleTick 4 后由 tick 复查。
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor,
                                   BlockPos neighborPos, boolean isTick) {
        if (!level.isClientSide) {
            boolean lit = state.getValue(litProperty);
            boolean powered = level.hasNeighborSignal(pos);
            if (lit != powered) {
                if (lit) {
                    level.scheduleTick(pos, this, 4);
                } else {
                    level.setBlock(pos, state.cycle(litProperty), 2);
                }
            }
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource rand) {
        if (state.getValue(litProperty) && !level.hasNeighborSignal(pos)) {
            level.setBlock(pos, state.cycle(litProperty), 2);
        }
    }

    /** 供测试/诊断：本方块受红石控制的布尔参数 */
    public BooleanProperty litProperty() {
        return litProperty;
    }
}
