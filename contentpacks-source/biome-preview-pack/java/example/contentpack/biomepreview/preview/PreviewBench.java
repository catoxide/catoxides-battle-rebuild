package example.contentpack.biomepreview.preview;

import net.minecraft.core.QuartPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 纯采样吞吐基准：一个网格 = (2*radius/step+1)^2 个气候采样点。
 * <p>单线程与多线程各跑一遍，采样结果只累加 checksum（防止 JIT 消除死代码）；
 * 群系直方图在计时之外单独统计。CPU 代数外推不在这里做 —— 只输出实测值，
 * 外推必须带明确假设（见 self-test 报告）。
 */
public final class PreviewBench {

    public record Report(
            int stepBlocks,
            int samples,
            long singleNanos,
            long multiNanos,
            int threads,
            int checksum,
            LinkedHashMap<String, Integer> biomeCounts
    ) {
        public double singleNanosPerSample() {
            return (double) singleNanos / Math.max(1, samples);
        }

        public double multiNanosPerSample() {
            return (double) multiNanos / Math.max(1, samples);
        }

        public double singleSamplesPerSecond() {
            return samples / (singleNanos / 1.0e9);
        }

        public double multiSamplesPerSecond() {
            return samples / (multiNanos / 1.0e9);
        }

        public double speedup() {
            return (double) singleNanos / Math.max(1, multiNanos);
        }

        /** 一个区块（16x16 格）在该 step 下的采样点数。 */
        public int samplesPerChunk() {
            int perAxis = Math.max(1, 16 / stepBlocks);
            return perAxis * perAxis;
        }

        public int samplesPerTile(int tileBlocks) {
            int perAxis = Math.max(1, tileBlocks / stepBlocks);
            return perAxis * perAxis;
        }
    }

    private PreviewBench() {
    }

    public static Report run(SurfaceBiomeSampler sampler, int centerX, int centerZ,
                             int radiusBlocks, int stepBlocks, int threads) {
        int step = Math.max(SurfaceBiomeSampler.MIN_STEP_BLOCKS, stepBlocks / 4 * 4);
        int stepQuarts = step / 4;
        int qx0 = QuartPos.fromBlock(centerX - radiusBlocks);
        int qz0 = QuartPos.fromBlock(centerZ - radiusBlocks);
        int qCount = Math.max(1, (radiusBlocks * 2) / step + 1);

        warmup(sampler, qx0, qz0, Math.min(qCount, 160), stepQuarts);

        long t0 = System.nanoTime();
        int checksum = sampleRows(sampler, qx0, qz0, qCount, stepQuarts, 0, qCount);
        long t1 = System.nanoTime();

        int workerCount = Math.max(1, threads);
        ExecutorService pool = Executors.newFixedThreadPool(workerCount, runnable -> {
            Thread thread = new Thread(runnable, "biomepreview-bench");
            thread.setDaemon(true);
            return thread;
        });
        long t2;
        long t3;
        try {
            List<Future<Integer>> futures = new ArrayList<>(workerCount);
            int warmRows = Math.min(qCount, workerCount * 8);
            for (int i = 0; i < workerCount; i++) {
                int from = (int) ((long) warmRows * i / workerCount);
                int to = (int) ((long) warmRows * (i + 1) / workerCount);
                futures.add(pool.submit(() -> sampleRows(sampler, qx0, qz0, qCount, stepQuarts, from, to)));
            }
            for (Future<Integer> future : futures) {
                checksum += get(future);
            }

            t2 = System.nanoTime();
            futures.clear();
            for (int i = 0; i < workerCount; i++) {
                int from = (int) ((long) qCount * i / workerCount);
                int to = (int) ((long) qCount * (i + 1) / workerCount);
                futures.add(pool.submit(() -> sampleRows(sampler, qx0, qz0, qCount, stepQuarts, from, to)));
            }
            for (Future<Integer> future : futures) {
                checksum += get(future);
            }
            t3 = System.nanoTime();
        } finally {
            pool.shutdownNow();
        }

        return new Report(step, qCount * qCount, t1 - t0, t3 - t2, workerCount, checksum,
                histogram(sampler, qx0, qz0, qCount, stepQuarts));
    }

