package example.contentpack.sablebase.map.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

/**
 * 船体俯视快照的方块颜色。
 *
 * <p>为了不依赖 map-pack 的实现类，这里只复用原版数据：方块 tint（草/树叶等）
 * 优先，其次 {@link MapColor}，最后中性灰。地图包自己的 JSON 覆盖表不参与——
 * 船体主要是木头/金属/玻璃，原版色已经够辨识。
 */
final class SableMapColors {

    private SableMapColors() {
    }

    static int colorFor(BlockState state, Level level, BlockPos pos,
                        int localY, int minY, int maxY) {
        int tint = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, 0);
        int rgb;
        if (tint >= 0) {
            rgb = tint & 0xFFFFFF;
        } else {
            MapColor map = state.getMapColor(level, pos);
            rgb = map == null ? 0x808080 : map.col & 0xFFFFFF;
        }

        float t = maxY <= minY ? 1.0f : (localY - minY) / (float) (maxY - minY);
        t = Math.max(0.0f, Math.min(1.0f, t));
        float shade = 0.62f + 0.38f * t;

        int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * shade));
        int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * shade));
        int b = Math.min(255, (int) ((rgb & 0xFF) * shade));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** ARGB → NativeImage 的 ABGR 内存布局（与 map-pack 的实测结论一致）。 */
    static int toNativeAbgr(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }
}
