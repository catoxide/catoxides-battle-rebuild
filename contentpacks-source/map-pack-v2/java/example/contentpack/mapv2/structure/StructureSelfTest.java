package example.contentpack.mapv2.structure;

import com.mojang.logging.LogUtils;
import example.contentpack.mapv2.network.StructureQueryPayload;
import example.contentpack.mapv2.network.StructureResultPayload;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import org.slf4j.Logger;

import java.util.List;

/**
 * 结构图层的纯逻辑自检，启动时跑一次。
 *
 * <p>候选点由原版的 {@code getPotentialStructureChunk} 计算，这里只验证我们自己的部分：
 * 区域/单元格归属在负坐标下不丢格、矩形判定闭合、两个 payload 编解码往返。
 * 任何一项失败都会让 {@code scripts/validate-game.ps1} 抓到 {@code FAILURES}。
 */
public final class StructureSelfTest {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation OVERWORLD =
            ResourceLocation.parse("minecraft:overworld");

    private StructureSelfTest() {
    }

    public static void run() {
        int passed = 0;
        int failed = 0;
        if (run("structure-regions", StructureSelfTest::testRegions)) {
            passed++;
        } else {
            failed++;
        }
        if (run("structure-cells", StructureSelfTest::testCells)) {
            passed++;
        } else {
            failed++;
        }
        if (run("structure-rect", StructureSelfTest::testRect)) {
            passed++;
        } else {
            failed++;
        }
        if (run("structure-sample-heights", StructureSelfTest::testSampleHeights)) {
            passed++;
        } else {
            failed++;
        }
        if (run("structure-candidates", StructureSelfTest::testCandidates)) {
            passed++;
        } else {
            failed++;
        }
        if (run("structure-variant-order", StructureSelfTest::testVariantOrder)) {
            passed++;
        } else {
            failed++;
        }
        if (run("structure-query-codec", StructureSelfTest::testQueryCodec)) {
            passed++;
        } else {
            failed++;
        }
        if (run("structure-result-codec", StructureSelfTest::testResultCodec)) {
            passed++;
        } else {
            failed++;
        }

        LOGGER.info("[MapStructureTest] passed={} failed={}", passed, failed);
        if (failed > 0) {
            LOGGER.error("[MapStructureTest] FAILURES={}", failed);
        }
    }

    private static boolean run(String name, Runnable test) {
        try {
            test.run();
            return true;
        } catch (Throwable error) {
            LOGGER.error("[MapStructureTest] FAIL {}: {}", name, error.toString());
            return false;
        }
    }

    /**
     * 区域索引必须真的「含住」区块：{@code region * spacing <= chunk < (region + 1) * spacing}。
     * 负区块是整数除法最容易错的地方（-1 / 32 == 0，但真正的区域是 -1）。
     */
    private static void testRegions() {
        int[] spacings = {1, 2, 8, 16, 24, 32, 34, 80};
        int[] chunks = {-1025, -65, -64, -33, -32, -31, -1, 0, 1, 31, 32, 33, 1023, 1024};
        for (int spacing : spacings) {
            for (int chunk : chunks) {
                int region = StructureScan.regionIndex(chunk, spacing);
                require(region * spacing <= chunk,
                        "region starts after chunk: spacing=" + spacing + " chunk=" + chunk);
                require(chunk < (region + 1) * spacing,
                        "region ends before chunk: spacing=" + spacing + " chunk=" + chunk);
            }
        }
        for (int spacing : spacings) {
            int minRegion = StructureScan.regionIndex(-100, spacing);
            int maxRegion = StructureScan.regionIndex(100, spacing);
            for (int chunk = -100; chunk <= 100; chunk++) {
                int region = StructureScan.regionIndex(chunk, spacing);
                require(region >= minRegion && region <= maxRegion,
                        "region outside range: spacing=" + spacing + " chunk=" + chunk);
            }
        }
    }

