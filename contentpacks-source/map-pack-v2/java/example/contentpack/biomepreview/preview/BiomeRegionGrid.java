package example.contentpack.biomepreview.preview;

/**
 * 预测区域的分格与采样步长：纯算术，不碰配置和世界。
 *
 * <p>服务端按<b>圆</b>采样（圆外格子留 {@link BiomeRegion#UNKNOWN}），
 * 客户端按半径为 {@code spacing} 的网格请求：3×3 个节点覆盖任意 2×2 个 spacing
 * 的方形区域（格心到最近节点的最大距离 = spacing/√2 &lt; spacing），
 * 于是相邻节点不需要重叠请求，也不会留缝。
 */
public final class BiomeRegionGrid {

    public static final int MIN_STEP = 4;

    private BiomeRegionGrid() {
    }

    /**
     * 在每轴最多 {@code maxCellsPerAxis} 个采样点的约束下选步长。
     *
     * <p>步长是 {@value #MIN_STEP} 的倍数（服务端采样按方块对齐），
     * 结果满足 {@code 2 * radius / step + 1 <= maxCellsPerAxis}；
     * 上限太小导致算出的步长超过半径时，直接取半径（size = 3）。
     */
    public static int stepForRadius(int radiusBlocks, int maxCellsPerAxis) {
        int radius = Math.max(1, radiusBlocks);
        int maxCells = Math.max(3, maxCellsPerAxis);
        int budget = maxCells - 1;
        int needed = (2 * radius + budget - 1) / budget;
        int step = Math.max(MIN_STEP, (needed + MIN_STEP - 1) / MIN_STEP * MIN_STEP);
        return Math.min(step, Math.max(MIN_STEP, radius));
    }

    /** 以步长算出的每轴采样点数。 */
    public static int sizeForRadius(int radiusBlocks, int step) {
        int radius = Math.max(1, radiusBlocks);
        int safeStep = Math.max(1, step);
        return (radius * 2) / safeStep + 1;
    }

    public static boolean insideCircle(int dx, int dz, int radius) {
        long limit = (long) radius * radius;
        return (long) dx * dx + (long) dz * dz <= limit;
    }

    /** 节点中心：格点以世界原点为锚，跨存档稳定，便于磁盘文件名复用。 */
    public static int gridCenter(int index, int spacing) {
        return index * Math.max(1, spacing);
    }

    public static int gridIndex(int block, int spacing) {
        return Math.floorDiv(block, Math.max(1, spacing));
    }
}
