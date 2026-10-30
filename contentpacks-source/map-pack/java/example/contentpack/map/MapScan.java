package example.contentpack.map;

/**
 * 采集范围与扫描顺序的纯逻辑：不碰世界、不碰渲染，启动自检直接覆盖。
 *
 * <p>扫描顺序决定「刚进世界时地图先从哪里长出来」：由内向外逐圈，
 * 玩家脚下先成片，而不是按行优先从西北角一排排铺。
 */
public final class MapScan {

    /** 再小的半径没有意义（连玩家周围一圈都覆盖不到）。 */
    public static final int MIN_RADIUS = 2;

    private MapScan() {
    }

    /**
     * 实际采集半径（区块）= 客户端视距。
     *
     * <p>不留配置项：曾经有个 {@code captureRadiusChunks}，视距 32 时它是 12，
     * 结果 192 格以外全是没采过的区块格（马赛克），而且从日志上看不出来。
     */
    public static int captureRadius(int renderDistanceChunks) {
        return Math.max(MIN_RADIUS, renderDistanceChunks);
    }

    /**
     * 以 (0,0) 为中心、切比雪夫半径 {@code radius} 的方形，按圈由内向外访问，
     * 每格恰好一次。访问者返回 {@code false} 时立即停止。
     */
    public static void centerOut(int radius, OffsetVisitor visitor) {
        if (radius < 0) {
            return;
        }
        if (!visitor.visit(0, 0)) {
            return;
        }
        for (int ring = 1; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                if (!visitor.visit(dx, -ring) || !visitor.visit(dx, ring)) {
                    return;
                }
            }
            for (int dz = -ring + 1; dz <= ring - 1; dz++) {
                if (!visitor.visit(-ring, dz) || !visitor.visit(ring, dz)) {
                    return;
                }
            }
        }
    }

    @FunctionalInterface
    public interface OffsetVisitor {
        /** @return false 表示不再继续扫描 */
        boolean visit(int dx, int dz);
    }
}
