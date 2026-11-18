package com.catoxide.catoxidesbattlerebuild.core.debug;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调试世界（{@code minecraft:debug_all_block_states}）里不参与摆放的方块。
 *
 * <p>调试世界会把每个方块状态各放一格。有的方块只有在被摆放/被邻居更新后才会失控
 * （当前已知 {@code simulated:redstone_accumulator} 与 {@code simulated:redstone_inductor}：
 * 每 tick 触发邻居更新，在无支撑方块成片的调试网格里引发更新链爆炸并刷出大量掉落物），
 * 进世界即卡死。{@code simulated:spring} 没有配对端时每 tick 走 {@code destroyBlock(pos, true)}，
 * 而调试世界的 {@code setBlock} 恒返回 false —— 方块删不掉、掉落物每 tick 刷一份；
 * {@code simulated:merging_glue} 的 {@code tick} 在 {@code partnerPosition} 为空时同样每 tick
 * {@code breakGlue} → {@code destroyBlock}，失败模式一致。
 *
 * <p>隐藏只把该格的返回值改成空气；网格索引与其它方块的位置不变。
 */
public final class DebugWorldBlockHider {

    private static final Set<ResourceLocation> HIDDEN = ConcurrentHashMap.newKeySet();

    static {
        HIDDEN.add(ResourceLocation.parse("simulated:redstone_accumulator"));
        HIDDEN.add(ResourceLocation.parse("simulated:redstone_inductor"));
        HIDDEN.add(ResourceLocation.parse("simulated:spring"));
        HIDDEN.add(ResourceLocation.parse("simulated:torsion_spring"));
        HIDDEN.add(ResourceLocation.parse("simulated:merging_glue"));
    }

    private DebugWorldBlockHider() {
    }

    public static void hide(String blockId) {
        HIDDEN.add(ResourceLocation.parse(blockId));
    }

    public static boolean isHidden(BlockState state) {
        return state != null && isHidden(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    public static boolean isHidden(ResourceLocation blockId) {
        return blockId != null && HIDDEN.contains(blockId);
    }
}
