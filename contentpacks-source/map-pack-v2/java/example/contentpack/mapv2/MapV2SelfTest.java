package example.contentpack.mapv2;

import com.mojang.logging.LogUtils;
import example.contentpack.mapv2.view.MapProjection;
import example.contentpack.mapv2.view.MapZoom;
import org.slf4j.Logger;

/**
 * 画布坐标系 + 缩放档位 + LOD 选择的纯逻辑自检，启动时跑一次。
 *
 * <p>失败用 ERROR，{@code scripts/validate-game.ps1} 会当成 FAIL 抓出来。
 */
public final class MapV2SelfTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private MapV2SelfTest() {
    }

    public static void run() {
        int passed = 0;
        int failed = 0;
        if (run("zoom-ladder", MapV2SelfTest::testZoomLadder)) {
            passed++;
        } else {
            failed++;
        }
        if (run("zoom-stepping", MapV2SelfTest::testZoomStepping)) {
            passed++;
        } else {
            failed++;
        }
        if (run("canvas-lattice", MapV2SelfTest::testCanvasLattice)) {
            passed++;
        } else {
            failed++;
        }
        if (run("canvas-roundtrip", MapV2SelfTest::testCanvasRoundtrip)) {
            passed++;
        } else {
            failed++;
        }

        LOGGER.info("[MapV2Test] passed={} failed={}", passed, failed);
        if (failed > 0) {
            LOGGER.error("[MapV2Test] FAILURES={}", failed);
        }
    }

    private static boolean run(String name, Runnable test) {
        try {
            test.run();
            return true;
        } catch (Throwable error) {
            LOGGER.error("[MapV2Test] FAIL {}: {}", name, error.toString());
            return false;
        }
    }

    /**
     * 档位与 LOD 的核心不变量：
     * 瓦片在画布上正好铺 {@code tilePixels * bpp == 512} 个方块；
     * {@code bpp >= 1} 时 {@code lodSize * bpp == 512}（一个 texel 一个画布像素）。
     */
    private static void testZoomLadder() {
        double[] steps = MapZoom.steps();
        require(steps.length >= 2, "ladder needs at least two steps");
        require(steps[0] == MapZoom.MIN_BPP, "first step must be MIN_BPP");
        require(steps[steps.length - 1] == MapZoom.MAX_BPP, "last step must be MAX_BPP");
        for (int i = 1; i < steps.length; i++) {
            require(steps[i] > steps[i - 1], "ladder must ascend: " + steps[i - 1] + " -> " + steps[i]);
        }
        for (double bpp : steps) {
            int tilePixels = MapZoom.tilePixels(bpp);
            require(tilePixels > 0, "tile pixels must be positive: bpp=" + bpp);
            require(tilePixels * bpp == MapZoom.TILE_BLOCKS,
                    "tile must cover 512 blocks exactly: bpp=" + bpp + " pixels=" + tilePixels);

            int lod = MapZoom.lod(bpp);
            require(lod >= 0 && lod <= MapZoom.MAX_LOD, "lod out of range: bpp=" + bpp + " lod=" + lod);
            if (bpp <= 1.0) {
                require(lod == 0, "magnified steps must use raw LOD 0: bpp=" + bpp + " lod=" + lod);
            } else {
                int lodSize = MapZoom.lodTextureSize(lod);
                require(lodSize * bpp == MapZoom.TILE_BLOCKS,
                        "one texel must be one canvas pixel: bpp=" + bpp + " lod=" + lod
                                + " lodSize=" + lodSize);
            }
        }
        require(MapZoom.blockTops(1.0 / 16.0), "16 px per block is the block-top threshold");
        require(!MapZoom.blockTops(1.0 / 8.0), "8 px per block is too shallow for block tops");
    }

    private static void testZoomStepping() {
        double[] steps = MapZoom.steps();
        for (int i = 0; i < steps.length; i++) {
            if (i > 0) {
                require(MapZoom.stepIn(steps[i]) == steps[i - 1], "stepIn must move one rung: " + steps[i]);
            }
            if (i < steps.length - 1) {
                require(MapZoom.stepOut(steps[i]) == steps[i + 1], "stepOut must move one rung: " + steps[i]);
            }
        }
        require(MapZoom.stepIn(steps[0]) == steps[0], "stepIn clamps at the deepest rung");
        require(MapZoom.stepOut(steps[steps.length - 1]) == steps[steps.length - 1],
                "stepOut clamps at the shallowest rung");
        // 配置里可能出现任意初始缩放：取最近的相邻档，不跳档
        require(MapZoom.stepOut(1.9) == 2.0, "off-ladder stepOut reaches the next rung: " + MapZoom.stepOut(1.9));
        require(MapZoom.stepIn(1.9) == 1.0, "off-ladder stepIn reaches the previous rung: " + MapZoom.stepIn(1.9));
        require(MapZoom.stepIn(0.7) == 0.5, "off-ladder magnification snaps down: " + MapZoom.stepIn(0.7));
    }

    /**
     * 放大档（{@code bpp <= 1}）视图原点吸附到像素格点后，瓦片起点、方块边缘、玩家标记
     * 必须落在同一套整像素上：标记相对所在方块边缘的偏移 = 真实小数位置的 {@code floor}。
     *
     * <p>缩小档（{@code bpp > 1}）一个像素覆盖多个方块，方块边界本来就落在半像素上，
     * 格点断言不适用；它们由 {@link #testCanvasRoundtrip} 管像素 → 世界 → 像素的往返。
     */
    private static void testCanvasLattice() {
        int[] views = {1000, 1001};
        int[] blocks = {-1025, -512, -1, 0, 1, 511, 512, 1023};
        double[] centers = {0.0, 100.37, -57.8125, 1024.999, 3_000_000.13};
        double[] markers = {100.31, 100.62, 100.87, -0.4, -1024.75};
        for (double bpp : MapZoom.steps()) {
            if (bpp > 1.0) {
                require(!MapProjection.onLattice(bpp),
                        "zoomed-out rung must not claim the pixel lattice: " + bpp);
                continue;
            }
            require(MapProjection.onLattice(bpp), "magnified rung must sit on the pixel grid: " + bpp);
            for (double center : centers) {
                for (int view : views) {
                    double origin = MapProjection.origin(center, view, bpp);
                    double originPixels = origin / bpp;
                    require(Math.abs(originPixels - Math.rint(originPixels)) < 1e-6,
                            "view origin must snap to the pixel grid: bpp=" + bpp + " origin=" + origin);
                    for (int block : blocks) {
                        double exact = (block - origin) / bpp;
                        require(Math.abs(exact - Math.rint(exact)) < 1e-6,
                                "block edge off the pixel grid: bpp=" + bpp + " block=" + block);
                        require(MapProjection.pixel(block, origin, bpp) == (int) Math.rint(exact),
                                "block edge pixel mismatch: bpp=" + bpp + " block=" + block);
                    }
                    for (double marker : markers) {
                        double edge = Math.floor(marker);
                        int expected = (int) Math.floor((marker - edge) / bpp);
                        int actual = MapProjection.pixel(marker, origin, bpp)
                                - MapProjection.pixel(edge, origin, bpp);
                        require(expected == actual,
                                "marker drifted inside its block: bpp=" + bpp + " marker=" + marker
                                        + " expected=" + expected + " actual=" + actual);
                    }
                }
            }
        }
    }

    /** 右键 / 放置 / 状态栏用像素反查世界坐标：整数像素必须能原路回去。 */
    private static void testCanvasRoundtrip() {
        double[] centers = {0.0, 1024.25};
        int[] views = {1000, 1024};
        int[] pixels = {0, 1, 17, 499, 999};
        for (double bpp : MapZoom.steps()) {
            for (double center : centers) {
                for (int view : views) {
                    double origin = MapProjection.origin(center, view, bpp);
                    for (int pixel : pixels) {
                        double world = MapProjection.world(pixel, origin, bpp);
                        require(MapProjection.pixel(world, origin, bpp) == pixel,
                                "pixel -> world -> pixel must roundtrip: bpp=" + bpp + " pixel=" + pixel);
                    }
                }
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
