package example.contentpack.mapv2.client;

import com.mojang.blaze3d.platform.NativeImage;
import example.contentpack.mapv2.MapShade;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiPredicate;

/**
 * 群系预测层的「果冻」画法：柔和低饱和填色 + 同色系描边。
 *
 * <p>只在未采集像素上画；描边按**世界坐标**比较右/下邻居的群系 ID，
 * 所以区域边缘和全屏缩放时都连续，不会在区域边界断开。
 *
 * <p>颜色来源分两段：客户端已加载的区块用世界真实群系（已确认），
 * 没加载的才问 {@link MapBiomeLayer} 的预测。预测只补看不到的地方。
 *
 * <p>成本：每像素 1 次群系查询（已加载处按 4 格缓存）、描边再 2 次预测查询，
 * 只在纹理缓存失效时发生（拖动/首次看到）。
 */
final class BiomeOverlay {

    /** 末地不做群系底图：群系铺满整片虚空，图上全是假的陆地色；悬停与结构照旧。 */
    private static final ResourceLocation END_DIMENSION = Level.END.location();

    private BiomeOverlay() {
    }

    /**
     * @param size   纹理边长（像素）
     * @param step   每像素覆盖的方块数
     * @param level  当前客户端世界；{@code null} 时退化为纯预测
     * @param known  (x, z) → 该像素已有真实地形（跳过，不覆盖）
     * @return 实际画上的像素数（0 = 这块没有任何群系数据）
     */
    static int paint(NativeImage image, ResourceLocation dimension, @Nullable ClientLevel level,
                     int originX, int originZ, int size, double step,
                     BiPredicate<Integer, Integer> known, boolean outline) {
        if (END_DIMENSION.equals(dimension)) {
            return 0;
        }
        int painted = 0;
        // 群系按 4 格 quart 存储：同一夸脱内的相邻像素直接复用上次查询结果
        int lastQuartX = Integer.MIN_VALUE;
        int lastQuartZ = Integer.MIN_VALUE;
        String lastActual = null;
        for (int z = 0; z < size; z++) {
            int worldZ = originZ + (int) Math.round(z * step);
            int nextZ = originZ + (int) Math.round((z + 1) * step);
            for (int x = 0; x < size; x++) {
                if (known.test(x, z)) {
                    continue;
                }
                int worldX = originX + (int) Math.round(x * step);
                String biomeId;
                if (level != null && MapProbe.isLoaded(level, worldX, worldZ)) {
                    int quartX = worldX >> 2;
                    int quartZ = worldZ >> 2;
                    if (quartX != lastQuartX || quartZ != lastQuartZ) {
                        lastQuartX = quartX;
                        lastQuartZ = quartZ;
                        lastActual = MapProbe.loadedSurfaceBiomeId(level, worldX, worldZ);
                    }
                    biomeId = lastActual;
                } else {
                    biomeId = MapBiomeLayer.biomeIdAt(dimension, worldX, worldZ);
                }
                if (biomeId == null) {
                    continue;
                }
                // 颜色由本地调色板决定（不同群系不同色系），未收录的用稳定哈希色
                int color = BiomePalette.color(biomeId);
                // 描边只看预测源：两边同属已加载时预测≈真实，边界位置不会跑。
                // 邻居没有预测（探明范围外）时不描边，否则范围边界会被画成一圈假群系边界。
                boolean edge = false;
                if (outline) {
                    String right = MapBiomeLayer.biomeIdAt(dimension,
                            originX + (int) Math.round((x + 1) * step), worldZ);
                    String down = MapBiomeLayer.biomeIdAt(dimension, worldX, nextZ);
                    edge = (right != null && !biomeId.equals(right))
                            || (down != null && !biomeId.equals(down));
                }
                int argb = edge ? outline(color) : jelly(color);
                image.setPixelRGBA(x, z, MapShade.toNativeAbgr(argb));
                painted++;
            }
        }
        return painted;
    }

    /** 果冻填充：降饱和 + 提亮，低对比，像半透明软块。 */
    private static int jelly(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int gray = (r * 30 + g * 59 + b * 11) / 100;
        r = clamp((r * 62 + gray * 38) / 100 + 20);
        g = clamp((g * 62 + gray * 38) / 100 + 20);
        b = clamp((b * 62 + gray * 38) / 100 + 20);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 描边：同色压暗，不破坏色系。 */
    private static int outline(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        r = r * 48 / 100;
        g = g * 48 / 100;
        b = b * 48 / 100;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
