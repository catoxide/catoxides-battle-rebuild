package example.contentpack.tech;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Optional;

/** 研究台方块的定位与层级读写（方块 id 与 tier 参数都以这里为准）。 */
public final class TechBlocks {

    public static final ResourceLocation RESEARCH_TABLE =
            ResourceLocation.fromNamespaceAndPath("tech_tree_pack", "research_table");
    public static final String TIER_PROPERTY = "tier";

    private static Block cached;

    private TechBlocks() {
    }

    public static Block researchTable() {
        if (cached == null) {
            cached = BuiltInRegistries.BLOCK.getOptional(RESEARCH_TABLE).orElse(null);
        }
        return cached;
    }

    public static boolean isResearchTable(BlockState state) {
        Block block = researchTable();
        return block != null && state.is(block);
    }

    /** 方块是研究台时返回层级（1..N），否则返回 0。 */
    public static int tierAt(Level level, BlockPos pos) {
        if (level == null || pos == null || !level.isLoaded(pos)) {
            return 0;
        }
        BlockState state = level.getBlockState(pos);
        return isResearchTable(state) ? tier(state) : 0;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static int tier(BlockState state) {
        Property property = tierProperty(state);
        return property == null ? 1 : parseTier(state.getValue(property));
    }

    /** 返回把层级改成 tier 后的状态；该层级不存在时原样返回。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static BlockState withTier(BlockState state, int tier) {
        Property<?> property = tierProperty(state);
        if (property == null) {
            return state;
        }
        Optional<?> value = property.getValue(String.valueOf(tier));
        if (value.isEmpty()) {
            return state;
        }
        return state.setValue((Property) property, (Comparable) value.get());
    }

    private static Property<?> tierProperty(BlockState state) {
        return state.getBlock().getStateDefinition().getProperty(TIER_PROPERTY);
    }

    private static int parseTier(Object value) {
        if (value instanceof Integer integer) {
            return integer;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
