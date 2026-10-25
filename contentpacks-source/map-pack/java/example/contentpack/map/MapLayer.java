package example.contentpack.map;

import net.minecraft.client.multiplayer.ClientLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * 地图层的命名与坐标换算。
 *
 * <ul>
 *   <li>{@link #TOP} —— 全高从上往下（主世界/末地默认）。</li>
 *   <li>{@code y<absoluteY>} —— 从该绝对 y 开始的 16 格高切片（地狱默认，主世界地下可选）。</li>
 * </ul>
 *
 * <p><b>层名用绝对 y，不用「第几层」：</b>世界高度是可改的（数据包 min_y / height），
 * 索引会在高度变化后整体错位；绝对 y 让旧存档仍然对应它当初记录的高度。
 * 切片边界始终以 {@code level.getMinBuildHeight()} 为起点对齐（MC 要求 min_y 是 16 的倍数）。
 */
public final class MapLayer {

    public static final String TOP = "top";
    public static final int SECTION_HEIGHT = 16;

    private MapLayer() {
    }

    public static String section(int absoluteY) {
        return "y" + absoluteY;
    }

    public static boolean isSection(String layer) {
        return layer.length() > 1 && layer.charAt(0) == 'y';
    }

    /** 玩家 y 所在切片的起始绝对 y（对齐到该维度的 section 网格）。 */
    public static int sectionStart(int blockY, int minBuildHeight) {
        return minBuildHeight + Math.floorDiv(blockY - minBuildHeight, SECTION_HEIGHT) * SECTION_HEIGHT;
    }

    /** 层名 → 起始绝对 y；非切片层返回 {@link Integer#MIN_VALUE}。 */
    public static int absoluteY(String layer) {
        if (!isSection(layer)) {
            return Integer.MIN_VALUE;
        }
        try {
            return Integer.parseInt(layer.substring(1));
        } catch (NumberFormatException ex) {
            return Integer.MIN_VALUE;
        }
    }

    public static boolean contains(String layer, int y) {
        int minY = absoluteY(layer);
        return minY != Integer.MIN_VALUE && y >= minY && y < minY + SECTION_HEIGHT;
    }

    /** 人类可读标签，用于地图界面的层提示。 */
    public static String label(String layer) {
        if (TOP.equals(layer)) {
            return "surface";
        }
        int minY = absoluteY(layer);
        if (minY == Integer.MIN_VALUE) {
            return layer;
        }
        return "y" + minY + ".." + (minY + SECTION_HEIGHT - 1);
    }

    /** 当前维度所有可切换的切片（按高度从低到高）。 */
    public static List<String> allSections(ClientLevel level) {
        List<String> layers = new ArrayList<>();
        for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y += SECTION_HEIGHT) {
            layers.add(section(y));
        }
        return layers;
    }
}
