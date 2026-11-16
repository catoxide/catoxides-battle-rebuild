package example.contentpack.mapv2;

import com.mojang.logging.LogUtils;
import example.contentpack.biomepreview.preview.BiomeRegion;
import example.contentpack.biomepreview.preview.BiomeRegionGrid;
import example.contentpack.mapv2.client.MinimapInfoLayout;
import example.contentpack.mapv2.view.MapProjection;
import example.contentpack.mapv2.view.MapZoom;
import example.contentpack.mapv2.view.UiScale;
import org.slf4j.Logger;

import java.util.Arrays;
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
        if (run("tile-biome", MapV2SelfTest::testTileBiome)) {
            passed++;
        } else {
            failed++;
        }
        if (run("tile-stack", MapV2SelfTest::testTileStack)) {
            passed++;
        } else {
            failed++;
        }
        if (run("hover-block", MapV2SelfTest::testHoverBlock)) {
            passed++;
        } else {
            failed++;
        }
        if (run("face-color", MapV2SelfTest::testFaceColor)) {
            passed++;
        } else {
            failed++;
        }
        if (run("flat-cover", MapV2SelfTest::testFlatCover)) {
            passed++;
        } else {
            failed++;
        }
        if (run("surface-stack-rules", MapV2SelfTest::testSurfaceStackRules)) {
            passed++;
        } else {
            failed++;
        }
        if (run("surface-up-face", MapV2SelfTest::testSurfaceUpFace)) {
            passed++;
        } else {
            failed++;
        }
        if (run("waterlogged-split", MapV2SelfTest::testWaterloggedSplit)) {
            passed++;
        } else {
            failed++;
        }
        if (run("fluid-surfaces", MapV2SelfTest::testFluidSurfaces)) {
            passed++;
        } else {
            failed++;
        }
        if (run("bed-surfaces", MapV2SelfTest::testBedSurfaces)) {
            passed++;
        } else {
            failed++;
        }
        if (run("entity-surfaces", MapV2SelfTest::testEntitySurfaces)) {
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
        require(BiomeRegionGrid.stepForRadius(1024, 513) == 4, "default profile is 4 blocks/cell");
        require(BiomeRegionGrid.sizeForRadius(1024, 4) == 513, "default profile is 513 cells/axis");
        require(BiomeRegionGrid.stepForRadius(2048, 513) == 8, "default budget at 2048 radius is 8 blocks/cell");
        require(BiomeRegionGrid.stepForRadius(2048, 129) == 32, "legacy profile is 32 blocks/cell");
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

    /**
     * 群系写进瓦片：缺省为无记录；只有群系变化也要算改动；
     * 补洞要连群系一起搬；磁盘格式往返后群系原样回来。
     */
    private static void testTileBiome() {
        MapTile tile = new MapTile();
        require(tile.biomeAt(0, 0) == null, "未采集像素没有群系");
        tile.set(0, 0, 0xFF00FF00, 70, 0, "minecraft:grass_block", null, "minecraft:plains");
        tile.set(1, 0, 0xFF808080, 64, 0, "minecraft:stone", null, null);
        require("minecraft:plains".equals(tile.biomeAt(0, 0)), "群系应写进像素");
        require(tile.biomeAt(1, 0) == null, "取不到群系时保持无记录");
        require(!tile.set(0, 0, 0xFF00FF00, 70, 0, "minecraft:grass_block", null,
                        "minecraft:plains"),
                "完全相同的重写不算改动");
        require(tile.set(0, 0, 0xFF00FF00, 70, 0, "minecraft:grass_block", null,
                        "minecraft:desert"),
                "只有群系变化也必须算改动（否则旧 v2 瓦片不会重存）");

        MapTile copy = roundtrip(tile);
        require("minecraft:desert".equals(copy.biomeAt(0, 0)), "磁盘往返后群系应原样回来");
        require(copy.biomeAt(1, 0) == null, "无群系像素往返后仍无记录");
        require("minecraft:grass_block".equals(copy.blockAt(0, 0)), "方块通道不受群系影响");

        MapTile filled = new MapTile();
        require(filled.fillUnknownFrom(tile) == 2, "两块已知像素都应补进来");
        require("minecraft:desert".equals(filled.biomeAt(0, 0)), "补洞要连群系一起搬");
    }

    /**
     * 表面栈通道：缺省为空；只有栈变化也算改动；往返和补洞都不丢；
     * 整块没有栈时磁盘上不写下标数组。
     */
    private static void testTileStack() {
        MapStackLayer glass = new MapStackLayer("minecraft:block/glass",
                0, 0, 64, 64, 0, 0, 16, 16,
                "minecraft:glass", 71, 0xFFC0D8E0, 64, 2, 1);
        MapStackLayer grass = new MapStackLayer("minecraft:block/grass_block_top",
                0, 0, 64, 64, 0, 0, 16, 16,
                "minecraft:grass_block", 70, 0xFF00FF00, 255, 1, 0);
        List<MapStackLayer> stack = List.of(glass, grass);

        MapTile tile = new MapTile();
        require(tile.stackAt(0, 0).isEmpty(), "未采集像素没有表面栈");
        require(tile.copyStackIndex() == null, "没有栈时下标数组应保持不分配");
        tile.set(0, 0, 0xFF00FF00, 71, 0, "minecraft:grass_block", stack, null);
        tile.set(1, 0, 0xFF808080, 64, 0, "minecraft:stone", List.of(), null);
        require(tile.stackAt(0, 0).equals(stack), "表面栈应写进像素");
        require(tile.stackAt(1, 0).isEmpty(), "没栈的像素保持空");
        require("minecraft:grass_block".equals(tile.blockAt(0, 0)), "栈不影响终点方块");
        require(tile.heightAt(0, 0) == 71, "高度仍按这一列最高的非空气方块");
        require(!tile.set(0, 0, 0xFF00FF00, 71, 0, "minecraft:grass_block", stack, null),
                "完全相同的重写不算改动");
        require(tile.set(0, 0, 0xFF00FF00, 71, 0, "minecraft:grass_block", List.of(), null),
                "只有表面栈变化也必须算改动");
        tile.set(0, 0, 0xFF00FF00, 71, 0, "minecraft:grass_block", stack, null);

        MapTile copy = roundtrip(tile);
        require(copy.stackAt(0, 0).equals(stack), "磁盘往返后表面栈应原样回来");
        require(copy.stackAt(1, 0).isEmpty(), "空栈像素往返后仍为空");
        require(copy.copyStackIndex() != null, "有栈的瓦片应写出下标数组");

        MapTile filled = new MapTile();
        require(filled.fillUnknownFrom(tile) == 2, "两块已知像素都应补进来");
        require(filled.stackAt(0, 0).equals(stack), "补洞要连表面栈一起搬");

        MapTile plain = new MapTile();
        plain.set(0, 0, 0xFF808080, 64, 0, "minecraft:stone", null, null);
        require(roundtrip(plain).copyStackIndex() == null,
                "整块没有栈时下标数组应保持不分配");

        // 床贴图是整张 64×64：UV 必须能落到像素（6..22 / 28..44），十六等分做不到
        require(MapStackLayer.UV_UNITS == 64, "贴图子矩形按六十四等分存");
        MapStackLayer foot = new MapStackLayer("minecraft:entity/bed/red",
                6, 28, 22, 44, 0, 0, 16, 16, "minecraft:red_bed", 70, 0xFF8A2820, 255, 1, 2);
        MapTile bedTile = new MapTile();
        bedTile.set(2, 2, 0xFF8A2820, 70, 0, "minecraft:red_bed", List.of(foot), null);
        MapStackLayer stored = roundtrip(bedTile).stackAt(2, 2).get(0);
        require(stored.u0() == 6 && stored.v0() == 28
                        && stored.u1() == 22 && stored.v1() == 44,
                "床的 64×64 贴图子矩形往返后应逐像素不变: " + stored);

        // v8：斜放告示牌存 0..15 档；平色层（生物头）没有贴图、UV 全 0
        MapStackLayer diagonal = new MapStackLayer("minecraft:entity/signs/oak",
                2, 0, 26, 4, 0, 7, 16, 9, "minecraft:oak_sign", 71, 0xFFB08A50, 255, 1, 2);
        MapStackLayer skull = new MapStackLayer("", 0, 0, 0, 0, 4, 4, 12, 12,
                "minecraft:skeleton_skull", 72, 0xFFB4B4B4, 255, 1, 0);
        MapTile mixed = new MapTile();
        mixed.set(3, 3, 0xFFB08A50, 72, 0, "minecraft:oak_sign",
                List.of(diagonal, skull), null);
        List<MapStackLayer> back = roundtrip(mixed).stackAt(3, 3);
        require(back.size() == 2, "两层的栈往返后层数不变: " + back);
        require(back.get(0).rotation() == 2, "斜放告示牌的 22.5° 档位要原样保留");
        require(back.get(1).sprite().isEmpty() && back.get(1).rotation() == 0,
                "平色层没有贴图、UV 全 0，往返后保持: " + back.get(1));
        require(back.get(1).x0() == 4 && back.get(1).z1() == 12,
                "平色层投影往返后不变: " + back.get(1));
    }

    /**
     * 悬停按像素命中表面栈：指针在格内哪个 1/16 就报哪一层 —— 半块形状上报形状本身，
     * 露出的部分报下一层；旋转必须和渲染端的位姿互逆。旧瓦片的空栈回落终点方块。
     */
    private static void testHoverBlock() {
        int[][] rects = {{1, 1, 15, 15}, {0, 7, 16, 9}, {0, 0, 4, 16}};
        double[][] probes = {{1.5, 1.5}, {8.5, 8.5}, {14.5, 0.5}, {3.5, 12.5}, {0.5, 15.5}};
        for (int rotation = 0; rotation < 16; rotation++) {
            for (int[] rect : rects) {
                MapStackLayer layer = new MapStackLayer("minecraft:block/stone",
                        0, 0, 64, 64, rect[0], rect[1], rect[2], rect[3],
                        "minecraft:stone", 70, 0xFF808080, 255, 1, rotation);
                for (double[] probe : probes) {
                    boolean inside = probe[0] >= rect[0] && probe[0] < rect[2]
                            && probe[1] >= rect[1] && probe[1] < rect[3];
                    double[] world = rotateLocal(probe[0], probe[1], rotation * 22.5);
                    require(layer.covers(world[0], world[1]) == inside,
                            "指针命中必须与渲染旋转互逆: r=" + rotation
                                    + " rect=" + Arrays.toString(rect) + " probe="
                                    + Arrays.toString(probe) + " world=" + Arrays.toString(world));
                }
            }
        }
        MapStackLayer torch = new MapStackLayer("minecraft:block/torch",
                6, 6, 10, 10, 7, 7, 9, 9, "minecraft:torch", 71, 0xFFFFC000, 255, 1, 0);
        MapStackLayer grass = new MapStackLayer("minecraft:block/grass_block_top",
                0, 0, 64, 64, 0, 0, 16, 16, "minecraft:grass_block", 70, 0xFF6A9E3A, 255, 1, 0);
        require(torch.coverage() < 0.5f, "火把只占一格中间 2×2");
        MapStore.StackSample hovered = new MapStore.StackSample(1, 71, Integer.MIN_VALUE,
                "minecraft:grass_block", List.of(torch, grass), null);
        require("minecraft:torch".equals(hovered.blockAt(8, 8)),
                "指在火把上要报火把: " + hovered.blockAt(8, 8));
        require("minecraft:grass_block".equals(hovered.blockAt(2, 2)),
                "指在露出的地方要报下一层: " + hovered.blockAt(2, 2));
        require("minecraft:grass_block".equals(hovered.block()), "终点方块不变（平色口径）");
        // 残缺旧数据没有任何层覆盖时回落栈顶，信息条不能空
        MapStore.StackSample onlyTorch = new MapStore.StackSample(1, 71, Integer.MIN_VALUE,
                "minecraft:grass_block", List.of(torch), null);
        require("minecraft:torch".equals(onlyTorch.blockAt(2, 2)),
                "没有层覆盖时回落栈顶: " + onlyTorch.blockAt(2, 2));
        MapStore.StackSample legacy = new MapStore.StackSample(1, 70, Integer.MIN_VALUE,
                "minecraft:grass_block", List.of(), null);
        require("minecraft:grass_block".equals(legacy.blockAt(8, 8)),
                "旧瓦片没有表面栈时回落终点方块: " + legacy.blockAt(8, 8));
    }

    /** 与渲染端同一套顺时针旋转：绕格心 (8,8) 在 (x,z) 平面转 degrees。 */
    private static double[] rotateLocal(double x, double z, double degrees) {
        double theta = Math.toRadians(degrees);
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);
        double dx = x - MapStackLayer.UNITS / 2.0;
        double dz = z - MapStackLayer.UNITS / 2.0;
        return new double[]{
                MapStackLayer.UNITS / 2.0 + dx * cos - dz * sin,
                MapStackLayer.UNITS / 2.0 + dx * sin + dz * cos};
    }

    /**
     * 没有 tintindex 的面记录贴图均值：渲染端拿「记录色 ÷ 贴图均值」当顶点色，
     * 记录对了贴图原样画出；记成 {@code MapColor.PLANT}（#007C00）会把粉色花簇乘成纯绿。
     */
    private static void testFaceColor() {
        require(MapColors.spriteOwned(false, false),
                "没有 JSON 覆盖、没有方块级 tint 的面取贴图均值");
        require(!MapColors.spriteOwned(true, false), "JSON 覆盖优先于贴图");
        require(!MapColors.spriteOwned(false, true), "群系 tint 优先于贴图");

        // 1.21.1 pink_petals.png 顶面 UV [0,0,8,8] 的 alpha 加权均值 #F599CB
        float[] neutral = MapStackLayer.vertexTint(0xF5 / 255f, 0x99 / 255f, 0xCB / 255f,
                0xF5 / 255f, 0x99 / 255f, 0xCB / 255f);
        require(Math.abs(neutral[0] - 1f) < 1e-4f && Math.abs(neutral[1] - 1f) < 1e-4f
                        && Math.abs(neutral[2] - 1f) < 1e-4f,
                "记录色取贴图均值时顶点色为 1（贴图原样）: " + Arrays.toString(neutral));

        // 旧口径把 MapColor.PLANT #007C00 记成这一面的颜色 → 比值只剩绿通道（红蓝归零）
        float[] plant = MapStackLayer.vertexTint(0x00 / 255f, 0x7C / 255f, 0x00 / 255f,
                0xF5 / 255f, 0x99 / 255f, 0xCB / 255f);
        require(plant[0] < 1e-4f && plant[2] < 1e-4f && plant[1] > 0.7f,
                "MapColor.PLANT ÷ 粉花贴图 = 只剩绿通道: " + Arrays.toString(plant));
    }

    /**
     * 多部件地表覆盖物的平色规则：四片 8×8 的粉色花簇单独都够不到 {@code flatBaseIndex}
     * （每片只占四分之一格、alpha &lt; 128），但整组并集盖满一格 —— 低缩放必须按整组取色，
     * 否则「覆盖 × alpha」只有两成左右，被下面的草色吃掉。
     */
    private static void testFlatCover() {
        // 1.21.1 pink_petals.png 四个 8×8 象限的 alpha 加权均值（Pillow 实测）
        List<MapStackLayer> petals = List.of(
                petal("block/pink_petals", 0, 0, 8, 8, 0, 0, 8, 8, 0xFFF599CB),
                petal("block/pink_petals", 0, 8, 8, 16, 0, 8, 8, 16, 0xFFF7C9E6),
                petal("block/pink_petals", 8, 8, 16, 16, 8, 8, 16, 16, 0xFFF8AFDA),
                petal("block/pink_petals", 8, 0, 16, 8, 8, 0, 16, 8, 0xFFF6B1D7));
        require(MapStackLayer.flatBaseIndex(petals) == -1,
                "四片粉色花簇单独都够不到平色基底");
        require(MapStackLayer.topGroupEnd(petals) == 4, "同一方块同一高度的四片算一组");
        require(MapStackLayer.unionCoverage(petals, 4) == 256,
                "四片分占四角、并集盖满整格: " + MapStackLayer.unionCoverage(petals, 4));
        require(MapStackLayer.averageArgb(petals, 4) == 0xF6B0D8,
                "整组平均色是粉的: " + Integer.toHexString(MapStackLayer.averageArgb(petals, 4)));
        // 下面那块草地不属于这一组
        MapStackLayer grass = new MapStackLayer("minecraft:block/grass_block_top",
                0, 0, 64, 64, 0, 0, 16, 16, "minecraft:grass_block", 64, 0xFF6A9E3A, 255, 1, 0);
        List<MapStackLayer> withGround = List.of(petals.get(0), petals.get(1),
                petals.get(2), petals.get(3), grass);
        require(MapStackLayer.topGroupEnd(withGround) == 4,
                "一组只看栈顶连续的同方块同高度层: " + MapStackLayer.topGroupEnd(withGround));
        // 叠在一起的两张整格层，并集仍是一格（不按面积相加）
        MapStackLayer full = new MapStackLayer("minecraft:block/stone",
                0, 0, 64, 64, 0, 0, 16, 16, "minecraft:stone", 65, 0xFF808080, 255, 1, 0);
        require(MapStackLayer.unionCoverage(List.of(full, full), 2) == 256,
                "重叠的矩形不重复计");
        // 一满格 + 一角：平均色按投影面积加权，不按层数
        require(MapStackLayer.averageArgb(List.of(full, petals.get(0)), 2) == 0x97858F,
                "面积加权: " + Integer.toHexString(MapStackLayer.averageArgb(
                        List.of(full, petals.get(0)), 2)));
        // 单片的薄装饰不成组（铁轨、红石粉、发光地衣的顶面都是单张整格平面）
        require(MapStackLayer.topGroupEnd(List.of(full)) == 1, "单片不成组");
    }

    private static MapStackLayer petal(String sprite, int u0, int v0, int u1, int v1,
                                       int x0, int z0, int x1, int z1, int argb) {
        return new MapStackLayer(sprite, u0, v0, u1, v1, x0, z0, x1, z1,
                "minecraft:pink_petals", 64, argb, 60, 1, 0);
    }

    /** 表面栈的纯规则：相邻同形层合并计数、叠层不透明度、层数压暗、浅水透明度、旋转还原。 */
    private static void testSurfaceStackRules() {
        MapStackLayer leaf = new MapStackLayer("minecraft:block/oak_leaves",
                0, 0, 64, 64, 0, 0, 16, 16, "minecraft:oak_leaves", 80, 0xFF3A6B21, 200, 1, 0);
        MapStackLayer leafLower = new MapStackLayer("minecraft:block/oak_leaves",
                0, 0, 64, 64, 0, 0, 16, 16, "minecraft:oak_leaves", 79, 0xFF3A6B21, 200, 1, 0);
        MapStackLayer stone = new MapStackLayer("minecraft:block/stone",
                0, 0, 64, 64, 0, 0, 16, 16, "minecraft:stone", 70, 0xFF808080, 255, 1, 0);
        List<MapStackLayer> merged = MapStackLayer.merge(List.of(leaf, leafLower, stone));
        require(merged.size() == 2, "相邻同形层应合并成一层: " + merged);
        require(merged.get(0).count() == 2, "合并后计数为 2: " + merged.get(0));
        require(merged.get(0).y() == 80, "合并后保留最上面那层的 y");
        require(Math.abs(MapStackLayer.stackedAlpha(128, 1) - 128f / 255f) < 1e-6f,
                "单层不透明度就是原值");
        require(MapStackLayer.stackedAlpha(128, 3) > MapStackLayer.stackedAlpha(128, 1),
                "叠得越多越不透明");
        require(MapStackLayer.density(1) > MapStackLayer.density(4), "叠得越多越暗");
        require(Math.abs(MapStackLayer.density(1) - 1f) < 1e-6f, "单层不压暗");
        require(MapStackLayer.density(99) >= 0.72f, "压暗有下限，不把树冠压成黑块");
        require(Math.abs(FluidSurfaces.WATER.alpha(1) - 0.45f) < 1e-6f, "1 格水 0.45");
        require(FluidSurfaces.WATER.alpha(2) > FluidSurfaces.WATER.alpha(1), "越深越不透明");
        require(Math.abs(FluidSurfaces.WATER.alpha(9) - 0.80f) < 1e-6f, "深水封顶 0.80");
        // 贴图、位置都一样但旋转不同是两种朝向，不能合并计数
        MapStackLayer turnedLeaf = new MapStackLayer("minecraft:block/oak_leaves",
                0, 0, 64, 64, 0, 0, 16, 16, "minecraft:oak_leaves", 80, 0xFF3A6B21, 200, 1, 1);
        require(MapStackLayer.merge(List.of(leaf, turnedLeaf)).size() == 2,
                "旋转不同的层不能合并");
        // 旋转前矩形：把结果顺时针转 r×90° 应回到原矩形（这里用不对称矩形验证每一档）
        require(java.util.Arrays.equals(
                        MapStackLayer.unrotatedRect(0, 2, 3, 5, 9), new int[]{2, 3, 5, 9}),
                "r0 不改矩形");
        require(java.util.Arrays.equals(
                        MapStackLayer.unrotatedRect(1, 2, 3, 5, 9), new int[]{3, 11, 9, 14}),
                "r1 逆时针还原");
        require(java.util.Arrays.equals(
                        MapStackLayer.unrotatedRect(2, 2, 3, 5, 9), new int[]{11, 7, 14, 13}),
                "r2 对角翻转");
        require(java.util.Arrays.equals(
                        MapStackLayer.unrotatedRect(3, 2, 3, 5, 9), new int[]{7, 2, 13, 5}),
                "r3 顺时针还原");

        MapStackLayer glass = new MapStackLayer("minecraft:block/glass",
                0, 0, 64, 64, 0, 0, 16, 16, "minecraft:glass", 81, 0xFFC0D8E0, 65, 1, 0);
        MapStackLayer water = new MapStackLayer("minecraft:block/water_still",
                0, 0, 64, 64, 0, 0, 16, 16, FluidSurfaces.WATER.layerId(),
                70, 0xFF3F76E4, 115, 1, 0);
        require(MapStackLayer.flatBaseIndex(List.of(leaf, leafLower)) == 0,
                "树叶自己就是平色表面（低缩放不混下面）");
        require(MapStackLayer.flatBaseIndex(List.of(glass, leaf)) == 1,
                "玻璃盖在树叶上时，树叶是平色基底，只有玻璃在上面混色");
        require(MapStackLayer.flatBaseIndex(List.of(water, stone)) == 0,
                "水面是平色基底：低缩放只显示水色，不混水底");
        require(MapStackLayer.flatBaseIndex(List.of(glass)) == -1,
                "没有读起来不透明的层时用终点色");

        MapStackLayer torch = new MapStackLayer("minecraft:block/torch",
                28, 0, 36, 8, 7, 7, 9, 9, "minecraft:torch", 82, 0xFFFFC040, 255, 1, 0);
        require(MapStackLayer.flatBaseIndex(List.of(torch, stone)) == 1,
                "火把这类只占一角的顶面不当平色表面，落到下面的完整方块");
        require(MapStackLayer.flatBaseIndex(List.of(torch)) == -1,
                "整条栈只有小顶面时用终点色");
    }

    /** 顶面筛选：45° 斜面按几何法向收，不看原版烘焙给的 Direction。 */
    private static void testSurfaceUpFace() {
        example.contentpack.mapv2.client.BlockSurfacesSelfTest.run();
    }

    /** 含水方块：水层按液面高度插进方块自己的顶面之间（水面 14.2/16）。 */
    private static void testWaterloggedSplit() {
        require(MapCapture.fluidSplitIndex(14.2f, List.of(part(16f))) == 1,
                "树叶顶面（16）在水面上，水层在叶下");
        require(MapCapture.fluidSplitIndex(14.2f, List.of(part(8f))) == 0,
                "下半台阶的板面（8）在水面下，水层在台阶上");
        require(MapCapture.fluidSplitIndex(14.2f, List.of(part(16f), part(8f))) == 1,
                "台阶只有高那级（16）在水面上");
        require(MapCapture.fluidSplitIndex(14.2f, List.of(part(16f), part(15f), part(9f))) == 2,
                "栅栏是混搭：柱子(16)和上横杆(15)在水面上，下横杆(9)在水面下");
        require(MapCapture.fluidSplitIndex(14.2f, List.of()) == 0,
                "没有顶面（水草/珊瑚）时水层直接排在最上面");
    }

    /** 流体表面登记表：水/岩浆的层 ID、贴图、不透明度和深度符号。 */
    private static void testFluidSurfaces() {
        // 只测纯参数；matches/of 要拿 FluidState，离线没有 bootstrap，留给启动自检
        require("minecraft:water".equals(FluidSurfaces.WATER.layerId()), "水层 ID");
        require("minecraft:block/water_still".equals(FluidSurfaces.WATER.sprite()), "水贴图");
        require(!FluidSurfaces.WATER.opaque(), "水是半透明流体");
        require(FluidSurfaces.WATER.shallowDepth() == 3, "水最多看穿 3 格");
        require(FluidSurfaces.WATER.depthSign() == 1, "水按深度压暗");
        require("minecraft:lava".equals(FluidSurfaces.LAVA.layerId()), "岩浆层 ID");
        require("minecraft:block/lava_still".equals(FluidSurfaces.LAVA.sprite()), "岩浆贴图");
        require(FluidSurfaces.LAVA.opaque(), "岩浆不透明：一层就是终点");
        require(FluidSurfaces.LAVA.shallowDepth() == 0, "岩浆不做浅层透底");
        require(Math.abs(FluidSurfaces.LAVA.alpha(1) - 1f) < 1e-6f, "岩浆层始终满不透明");
        require(FluidSurfaces.LAVA.depthSign() == -1, "岩浆按深度提亮");
        require(FluidSurfaces.isFluidLayer("minecraft:water"), "渲染端认水层");
        require(FluidSurfaces.isFluidLayer("minecraft:lava"), "渲染端认岩浆层");
        require(!FluidSurfaces.isFluidLayer("minecraft:stone"), "普通方块不是流体层");
        require(!FluidSurfaces.isFluidLayer(null), "空 blockId 不是流体层");
        // 炼药锅的内容物靠顶面贴图反查（方块自己的流体状态是空的）
        require(FluidSurfaces.ofSprite("minecraft:block/water_still") == FluidSurfaces.WATER,
                "水锅的内容物按水面贴图认成水");
        require(FluidSurfaces.ofSprite("minecraft:block/lava_still") == FluidSurfaces.LAVA,
                "岩浆锅的内容物按岩浆面贴图认成岩浆");
        require(FluidSurfaces.ofSprite("minecraft:block/stone") == null, "石头贴图不是流体");
    }

    /** 床：头/脚分两片、朝向旋转与渲染器变换同口径。 */
    private static void testBedSurfaces() {
        example.contentpack.mapv2.client.BedSurfacesSelfTest.run();
    }

    /** 箱子/告示牌/潜影盒/潮涌核心/陶罐/生物头：合成顶面的 UV、投影、旋转档位。 */
    private static void testEntitySurfaces() {
        example.contentpack.mapv2.client.EntitySurfacesSelfTest.run();
    }

    private static example.contentpack.mapv2.client.BlockSurfaces.Part part(float height) {
        return new example.contentpack.mapv2.client.BlockSurfaces.Part(
                null, 0, 0, 64, 64, 0, 0, 16, 16, 255, height, 0, -1);
    }

    private static MapTile roundtrip(MapTile tile) {
        try {
            return MapStorage.roundtripForTest(tile);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
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
