package example.contentpack.mapv2;

import com.mojang.logging.LogUtils;
import example.contentpack.biomepreview.preview.BiomeRegion;
import example.contentpack.biomepreview.preview.BiomeRegionGrid;
import example.contentpack.mapv2.client.MinimapInfoLayout;
import example.contentpack.mapv2.view.MapProjection;
import example.contentpack.mapv2.view.MapZoom;
import example.contentpack.mapv2.view.UiScale;
import org.slf4j.Logger;

import java.util.List;

/**
 * 画布坐标系 + 缩放档位 + LOD 选择 + 立体光暗的纯逻辑自检，启动时跑一次。
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
        if (run("relief-shading", MapV2SelfTest::testReliefShading)) {
            passed++;
        } else {
            failed++;
        }
        if (run("ui-scale-resolver", MapV2SelfTest::testUiScaleResolver)) {
            passed++;
        } else {
            failed++;
        }
        if (run("minimap-info-layout", MapV2SelfTest::testMinimapInfoLayout)) {
            passed++;
        } else {
            failed++;
        }
        if (run("biome-region-grid", MapV2SelfTest::testBiomeRegionGrid)) {
            passed++;
        } else {
            failed++;
        }
        if (run("layer-marks", MapV2SelfTest::testLayerMarks)) {
            passed++;
        } else {
            failed++;
        }
        if (run("layer-stack", MapV2SelfTest::testLayerStack)) {
            passed++;
        } else {
            failed++;
        }
        if (run("blob-transfer", MapV2SelfTest::testBlobTransfer)) {
            passed++;
        } else {
            failed++;
        }
        if (run("tile-key", MapV2SelfTest::testTileKey)) {
            passed++;
        } else {
            failed++;
        }
        if (run("reveal-range", MapV2SelfTest::testRevealRange)) {
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

    /**
     * 立体光暗的符号与归一：
     * 本格高于西北邻格（迎光）变亮、低于（背光）变暗、等高不变；
     * 邻格未知（瓦片边缘）不参与；坡降按 step 归一，缩小时同一座山不会越缩越黑。
     */
    private static void testReliefShading() {
        int base = 0xFF808080;
        int flat = MapShade.applyRelief(base, 70, 70, 70, 1, 100);
        require(red(flat) == 128 && green(flat) == 128, "flat ground must keep its color");

        require(MapShade.applyRelief(base, 70, 69, 69, 1, 0) == base,
                "strength 0 must be a no-op");
        require(MapShade.applyRelief(base, 70, MapShade.NO_HEIGHT, MapShade.NO_HEIGHT, 1, 100) == base,
                "unknown neighbours must not shade");

        int lit = MapShade.applyRelief(base, 70, 69, 69, 1, 100);
        int dark = MapShade.applyRelief(base, 70, 71, 71, 1, 100);
        require(red(lit) > 128, "higher than both west and north must brighten: " + red(lit));
        require(red(dark) < 128, "lower than both west and north must darken: " + red(dark));
        require(red(MapShade.applyRelief(base, 70, 69, MapShade.NO_HEIGHT, 1, 100)) > 128,
                "one known neighbour is enough to shade");

        // 1 方块/格 与 8 方块/8 格 是同一个坡度
        require(Math.abs(red(MapShade.applyRelief(base, 71, 70, 70, 1, 100))
                        - red(MapShade.applyRelief(base, 78, 70, 70, 8, 100))) <= 1,
                "slope must be normalised by step");

        require(red(MapShade.applyRelief(base, 70, 66, 66, 1, 200))
                        > red(MapShade.applyRelief(base, 70, 66, 66, 1, 100)),
                "strength must scale the effect");
        require(red(MapShade.applyRelief(0xFFFFFFFF, 70, 60, 60, 1, 200)) <= 255,
                "channels must clamp");
    }

    /** 0 = 跟随系统；1..4 = 绝对覆盖；手改出来的越界值回落到系统，且系统值永不为 0。 */
    private static void testUiScaleResolver() {
        require(UiScale.resolve(UiScale.FOLLOW_SYSTEM, 2) == 2, "0 must follow the system scale");
        require(UiScale.resolve(UiScale.FOLLOW_SYSTEM, 4) == 4, "follow must track high system scales");
        require(UiScale.resolve(1, 4) == 1, "explicit scale must override the system");
        require(UiScale.resolve(4, 1) == 4, "explicit scale must override a small system scale");
        require(UiScale.resolve(-1, 3) == 3, "negative config must fall back to the system");
        require(UiScale.resolve(9, 2) == 2, "out-of-range config must fall back to the system");
        require(UiScale.resolve(UiScale.FOLLOW_SYSTEM, 0) == 1, "resolved scale must never be zero");
        require(UiScale.resolve(3, 0) == 3, "explicit scale must not depend on the system value");
    }

    /**
     * 信息条排版：行数 = 开启的行数；左/中/右锚点分别贴小地图左缘、中线、右缘。
     * 未知对齐值（手改配置）按右对齐处理，不把文字画到屏幕外。
     */
    private static void testMinimapInfoLayout() {
        require(MinimapInfoLayout.lineCount(false, false, false) == 0, "all lines off must be zero");
        require(MinimapInfoLayout.lineCount(true, false, false) == 1, "coords only must be one line");
        require(MinimapInfoLayout.lineCount(false, true, false) == 1, "biome only must be one line");
        require(MinimapInfoLayout.lineCount(false, false, true) == 1, "time only must be one line");
        require(MinimapInfoLayout.lineCount(true, true, true) == 3, "all lines on must be three lines");

        int left = 10;
        int center = 60;
        int right = 110;
        require(MinimapInfoLayout.alignX(MinimapInfoLayout.LEFT, 40, left, center, right) == 10,
                "left alignment must start at the left anchor");
        require(MinimapInfoLayout.alignX(MinimapInfoLayout.CENTER, 40, left, center, right) == 40,
                "centre alignment must centre on the middle anchor");
        require(MinimapInfoLayout.alignX(MinimapInfoLayout.RIGHT, 40, left, center, right) == 70,
                "right alignment must end at the right anchor");
        require(MinimapInfoLayout.alignX(7, 40, left, center, right) == 70,
                "unknown alignment must fall back to the right anchor");
        require(MinimapInfoLayout.alignX(MinimapInfoLayout.RIGHT, 200, left, center, right) == -90,
                "over-wide text must keep its right edge");
    }

    /**
     * 脏标记边界：方块变化只影响包含它的切片与 {@code top}；
     * 地狱（没有 surface）与主世界（只有 surface）的群系门槛不同。
     */
    private static void testLayerMarks() {
        require(MapLayer.contains("y48", 48), "change at the layer bottom must refresh it");
        require(MapLayer.contains("y48", 63), "change at the layer top must refresh it");
        require(!MapLayer.contains("y48", 64), "change above the layer must not refresh it");
        require(!MapLayer.contains("y32", 48), "change above y32..47 must not refresh it");
        require(!MapLayer.contains(MapLayer.TOP, 0), "top is not a section");
        require(!MapLayer.contains("yfoo", 0), "bad layer names are never affected");
        require(MapLayer.contains("y-16", -16), "negative sections still work");
        require(MapLayer.contains("y-16", -1), "negative sections cover up to y=-1");
        require(!MapLayer.contains("y-16", -17), "negative sections stay bounded");
        require(!MapLayer.contains("y-16", 0), "negative sections stay bounded above");

        require(MapLayer.showsBiome(MapLayer.TOP, false), "overworld surface shows biome");
        require(!MapLayer.showsBiome("y48", false), "overworld cave layers hide biome");
        require(MapLayer.showsBiome("y48", true), "nether sections show biome");
        require(MapLayer.showsBiome(MapLayer.TOP, true), "nether top still shows biome");
    }

    /**
     * 叠层顺序：切片只叠自己及以下、有数据的层，从低到高绘制（高者覆盖低者）；
     * 采样是相反顺序；{@code top} 单独成栈，不能垫在切片下面。
     */
    private static void testLayerStack() {
        List<String> available = List.of(MapLayer.TOP, "y-64", "y-48", "y16", "y64", "yfoo");
        require(MapLayerStack.build(available, "y32").equals(List.of("y-64", "y-48", "y16", "y32")),
                "stack must keep selected and everything below, ascending: "
                        + MapLayerStack.build(available, "y32"));
        require(MapLayerStack.build(available, MapLayer.TOP).equals(List.of(MapLayer.TOP)),
                "top must not stack sections under it");
        require(MapLayerStack.build(available, "y48").equals(List.of("y-64", "y-48", "y16", "y48")),
                "selected layer stays in the stack even without data");
        require(MapLayerStack.sampleOrder(available, "y32")
                        .equals(List.of("y32", "y16", "y-48", "y-64")),
                "sample order must be top-down: "
                        + MapLayerStack.sampleOrder(available, "y32"));
        require(MapLayerStack.build(List.of(MapLayer.TOP), "y0").equals(List.of("y0")),
                "an empty stack still contains the selected layer");
        require(MapLayerStack.sampleOrder(available, MapLayer.TOP).equals(List.of(MapLayer.TOP)),
                "top samples only itself");
    }

    /**
     * 圆形预测网格：任意半径/预算下步长都是 4 的倍数，每轴采样点数不超预算；
     * 圆外格子是 {@link BiomeRegion#UNKNOWN}，查询方形外不读调色板；
     * 3×3 节点覆盖任意 2×2 spacing 的方形（格心到最近节点距离 ≤ spacing/√2）。
     */
    private static void testBiomeRegionGrid() {
        require(BiomeRegionGrid.stepForRadius(2048, 129) == 32, "default profile is 32 blocks/cell");
        require(BiomeRegionGrid.sizeForRadius(2048, 32) == 129, "default profile is 129 cells/axis");
        require(BiomeRegionGrid.stepForRadius(256, 129) == 4, "small radius clamps to MIN_STEP");
        require(BiomeRegionGrid.stepForRadius(8192, 129) == 128, "large radius doubles the step");
        require(BiomeRegionGrid.stepForRadius(2048, 33) == 128, "tight budget widens the step");

        int[] radii = {256, 512, 1024, 2048, 4096, 8192};
        int[] budgets = {33, 65, 129, 257, 513};
        for (int radius : radii) {
            for (int maxCells : budgets) {
                int step = BiomeRegionGrid.stepForRadius(radius, maxCells);
                require(step >= BiomeRegionGrid.MIN_STEP, "step below MIN_STEP: " + step);
                require(step % BiomeRegionGrid.MIN_STEP == 0, "step must be a multiple of 4: " + step);
                require(step <= radius, "step must not exceed the radius: r=" + radius + " step=" + step);
                int size = BiomeRegionGrid.sizeForRadius(radius, step);
                require(size >= 3, "size must include both edges: " + size);
                require(size <= maxCells,
                        "size over budget: r=" + radius + " max=" + maxCells + " step=" + step
                                + " size=" + size);
                // 采样点恰好铺满直径：center ± radius 都落在格点上
                require((size - 1) * step == radius * 2, "cells must span the diameter: r=" + radius);
            }
        }

        require(BiomeRegionGrid.insideCircle(0, 0, 4), "centre is inside");
        require(BiomeRegionGrid.insideCircle(4, 0, 4), "edge is inside");
        require(BiomeRegionGrid.insideCircle(0, -4, 4), "edge is inside");
        require(!BiomeRegionGrid.insideCircle(4, 1, 4), "just past the edge is outside");
        require(!BiomeRegionGrid.insideCircle(3, 3, 4), "corner is outside");

        int radius = 4;
        int step = 2;
        int size = BiomeRegionGrid.sizeForRadius(radius, step);
        short[] cells = new short[size * size];
        int[] colors = new int[size * size];
        String[] biomeIds = new String[size * size];
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                int dx = -radius + col * step;
                int dz = -radius + row * step;
                int index = row * size + col;
                cells[index] = BiomeRegionGrid.insideCircle(dx, dz, radius)
                        ? (short) index
                        : BiomeRegion.UNKNOWN;
                colors[index] = 0xFF000000 | index;
                biomeIds[index] = "test:biome_" + index;
            }
        }
        BiomeRegion region = new BiomeRegion(null, 0, 0, radius, step, size, colors, biomeIds, cells);

        require(region.contains(0, 0), "centre must be contained");
        require(region.contains(radius, -radius), "square corners stay contained");
        require(!region.contains(radius + 1, 0), "past the square edge is not contained");
        require(region.cellIndexAt(-radius, -radius) == 0, "top-left sample index");
        require(region.cellIndexAt(radius, radius) == size * size - 1, "bottom-right sample index");
        require(region.cellIndexAt(radius + 1, 0) == -1, "outside the square has no cell");

        require(region.paletteIndexAt(-radius, -radius) == BiomeRegion.UNKNOWN,
                "corner sample is outside the circle");
        require(region.paletteIndexAt(radius, radius) == BiomeRegion.UNKNOWN,
                "corner sample is outside the circle");
        int centre = region.paletteIndexAt(0, 0);
        require(centre == 2 * size + 2, "centre sample index: " + centre);
        require(region.colorAt(0, 0) == colors[centre], "colour must follow the palette");
        require(biomeIds[centre].equals(region.biomeIdAt(0, 0)), "biome id must follow the palette");
        require(region.colorAt(0, 0) != 0, "centre sample must be known");
        require(region.paletteIndexAt(radius + 1, 0) == BiomeRegion.UNKNOWN,
                "square-outside lookup must not read the palette");
        require(region.colorAt(radius + 1, 0) == 0, "unknown colour is transparent");
        require(region.biomeIdAt(radius + 1, 0) == null, "unknown biome is null");

        // 节点以世界原点为锚，半开区间 [start, start + spacing) 归属同一节点
        int spacing = 2048;
        int[] blocks = {-4097, -4096, -2049, -1, 0, 1, 2047, 2048, 4097, 12345, -12345};
        for (int block : blocks) {
            int start = BiomeRegionGrid.gridCenter(BiomeRegionGrid.gridIndex(block, spacing), spacing);
            require(start <= block && block < start + spacing,
                    "grid node must own its half-open interval: block=" + block + " start=" + start);
        }

        // 3×3 个节点覆盖任意 2×2 spacing 的方形
        double limit = spacing / Math.sqrt(2.0);
        for (int i = 0; i <= 64; i++) {
            for (int j = 0; j <= 64; j++) {
                double x = -spacing + spacing * i / 32.0;
                double z = -spacing + spacing * j / 32.0;
                int ix = BiomeRegionGrid.gridIndex((int) Math.floor(x), spacing);
                int iz = BiomeRegionGrid.gridIndex((int) Math.floor(z), spacing);
                double nearest = Double.MAX_VALUE;
                for (int ax = -1; ax <= 1; ax++) {
                    for (int az = -1; az <= 1; az++) {
                        double nx = BiomeRegionGrid.gridCenter(ix + ax, spacing);
                        double nz = BiomeRegionGrid.gridCenter(iz + az, spacing);
                        nearest = Math.min(nearest, Math.hypot(x - nx, z - nz));
                    }
                }
                require(nearest <= limit + 1e-6,
                        "3x3 nodes must cover 2x2 spacing: point=(" + x + "," + z + ") d=" + nearest);
            }
        }
    }

    /** 分块传输：往返、拒收、超时、预算（复用 transfer 包里的纯逻辑用例）。 */
    private static void testBlobTransfer() {
        example.contentpack.mapv2.transfer.TransferSelfTest.run();
    }

    /** 网分发的瓦片 key 编解码。 */
    private static void testTileKey() {
        example.contentpack.mapv2.network.TileSyncSelfTest.run();
    }

    /** 探明范围：网格位序、半径圆、负坐标、增量幂等、结构单元对齐。 */
    private static void testRevealRange() {
        example.contentpack.mapv2.reveal.RevealSelfTest.run();
    }

    private static int red(int argb) {
        return (argb >> 16) & 0xFF;
    }

    private static int green(int argb) {
        return (argb >> 8) & 0xFF;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
