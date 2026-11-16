package example.contentpack.tech.compat;

import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import example.contentpack.tech.TechBlocks;
import example.contentpack.tech.TechService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 转换机方块实体：把接入的转速折算成研究点，写入水平朝向（输出面）前方那张研究台。
 *
 * <p>只有「研究台就在输出面相邻格」时才转化；转化条件：
 * 转速 ≥ {@link #MIN_SPEED}（RPM）且网络未过载。转速越高转化越快。
 * 小数部分累积在 {@link #pendingPoints} 里并随方块实体存档。
 *
 * <p>数值全部是<b>待实测调参</b>的占位：{@link #POINTS_PER_RPM_TICK} 为每 RPM·tick 产出的研究点，
 * {@link #STRESS_APPLIED} 为机器占用的应力（SU）。接入正式配方后按实际平衡调整。
 */
public class StressConverterBlockEntity extends KineticBlockEntity {

    /** 待实测调参：每 RPM·tick 产出的研究点。 */
    private static final double POINTS_PER_RPM_TICK = 1.0 / 512.0;
    /** 待实测调参：低于该转速（RPM）不转化。 */
    private static final float MIN_SPEED = 16.0F;
    /** 待实测调参：机器占用的应力（SU）。 */
    private static final float STRESS_APPLIED = 8.0F;

    private double pendingPoints;

    public StressConverterBlockEntity(BlockPos pos, BlockState state) {
        super(CreateStressCompat.BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public float calculateStressApplied() {
        return STRESS_APPLIED;
    }

    @Override
    public float calculateAddedStressCapacity() {
        return 0.0F;
    }

    @Override
    public float getGeneratedSpeed() {
        return 0.0F;
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level instanceof ServerLevel serverLevel) || isOverStressed()) {
            return;
        }
        float speed = Math.abs(getSpeed());
        if (speed < MIN_SPEED) {
            return;
        }
        BlockPos target = worldPosition.relative(getBlockState().getValue(HorizontalKineticBlock.HORIZONTAL_FACING));
        if (!TechBlocks.isResearchTable(serverLevel.getBlockState(target))) {
            return;
        }

        pendingPoints += speed * POINTS_PER_RPM_TICK;
        int whole = (int) pendingPoints;
        if (whole <= 0) {
            return;
        }
        pendingPoints -= whole;
        TechService.addPoints(serverLevel, target, whole);
        setChanged();
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        tag.putDouble("PendingPoints", pendingPoints);
        super.write(tag, registries, clientPacket);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        pendingPoints = tag.getDouble("PendingPoints");
    }
}