    /** 单元格必须按 CELL_CHUNKS 对齐，且负坐标同样连续。 */
    private static void testCells() {
        for (int chunk = -300; chunk <= 300; chunk++) {
            int cell = StructureScan.cellIndex(chunk);
            int origin = StructureScan.cellOrigin(cell);
            require(origin <= chunk, "cell origin after chunk: cell=" + cell + " chunk=" + chunk);
            require(chunk < origin + StructureScan.CELL_CHUNKS,
                    "cell ends before chunk: cell=" + cell + " chunk=" + chunk);
            require(StructureScan.cellIndex(origin) == cell, "cell origin maps back: cell=" + cell);
        }
        require(StructureScan.cellOrigin(0) == 0, "cell 0 starts at chunk 0");
        require(StructureScan.cellOrigin(-1) == -StructureScan.CELL_CHUNKS,
                "cell -1 starts one cell below zero");
    }

    /**
     * 候选块枚举必须真的落回「枚举它的那个区域」。
     *
     * <p>{@code getPotentialStructureChunk} 的参数是<b>区块坐标</b>，方法内部自己再
     * {@code floorDiv} 到区域；传区域下标会被二次取整，候选全体挤到原点附近
     * （实测：只有原点单元有结果，相邻单元 inRect=0）。
     */
    private static void testCandidates() {
        long seed = -2003498481722412566L;
        int[] spacings = {8, 16, 24, 32, 34, 80};
        for (int spacing : spacings) {
            RandomSpreadStructurePlacement placement = new RandomSpreadStructurePlacement(
                    spacing, Math.max(1, spacing / 4), RandomSpreadType.LINEAR, 987654321);
            for (int cellX = -3; cellX <= 3; cellX++) {
                for (int cellZ = -3; cellZ <= 3; cellZ++) {
                    int minChunkX = StructureScan.cellOrigin(cellX);
                    int minChunkZ = StructureScan.cellOrigin(cellZ);
                    int maxChunkX = minChunkX + StructureScan.CELL_CHUNKS - 1;
                    int maxChunkZ = minChunkZ + StructureScan.CELL_CHUNKS - 1;
                    int hits = 0;
                    for (int regionX = StructureScan.regionIndex(minChunkX, spacing);
                         regionX <= StructureScan.regionIndex(maxChunkX, spacing); regionX++) {
                        for (int regionZ = StructureScan.regionIndex(minChunkZ, spacing);
                             regionZ <= StructureScan.regionIndex(maxChunkZ, spacing); regionZ++) {
                            ChunkPos pos = placement.getPotentialStructureChunk(seed,
                                    regionX * spacing, regionZ * spacing);
                            require(pos.x >= regionX * spacing
                                            && pos.x < (regionX + 1) * spacing,
                                    "candidate x outside its region: spacing=" + spacing
                                            + " region=" + regionX + " chunk=" + pos.x);
                            require(pos.z >= regionZ * spacing
                                            && pos.z < (regionZ + 1) * spacing,
                                    "candidate z outside its region: spacing=" + spacing
                                            + " region=" + regionZ + " chunk=" + pos.z);
                            if (StructureScan.inRect(pos.x, pos.z, minChunkX, minChunkZ,
                                    maxChunkX, maxChunkZ)) {
                                hits++;
                            }
                        }
                    }
                    // spacing 整除单元边长时，区域恰好铺满单元，候选不可能全落在外面
                    if (StructureScan.CELL_CHUNKS % spacing == 0) {
                        int perAxis = StructureScan.CELL_CHUNKS / spacing;
                        require(hits == perAxis * perAxis,
                                "cell " + cellX + "/" + cellZ + " spacing=" + spacing
                                        + " expected " + perAxis * perAxis + " candidates, got "
                                        + hits);
                    }
                }
            }
        }
    }

    private static void testRect() {
        require(StructureScan.inRect(0, 0, 0, 0, 63, 63), "origin is inside");
        require(StructureScan.inRect(63, 63, 0, 0, 63, 63), "max edge is inclusive");
        require(!StructureScan.inRect(64, 0, 0, 0, 63, 63), "just past max edge is outside");
        require(!StructureScan.inRect(-1, 0, 0, 0, 63, 63), "just before min edge is outside");
        require(StructureScan.inRect(-64, -64, -64, -64, -1, -1), "negative rect covers itself");
        require(!StructureScan.inRect(-65, -64, -64, -64, -1, -1),
                "outside negative rect is rejected");
    }

