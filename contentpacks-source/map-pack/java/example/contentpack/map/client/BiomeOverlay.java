package example.contentpack.map.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.resources.ResourceLocation;

import java.util.function.BiPredicate;

/**
 * 群系预测层的「果冻」画法：柔和低饱和填色 + 同色系描边。
 *
 * <p>只在未采集像素上画；描边按**世界坐标**比较右/下邻居的群系 ID，
 * 所以瓦片边缘和全屏缩放时都连续，不会在 128 格边界断开。
 *
 * <p>成本：每像素 3 次群系查询，只在纹理缓存失效时发生（拖动/首次看到）。
 */
final class BiomeOverlay {

    private BiomeOverlay() {
    }

    /**
     * @param size   纹理边长（像素）
     * @param step   每像素覆盖的方块数
     * @param known  (x, z) → 该像素已有真实地形（跳过，不覆盖）
     */
    static void paint(NativeImage image, ResourceLocation dimension,
                      int originX, int originZ, int size, double step,
                      BiPredicate<Integer, Integer> known, boolean outline) {
        for (int z = 0; z < size; z++) {
            int worldZ = originZ + (int) Math.round(z * step);
            int nextZ = originZ + (int) Math.round((z + 1) * step);
            for (int x = 0; x < size; x++) {
                if (known.test(x, z)) {
                    continue;
                }
                int worldX = originX + (int) Math.round(x * step);
                String biomeId = MapBiomeLayer.biomeIdAt(dimension, worldX, worldZ);
                if (biomeId == null) {
                    continue;
                }
                // 颜色由本地调色板决定（不同群系不同色系），未收录的用稳定哈希色
                int color = BiomePalette.color(biomeId);
                boolean edge = outline && (!biomeId.equals(MapBiomeLayer.biomeIdAt(
                        dimension, originX + (int) Math.round((x + 1) * step), worldZ))
                        || !biomeId.equals(MapBiomeLayer.biomeIdAt(
                        dimension, worldX, nextZ)));
                int painted = edge ? outline(color) : jelly(color);
                image.setPixelRGBA(x, z, MapMinimap.toNativeAbgr(painted));
            }
        }
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