    private static int get(Future<Integer> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 采样 [fromRow, toRow) 行，返回 checksum。 */
    private static int sampleRows(SurfaceBiomeSampler sampler, int qx0, int qz0,
                                  int qCount, int stepQuarts, int fromRow, int toRow) {
        int checksum = 0;
        for (int row = fromRow; row < toRow; row++) {
            int blockZ = QuartPos.toBlock(qz0 + row * stepQuarts);
            for (int col = 0; col < qCount; col++) {
                int blockX = QuartPos.toBlock(qx0 + col * stepQuarts);
                checksum += sampler.surfaceAt(blockX, blockZ).hashCode();
            }
        }
        return checksum;
    }

    private static void warmup(SurfaceBiomeSampler sampler, int qx0, int qz0, int rows, int stepQuarts) {
        int checksum = sampleRows(sampler, qx0, qz0, rows, stepQuarts, 0, rows);
        if (checksum == Integer.MIN_VALUE) {
            throw new IllegalStateException("impossible warmup checksum");
        }
    }

    private static LinkedHashMap<String, Integer> histogram(SurfaceBiomeSampler sampler, int qx0, int qz0,
                                                           int qCount, int stepQuarts) {
        Map<String, AtomicInteger> counts = new java.util.HashMap<>();
        for (int row = 0; row < qCount; row++) {
            int blockZ = QuartPos.toBlock(qz0 + row * stepQuarts);
            for (int col = 0; col < qCount; col++) {
                int blockX = QuartPos.toBlock(qx0 + col * stepQuarts);
                counts.computeIfAbsent(sampler.surfaceBiomeId(blockX, blockZ), key -> new AtomicInteger())
                        .incrementAndGet();
            }
        }
        List<Map.Entry<String, AtomicInteger>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<String, AtomicInteger>comparingByValue(
                Comparator.comparingInt(AtomicInteger::get)).reversed());
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, AtomicInteger> entry : sorted) {
            result.put(entry.getKey(), entry.getValue().get());
        }
        return result;
    }

    public static List<String> describe(Report report) {
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT,
                "[preview-bench] step=%d blocks, samples=%d, biomes=%d",
                report.stepBlocks(), report.samples(), report.biomeCounts().size()));
        lines.add(String.format(Locale.ROOT,
                "[preview-bench] single: %.1f ms total, %.0f ns/sample, %.2f Msamples/s",
                report.singleNanos() / 1.0e6, report.singleNanosPerSample(),
                report.singleSamplesPerSecond() / 1.0e6));
        lines.add(String.format(Locale.ROOT,
                "[preview-bench] single: per chunk (%d samples) = %.1f us; 1024x1024 tile (%d samples) = %.1f ms",
                report.samplesPerChunk(),
                report.singleNanosPerSample() * report.samplesPerChunk() / 1.0e3,
                report.samplesPerTile(1024),
                report.singleNanosPerSample() * report.samplesPerTile(1024) / 1.0e6));
        lines.add(String.format(Locale.ROOT,
                "[preview-bench] multi(%d threads): %.1f ms wall, %.0f ns/sample, %.2f Msamples/s, speedup %.2fx",
                report.threads(), report.multiNanos() / 1.0e6, report.multiNanosPerSample(),
                report.multiSamplesPerSecond() / 1.0e6, report.speedup()));
        lines.add(String.format(Locale.ROOT,
                "[preview-bench] multi(%d threads): per chunk = %.1f us; 1024x1024 tile = %.1f ms",
                report.threads(),
                report.multiNanosPerSample() * report.samplesPerChunk() / 1.0e3,
                report.multiNanosPerSample() * report.samplesPerTile(1024) / 1.0e6));
        StringBuilder top = new StringBuilder();
        int shown = 0;
        int total = report.samples();
        for (Map.Entry<String, Integer> entry : report.biomeCounts().entrySet()) {
            if (shown++ >= 10) {
                break;
            }
            if (top.length() > 0) {
                top.append(", ");
            }
            top.append(entry.getKey()).append('=').append(String.format(Locale.ROOT, "%.1f%%",
                    100.0 * entry.getValue() / total));
        }
        lines.add("[preview-bench] top biomes: " + top);
        return lines;
    }
}