    /** 采样高度必须落在维度高度范围内、不重复，且包含海平面（海平面本身越界时除外）。 */
    private static void testSampleHeights() {
        assertSampleHeights(-64, 320, 63, 63);
        assertSampleHeights(0, 256, 32, 32);
        assertSampleHeights(0, 16, 64, 15);
        assertSampleHeights(-64, 320, -100, -64);
    }

    private static void assertSampleHeights(int minY, int maxY, int seaLevel, int expectedSea) {
        int[] heights = StructureScan.sampleHeights(minY, maxY, seaLevel);
        require(heights.length >= 1 && heights.length <= 3,
                "sample height count: " + heights.length);
        for (int i = 0; i < heights.length; i++) {
            require(heights[i] >= minY && heights[i] < maxY,
                    "sample height out of range: " + heights[i]);
            for (int j = i + 1; j < heights.length; j++) {
                require(heights[i] != heights[j],
                        "duplicate sample height: " + heights[i]);
            }
        }
        if (expectedSea >= minY && expectedSea < maxY) {
            boolean found = false;
            for (int height : heights) {
                found |= height == expectedSea;
            }
            require(found, "sea level missing from samples: " + expectedSea);
        }
    }

    /**
     * 变体顺序必须是排列、同输入同结果，且两个等权变体在不同落点上都会出现——
     * 这保证下界复合结构能按原版随机分出要塞和堡垒遗迹，而不是恒取第一个。
     */
    private static void testVariantOrder() {
        long seed = 123456789L;
        int[] weights = {1, 1};
        boolean sawFirst = false;
        boolean sawSecond = false;
        for (int x = -40; x < 40; x++) {
            for (int z = -40; z < 40; z += 7) {
                int[] order = StructureScan.variantOrder(seed, x, z, weights);
                require(order.length == weights.length, "order length");
                boolean[] seen = new boolean[weights.length];
                for (int index : order) {
                    require(index >= 0 && index < weights.length, "index range: " + index);
                    require(!seen[index], "duplicate variant index: " + index);
                    seen[index] = true;
                }
                int[] again = StructureScan.variantOrder(seed, x, z, weights);
                for (int i = 0; i < order.length; i++) {
                    require(order[i] == again[i], "order must be deterministic");
                }
                sawFirst |= order[0] == 0;
                sawSecond |= order[0] == 1;
            }
        }
        require(sawFirst && sawSecond, "both variants must lead on some chunk");
        int[] single = StructureScan.variantOrder(seed, 3, -5, new int[]{7});
        require(single.length == 1 && single[0] == 0, "single variant stays first");
    }

    private static void testQueryCodec() {
        StructureQueryPayload original = new StructureQueryPayload(OVERWORLD, -3, 7);
        ByteBuf buffer = Unpooled.buffer();
        try {
            StructureQueryPayload.CODEC.encode(buffer, original);
            StructureQueryPayload decoded = StructureQueryPayload.CODEC.decode(buffer);
            require(decoded.dimension().equals(original.dimension()), "query dimension");
            require(decoded.cellX() == original.cellX(), "query cellX");
            require(decoded.cellZ() == original.cellZ(), "query cellZ");
        } finally {
            buffer.release();
        }
    }

    private static void testResultCodec() {
        List<StructureEntry> entries = List.of(
                new StructureEntry("minecraft:villages", "minecraft:plains_village", -117, 45,
                        StructureEntry.TIER_CANDIDATE),
                new StructureEntry("minecraft:ancient_cities", "minecraft:ancient_city",
                        2000, -2001, StructureEntry.TIER_CONFIRMED));
        StructureResultPayload original = new StructureResultPayload(
                OVERWORLD, -2, 1, entries, true);
        ByteBuf buffer = Unpooled.buffer();
        try {
            StructureResultPayload.CODEC.encode(buffer, original);
            StructureResultPayload decoded = StructureResultPayload.CODEC.decode(buffer);
            require(decoded.dimension().equals(original.dimension()), "result dimension");
            require(decoded.cellX() == original.cellX(), "result cellX");
            require(decoded.cellZ() == original.cellZ(), "result cellZ");
            require(decoded.truncated(), "result truncated");
            require(decoded.entries().equals(entries), "result entries roundtrip");
            require(!decoded.entries().get(0).confirmed(), "candidate tier stays candidate");
            require(decoded.entries().get(1).confirmed(), "confirmed tier roundtrip");
        } finally {
            buffer.release();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
