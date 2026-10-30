package example.contentpack.map;

import com.mojang.logging.LogUtils;
import example.contentpack.map.client.TileLodTextures;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 采集范围/顺序 + 瓦片文件名的纯逻辑自检，启动时跑一次。
 *
 * <p>失败用 ERROR，`scripts/validate-game.ps1` 会当成 FAIL 抓出来。
 */
public final class MapSelfTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private MapSelfTest() {
    }

    public static void run() {
        int passed = 0;
        int failed = 0;
        if (run("capture-radius", MapSelfTest::testCaptureRadius)) {
            passed++;
        } else {
            failed++;
        }
        if (run("center-out-coverage", MapSelfTest::testCenterOutCoverage)) {
            passed++;
        } else {
            failed++;
        }
        if (run("center-out-stop", MapSelfTest::testCenterOutStop)) {
            passed++;
        } else {
            failed++;
        }
        if (run("chunk-tile-alignment", MapSelfTest::testChunkTileAlignment)) {
            passed++;
        } else {
            failed++;
        }
        if (run("tile-file-name", MapSelfTest::testTileFileName)) {
            passed++;
        } else {
            failed++;
        }
        if (run("tile-change-detection", MapSelfTest::testTileChangeDetection)) {
            passed++;
        } else {
            failed++;
        }
        if (run("tile-merge", MapSelfTest::testTileMerge)) {
            passed++;
        } else {
            failed++;
        }
        if (run("legacy-offset", MapSelfTest::testLegacyOffset)) {
            passed++;
        } else {
            failed++;
        }
        if (run("zoom-sharpen", MapSelfTest::testZoomSharpen)) {
            passed++;
        } else {
            failed++;
        }
        if (run("layer-order", MapSelfTest::testLayerOrder)) {
            passed++;
        } else {
            failed++;
        }
        if (run("view-lattice", MapSelfTest::testViewLattice)) {
            passed++;
        } else {
            failed++;
        }

        LOGGER.info("[MapCaptureTest] passed={} failed={}", passed, failed);
        if (failed > 0) {
            LOGGER.error("[MapCaptureTest] FAILURES={}", failed);
        }
    }

    private static boolean run(String name, Runnable test) {
        try {
            test.run();
            return true;
        } catch (Throwable error) {
            LOGGER.error("[MapCaptureTest] FAIL {}: {}", name, error.toString());
            return false;
        }
    }

    private static void testCaptureRadius() {
        require(MapScan.captureRadius(32) == 32, "radius follows render distance");
        require(MapScan.captureRadius(12) == 12, "radius follows render distance (12)");
        require(MapScan.captureRadius(2) == 2, "tiny render distance floors at 2");
        require(MapScan.captureRadius(0) == MapScan.MIN_RADIUS, "below minimum");
        require(MapScan.captureRadius(-3) == MapScan.MIN_RADIUS, "negative render distance floors");
    }

    private static void testCenterOutCoverage() {
        for (int radius : new int[]{0, 1, 3, 12}) {
            int expected = (radius * 2 + 1) * (radius * 2 + 1);
            Set<Long> seen = new HashSet<>();
            int[] count = {0};
            int[] firstX = {Integer.MIN_VALUE};
            int[] firstZ = {Integer.MIN_VALUE};
            int[] lastRing = {-1};
            MapScan.centerOut(radius, (dx, dz) -> {
                int ring = Math.max(Math.abs(dx), Math.abs(dz));
                require(ring <= radius, "offset outside radius: " + dx + "," + dz);
                require(ring >= lastRing[0], "rings not monotonic at " + dx + "," + dz);
                lastRing[0] = ring;
                if (count[0] == 0) {
                    firstX[0] = dx;
                    firstZ[0] = dz;
                }
                require(seen.add(pack(dx, dz)), "duplicate offset: " + dx + "," + dz);
                count[0]++;
                return true;
            });
            require(count[0] == expected, "coverage radius=" + radius
                    + " expected=" + expected + " actual=" + count[0]);
            require(firstX[0] == 0 && firstZ[0] == 0, "center must be visited first");
        }
    }

    private static void testCenterOutStop() {
        int[] visits = {0};
        MapScan.centerOut(8, (dx, dz) -> ++visits[0] < 5);
        require(visits[0] == 5, "visitor returning false stops immediately: " + visits[0]);
    }

    /** 一块 16×16 区块必须整体落在一块 512×512 瓦片里（采集按块取一次瓦片句柄的前提）。 */
    private static void testChunkTileAlignment() {
        for (int chunk = -20; chunk <= 20; chunk++) {
            int base = chunk * 16;
            int tile = MapTile.tileCoord(base);
            require(MapTile.tileCoord(base + 15) == tile, "chunk straddles tile: " + chunk);
            for (int offset = 0; offset < 16; offset++) {
                int local = MapTile.localCoord(base + offset);
                require(local >= 0 && local < MapTile.SIZE, "local coord out of range: " + local);
                require(local == MapTile.localCoord(base) + offset, "local coords not contiguous");
            }
        }
    }

    private static void testTileFileName() {
        int[][] samples = {{0, 0}, {-5, 300}, {123, -456}, {Integer.MIN_VALUE + 1, -1}};
        for (int[] sample : samples) {
            String name = MapStorage.tileFileName(sample[0], sample[1]);
            long packed = MapStorage.parseTileFileName(name);
            require(packed != Long.MIN_VALUE, "parse failed: " + name);
            require((int) (packed >> 32) == sample[0], "x roundtrip: " + name);
            require((int) packed == sample[1], "z roundtrip: " + name);
        }
        for (String bad : new String[]{"r.1.mpt", "r..2.mpt", "r.a.b.mpt", "r.1.2.txt", "x.1.2.mpt"}) {
            require(MapStorage.parseTileFileName(bad) == Long.MIN_VALUE, "accepted bad name: " + bad);
        }
    }

    /** 重复采集同一块地形不能算「变化」，否则重进世界会把所有瓦片重写一遍。 */
    private static void testTileChangeDetection() {
        MapTile tile = new MapTile();
        require(tile.set(0, 0, 0xFF00FF00, 64, 0, "minecraft:grass_block"),
                "first write must count as change");
        require(!tile.set(0, 0, 0xFF00FF00, 64, 0, "minecraft:grass_block"),
                "identical write must not count as change");
        require(tile.set(0, 0, 0xFF112233, 64, 0, "minecraft:grass_block"),
                "color change must count");
        require(tile.set(0, 0, 0xFF112233, 65, 0, "minecraft:grass_block"),
                "height change must count");
        require(tile.set(0, 0, 0xFF112233, 65, 3, "minecraft:grass_block"),
                "fluid change must count");
        require(tile.set(0, 0, 0xFF112233, 65, 3, "minecraft:stone"),
                "block change must count");
        require(tile.isKnown(0, 0), "written pixel must be known");
        require(!tile.isKnown(1, 0), "untouched pixel must stay unknown");
        require(tile.knownCount() == 1, "known count tracks first writes: " + tile.knownCount());
    }

    /** 磁盘旧像素只补洞，不能覆盖本次采集的像素（重进世界不丢已采内容的前提）。 */
    private static void testTileMerge() {
        MapTile fresh = new MapTile();
        fresh.set(1, 1, 0xFF111111, 70, 0, "minecraft:stone");
        MapTile disk = new MapTile();
        disk.set(1, 1, 0xFF222222, 60, 2, "minecraft:dirt");
        disk.set(2, 2, 0xFF333333, 65, 0, "minecraft:grass_block");

        int filled = fresh.fillUnknownFrom(disk);
        require(filled == 1, "only unknown pixels merge: " + filled);
        require(fresh.knownCount() == 2, "known count after merge: " + fresh.knownCount());
        require(fresh.colorAt(1, 1) == 0xFF111111, "newer pixel must win");
        require(fresh.heightAt(1, 1) == 70, "newer height must win");
        require(fresh.colorAt(2, 2) == 0xFF333333, "disk pixel must fill the hole");
        require("minecraft:grass_block".equals(fresh.blockAt(2, 2)), "block id must merge");
        require(fresh.fillUnknownFrom(disk) == 0, "second merge is a no-op");
    }

    /** 旧 128 文件 → 512 分区：瓦片坐标与像素偏移都必须落在同一条方块坐标上（负数也不许偏）。 */
    private static void testLegacyOffset() {
        for (int legacy = -9; legacy <= 9; legacy++) {
            int tile = MapStorage.legacyTargetTile(legacy);
            int offset = MapStorage.legacyOffset(legacy);
            require(offset >= 0 && offset < MapTile.SIZE, "offset out of tile: " + offset);
            require((long) tile * MapTile.SIZE + offset == (long) legacy * 128,
                    "legacy coord " + legacy + " maps to " + tile + "+" + offset);
        }
        require(MapStorage.legacyTargetTile(-1) == -1, "negative legacy tile floors");
        require(MapStorage.legacyOffset(-1) == 384, "negative legacy offset wraps to the last quadrant");
        require(MapStorage.legacyTargetTile(4) == 1, "legacy tile 4 starts the second 512 tile");
        require(MapStorage.legacyOffset(5) == 128, "legacy tile 5 sits in the second quadrant");
    }

    /** 锐化只能增强已有对比：平坦瓦片不变、边缘两侧拉开、未采集格保持未采集。 */
    private static void testZoomSharpen() {
        int size = 5;
        int[] flat = new int[size * size];
        java.util.Arrays.fill(flat, 0x404040);
        require(java.util.Arrays.equals(flat, TileLodTextures.unsharp(flat, size, 100)),
                "uniform tile must be unchanged by sharpening");

        int[] edge = new int[size * size];
        java.util.Arrays.fill(edge, 0x404040);
        for (int z = 0; z < size; z++) {
            edge[z * size + 2] = 0xC0C0C0;
        }
        int[] out = TileLodTextures.unsharp(edge, size, 100);
        int center = (out[2 * size + 2] >> 16) & 0xFF;
        int dark = (out[2 * size + 1] >> 16) & 0xFF;
        require(center > 0xC0, "bright side must gain contrast: " + center);
        require(dark < 0x40, "dark side must lose brightness: " + dark);

        int[] hole = edge.clone();
        hole[0] = -1;
        int[] holeOut = TileLodTextures.unsharp(hole, size, 100);
        require(holeOut[0] == -1, "unknown cell must stay unknown");
        require(TileLodTextures.unsharp(edge, size, 0)[2 * size + 2] == 0xC0C0C0,
                "strength 0 must disable sharpening");
    }

    /** 层列表：surface 永远第一、切片按 y 升序、群系层只跟 surface、越界/非法层名要滤掉。 */
    private static void testLayerOrder() {
        List<String> layers = new ArrayList<>(List.of("y16", MapLayer.TOP, "y-64", "y-48"));
        layers.sort(MapLayer.ORDER);
        require(MapLayer.TOP.equals(layers.get(0)), "surface must be first: " + layers);
        require("y-64".equals(layers.get(1)) && "y-48".equals(layers.get(2))
                && "y16".equals(layers.get(3)), "sections must ascend by y: " + layers);
        require(MapLayer.showsBiome(MapLayer.TOP), "surface shows the biome layer");
        require(!MapLayer.showsBiome("y-64"), "slices must hide the biome layer");
        require(MapLayer.inRange("y-64", -64, 320), "in-range slice accepted");
        require(!MapLayer.inRange("y320", -64, 320), "slice at max build height rejected");
        require(!MapLayer.inRange("yabc", -64, 320), "garbage section name rejected");
    }

    /**
     * 放大时视图原点吸附到像素格点：瓦片图像、方块边缘、玩家标记必须落在同一套整像素上。
     *
     * <p>没吸附时原点带小数，瓦片和标记各自 {@code floor}，标记相对方块网格最多偏 1px ——
     * 在 2~8 px/方块时就是半格到八分之一格，看起来像「玩家标记漂了」。这里的断言把它钉死成 0：
     * 瓦片起点必须正好落在整数像素，标记的格内偏移必须等于真实小数位置。
     */
    private static void testViewLattice() {
        double[] zooms = {1.0 / 32, 1.0 / 16, 1.0 / 8, 1.0 / 4, 1.0 / 2, 1, 2, 4, 8, 16, 32};
        double[] centers = {0.0, 100.37, -57.8125, 1024.999, 3_000_000.13};
        int[] views = {487, 488};
        int[] blocks = {-1025, -512, -1, 0, 1, 511, 512, 1023};
        double[] markers = {100.31, 100.62, 100.87, -0.4, -1024.75};
        for (double bpp : zooms) {
            for (double center : centers) {
                for (int view : views) {
                    double origin = MapProjection.origin(center, view, bpp);
                    if (!MapProjection.onLattice(bpp)) {
                        continue;
                    }
                    for (int block : blocks) {
                        double exact = (block - origin) / bpp;
                        require(Math.abs(exact - Math.rint(exact)) < 1e-6,
                                "block edge off the pixel grid: bpp=" + bpp + " block=" + block);
                        require(MapProjection.pixel(block, origin, bpp) == (int) Math.rint(exact),
                                "block edge pixel mismatch: bpp=" + bpp + " block=" + block);
                    }
                    for (double marker : markers) {
                        double edge = Math.floor(marker);
                        // 标记相对它所在方块边缘的像素偏移，必须等于真实小数位置的 floor
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

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
