package example.contentpack.hudlayout.ui;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 拖动吸附的纯计算：被拖框的「左/中/右」（或「上/中/下」）三条线，与候选线比距离。
 *
 * <p>只改位置，不改任何关系——两个框对齐与否不产生从属/配对（2026-11-13 拆开的那条语义，
 * 2026-11-15 随调试框删除后按它重新落地）。
 */
final class UiSnap {

    private UiSnap() {
    }

    /**
     * @param start     网格吸附后的绝对起点（左或上）
     * @param size      框在这一轴上的尺寸
     * @param lines     候选线，<b>按优先级排好</b>：同距离时取靠前的
     * @param threshold 超过这个距离不吸
     * @return {@code null}，或 {@code {吸附后的绝对起点, 命中的线}}；第二项用来画参考线
     */
    static int @Nullable [] axis(int start, int size, List<Integer> lines, int threshold) {
        int bestDistance = threshold + 1;
        int bestStart = start;
        int bestLine = start;
        for (int edge = 0; edge <= 2; edge++) {
            int at = start + size * edge / 2;
            for (int line : lines) {
                int distance = Math.abs(line - at);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestStart = start + (line - at);
                    bestLine = line;
                }
            }
        }
        return bestDistance <= threshold ? new int[]{bestStart, bestLine} : null;
    }
}
