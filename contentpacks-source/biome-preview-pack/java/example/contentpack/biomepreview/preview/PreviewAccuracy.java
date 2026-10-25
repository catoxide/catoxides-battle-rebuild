package example.contentpack.biomepreview.preview;

import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 精度对照：depth=0 预测 vs 已生成区块里真实记录的群系。
 * <p>对照点取「该列地表格」：高度用 {@link Heightmap.Types#OCEAN_FLOOR}（水位以下取水底），
 * 群系用 {@link LevelChunk#getNoiseBiome}（生成期 fillBiomesFromNoise 写下的值），
 * 所以这是游戏本身的结果，不是二次推算。<b>会强制生成区块</b>，只能在服务端主线程调用。
 */
public final class PreviewAccuracy {

    private static final Set<String> UNDERGROUND_BIOMES = Set.of(
            "minecraft:dripstone_caves", "minecraft:lush_caves", "minecraft:deep_dark");

    public record Report(
            int stepBlocks,
            int chunks,
            int columns,
            int matches,
            int undergroundCells,
            long nanos,
            int minSurfaceY,
            int maxSurfaceY,
            List<Map.Entry<String, Integer>> mismatches,
            String firstMismatch
    ) {
    }

    private PreviewAccuracy() {
    }

    public static Report run(ServerLevel level, SurfaceBiomeSampler sampler,
                             int centerChunkX, int centerChunkZ, int chunkRadius, int stepBlocks) {
        int step = Math.max(SurfaceBiomeSampler.MIN_STEP_BLOCKS, stepBlocks / 4 * 4);
        int columns = 0;
        int matches = 0;
        int undergroundCells = 0;
        int chunks = 0;
        int minSurface = Integer.MAX_VALUE;
        int maxSurface = Integer.MIN_VALUE;
        Map<String, Integer> mismatchPairs = new HashMap<>();
        String firstMismatch = "-";

        long start = System.nanoTime();
        for (int cz = centerChunkZ - chunkRadius; cz <= centerChunkZ + chunkRadius; cz++) {
            for (int cx = centerChunkX - chunkRadius; cx <= centerChunkX + chunkRadius; cx++) {
                LevelChunk chunk = level.getChunk(cx, cz);
                chunks++;
                for (int dz = 0; dz < 16; dz += step) {
                    int z = chunk.getPos().getMinBlockZ() + dz;
                    for (int dx = 0; dx < 16; dx += step) {
                        int x = chunk.getPos().getMinBlockX() + dx;
                        int surfaceY = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
                        if (surfaceY <= level.getMinBuildHeight()) {
                            continue;
                        }
                        columns++;
                        minSurface = Math.min(minSurface, surfaceY);
                        maxSurface = Math.max(maxSurface, surfaceY);

                        String predicted = sampler.surfaceBiomeId(x, z);
                        String actual = SurfaceBiomeSampler.idOf(chunk.getNoiseBiome(
                                QuartPos.fromBlock(x), QuartPos.fromBlock(surfaceY - 1), QuartPos.fromBlock(z)));
                        if (UNDERGROUND_BIOMES.contains(actual)) {
                            undergroundCells++;
                        }
                        if (predicted.equals(actual)) {
                            matches++;
                        } else {
                            mismatchPairs.merge(predicted + " -> " + actual, 1, Integer::sum);
                            if (firstMismatch.equals("-")) {
                                firstMismatch = String.format(Locale.ROOT, "%s -> %s at (%d,%d,%d)",
                                        predicted, actual, x, surfaceY - 1, z);
                            }
                        }
                    }
                }
            }
        }
        long elapsed = System.nanoTime() - start;

        List<Map.Entry<String, Integer>> worst = new ArrayList<>(mismatchPairs.entrySet());
        worst.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        return new Report(step, chunks, columns, matches, undergroundCells, elapsed,
                minSurface, maxSurface, worst, firstMismatch);
    }

    public static List<String> describe(Report report) {
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT,
                "[preview-accuracy] step=%d blocks, chunks=%d, columns=%d, match=%d (%.2f%%), mismatch=%d, underground-biome cells=%d",
                report.stepBlocks(), report.chunks(), report.columns(), report.matches(),
                100.0 * report.matches() / Math.max(1, report.columns()),
                report.columns() - report.matches(), report.undergroundCells()));
        lines.add(String.format(Locale.ROOT,
                "[preview-accuracy] surface y range [%d, %d], elapsed %.1f ms (%.2f ms/chunk, includes chunk generation)",
                report.minSurfaceY(), report.maxSurfaceY(),
                report.nanos() / 1.0e6,
                report.nanos() / 1.0e6 / Math.max(1, report.chunks())));
        lines.add("[preview-accuracy] first mismatch: " + report.firstMismatch());
        if (report.mismatches().isEmpty()) {
            lines.add("[preview-accuracy] no mismatches");
        } else {
            int shown = 0;
            for (Map.Entry<String, Integer> entry : report.mismatches()) {
                if (shown >= 10) {
                    lines.add("[preview-accuracy] ... " + (report.mismatches().size() - shown) + " more pairs");
                    break;
                }
                lines.add(String.format(Locale.ROOT, "[preview-accuracy]   %s x%d", entry.getKey(), entry.getValue()));
                shown++;
            }
        }
        return lines;
    }
}
