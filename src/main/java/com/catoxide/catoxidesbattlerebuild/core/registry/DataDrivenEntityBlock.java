package com.catoxide.catoxidesbattlerebuild.core.registry;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 数据驱动方块（批量注册的带方块实体方块载体）
 * <p>
 * 继承 {@link BaseEntityBlock}（1.21.1 里「带方块实体的方块」= 实现
 * {@code EntityBlock} 接口，{@link net.minecraft.world.level.block.state.BlockState#hasBlockEntity()}
 * 按此判定）。与 {@link DataDrivenBlock} 共用同一套
 * {@code ThreadLocal} 状态参数注入流程（见其类文档）。
 *
 * <p>与纯 {@code BaseEntityBlock} 的两个有意差异：
 * <ul>
 *   <li>{@link #getRenderShape(BlockState)} 返回 {@link RenderShape#MODEL}
 *       （容器类方块才 INVISIBLE；数据块要按模型渲染）；</li>
 *   <li>{@link #codec()} 返回 {@code MapCodec.unit(this)}：1.21.1 的 block codec 派发
 *       （{@code BlockTypes.CODEC}）无实际游戏消费路径，unit 编解码保证任何意外调用
 *       都还原到注册表里的同一实例，而不是丢失状态空间的空壳。</li>
 * </ul>
 */
public class DataDrivenEntityBlock extends BaseEntityBlock {

    private final BlockEntityType.BlockEntitySupplier<? extends BlockEntity> beSupplier;
    private final List<Property<?>> properties;

    protected DataDrivenEntityBlock(
            BlockBehaviour.Properties properties,
            BlockEntityType.BlockEntitySupplier<? extends BlockEntity> beSupplier) {
        super(properties);
        this.beSupplier = beSupplier;
        DataDrivenBlockPending.Pending pending = DataDrivenBlockPending.get();
        this.properties = pending != null ? List.copyOf(pending.properties().values()) : List.of();
        if (pending != null) {
            // 同 DataDrivenBlock：registerDefaultState 为 final，super() 后按声明默认值重新注册
            //（boolean 缺省 false，原版惯例）
            this.registerDefaultState(DataDrivenBlock.withDeclaredDefaults(this.stateDefinition.any()));
        }
    }

    public static DataDrivenEntityBlock of(
            BlockBehaviour.Properties properties,
            Collection<Property<?>> stateProperties,
            BlockEntityType.BlockEntitySupplier<? extends BlockEntity> beSupplier) {
        return of(properties, stateProperties, beSupplier, null);
    }

    /**
     * 带声明默认值的构建（参数名 → 包 JSON {@code default}；可空）。
     * 默认状态覆盖逻辑与 {@link DataDrivenBlock} 相同（boolean 缺省 false 惯例）。
     */
    public static DataDrivenEntityBlock of(
            BlockBehaviour.Properties properties,
            Collection<Property<?>> stateProperties,
            BlockEntityType.BlockEntitySupplier<? extends BlockEntity> beSupplier,
            Map<String, String> declaredDefaults) {
        DataDrivenBlockPending.set(DataDrivenBlock.toPropertyMap(stateProperties), declaredDefaults);
        try {
            return new DataDrivenEntityBlock(properties, beSupplier);
        } finally {
            DataDrivenBlockPending.remove();
        }
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        DataDrivenBlockPending.Pending pending = DataDrivenBlockPending.get();
        if (pending != null) {
            pending.properties().values().forEach(builder::add);
        }
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return beSupplier.create(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return MapCodec.unit(this);
    }

    /** 本方块声明的状态参数（按声明序） */
    public List<Property<?>> getStateProperties() {
        return properties;
    }

    /** 方块实体供应器（调试/查询用） */
    public BlockEntityType.BlockEntitySupplier<? extends BlockEntity> getBeSupplier() {
        return beSupplier;
    }
}
