package example.contentpack.tech.compat;

import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 应力 → 研究点转换机（Create 动力学方块）。
 * <p>{@code HORIZONTAL_FACING} 是<b>输出面</b>：输出面朝向研究台；轴从 FACING 的背面接入。
 * 用 {@link HorizontalKineticBlock} 而非 {@code DirectionalKineticBlock}，
 * 保证轴恒为水平（不出现朝上/朝下的放置）。
 * 只有背面有轴，所以整机不会把旋转继续传到输出侧。
 */
public class StressConverterBlock extends HorizontalKineticBlock
        implements IBE<StressConverterBlockEntity> {

    public StressConverterBlock(Properties properties) {
        super(properties);
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(HORIZONTAL_FACING).getAxis();
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state,
                                   Direction side) {
        return side == state.getValue(HORIZONTAL_FACING).getOpposite();
    }

    @Override
    public Class<StressConverterBlockEntity> getBlockEntityClass() {
        return StressConverterBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends StressConverterBlockEntity> getBlockEntityType() {
        return CreateStressCompat.BLOCK_ENTITY.get();
    }
}
