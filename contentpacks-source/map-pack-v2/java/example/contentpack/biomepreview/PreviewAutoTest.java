package example.contentpack.biomepreview;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import example.contentpack.biomepreview.preview.PreviewAccuracy;
import example.contentpack.biomepreview.preview.PreviewBench;
import example.contentpack.biomepreview.preview.PreviewScheduler;
import example.contentpack.biomepreview.preview.PreviewSettings;
import example.contentpack.biomepreview.preview.SurfaceBiomeSampler;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一次性自检触发器（dev 用，正常游戏不跑）。
 * <p>触发方式二选一：
 * <ul>
 *   <li>工作目录（run/server）放 {@code biomepreview-autotest.txt}，内容
 *       {@code "benchRadius accuracyChunks spreadRadius [jobRadius]"}（缺省 1024 2 4000 0）</li>
 *   <li>JVM 系统属性 {@code -Dbiomepreview.autotest="1024 2 4000 0"}</li>
 * </ul>
 * 触发文件跑完改名成 {@code .done}（不删，避免重复启动自检）。
 * <p>吞吐：step 4 / 8 / 16 各跑一遍纯采样（工作线程）。
 * 调度：jobRadius &gt; 0 时用配置档位提交一次后台任务，记录墙钟与期间最大 tick。
 * 精度：出生点 + 8 个方向 spreadRadius 处的区块，step 4 与 16 各对照一遍；
 * 强制生成区块，调度回服务端主线程。
 */
public final class PreviewAutoTest {

    private static final String TAG = "BiomePreviewAutoTest";
    private static final String TRIGGER_FILE = "biomepreview-autotest.txt";
    private static final int DEFAULT_BENCH_RADIUS = 1024;
    private static final int DEFAULT_ACCURACY_CHUNKS = 2;
    private static final int DEFAULT_SPREAD_RADIUS = 4000;
    private static final int DEFAULT_JOB_RADIUS = 0;
    private static final int[] STEPS = {4, 8, 16};

    private PreviewAutoTest() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> maybeRun(event.getServer()));
    }

    private static void maybeRun(MinecraftServer server) {
        String spec = System.getProperty("biomepreview.autotest");
        Path trigger = Paths.get(TRIGGER_FILE);
        boolean fromFile = false;
        if ((spec == null || spec.isBlank()) && Files.isRegularFile(trigger)) {
            try {
                spec = Files.readString(trigger).trim();
            } catch (IOException e) {
                log("cannot read " + TRIGGER_FILE + ": " + e);
                return;
            }
            fromFile = true;
        }
        if (spec == null || spec.isBlank()) {
            return;
        }
        if (fromFile) {
            try {
                Files.move(trigger, trigger.resolveSibling(TRIGGER_FILE + ".done"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                log("cannot rename trigger file: " + e);
            }
        }

        int[] params = parse(spec);
        Thread worker = new Thread(() -> run(server, params[0], params[1], params[2], params[3]),
                "biomepreview-autotest");
        worker.setDaemon(true);
        worker.start();
    }

    private static int[] parse(String spec) {
        String[] parts = spec.trim().split("\\s+");
        int[] values = {DEFAULT_BENCH_RADIUS, DEFAULT_ACCURACY_CHUNKS, DEFAULT_SPREAD_RADIUS,
                DEFAULT_JOB_RADIUS};
        for (int i = 0; i < Math.min(parts.length, values.length); i++) {
            try {
                values[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                log("bad autotest argument '" + parts[i] + "', keeping " + values[i]);
            }
        }
        return values;
    }

    private static void run(MinecraftServer server, int benchRadius, int accuracyChunks,
                            int spreadRadius, int jobRadius) {
        try {
            ServerLevel level = server.overworld();
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            if (!SurfaceBiomeSampler.supports(generator.getBiomeSource())) {
                log("unsupported biome source: " + generator.getBiomeSource().getClass().getName());
                return;
            }
            SurfaceBiomeSampler sampler = new SurfaceBiomeSampler(
                    generator.getBiomeSource(), level.getChunkSource().randomState());
            int threads = Math.min(16, Runtime.getRuntime().availableProcessors());
            log(String.format(Locale.ROOT,
                    "start seed=%d benchRadius=%d accuracyChunks=%d spreadRadius=%d jobRadius=%d cpu=%d threads",
                    level.getSeed(), benchRadius, accuracyChunks, spreadRadius, jobRadius,
                    Runtime.getRuntime().availableProcessors()));

            for (int step : STEPS) {
                PreviewBench.describe(PreviewBench.run(sampler, 0, 0, benchRadius, step, threads))
                        .forEach(PreviewAutoTest::log);
            }

            if (jobRadius > 0) {
                runJobTest(server, sampler, jobRadius);
            }

            List<ChunkPos> centers = centers(level, spreadRadius);
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<List<String>> result = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            server.execute(() -> {
                try {
                    result.set(runAccuracy(level, sampler, centers, accuracyChunks));
                } catch (Throwable throwable) {
                    failure.set(throwable);
                } finally {
                    latch.countDown();
                }
            });
            if (!latch.await(600, TimeUnit.SECONDS)) {
                log("accuracy timed out after 600s");
                return;
            }
            if (failure.get() != null) {
                log("accuracy failed: " + failure.get());
                return;
            }
            result.get().forEach(PreviewAutoTest::log);
            log("done");
        } catch (Throwable throwable) {
            log("failed: " + throwable);
        }
    }

    private static void runJobTest(MinecraftServer server, SurfaceBiomeSampler sampler, int radius) {
        int step = PreviewSettings.stepBlocks();
        if (step <= 0) {
            log("preview disabled in config; exercising the scheduler with step=4");
            step = SurfaceBiomeSampler.MIN_STEP_BLOCKS;
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<PreviewScheduler.Result> result = new AtomicReference<>();
        int finalStep = step;
        server.execute(() -> PreviewScheduler.submit(sampler, 0, 0, radius, finalStep, done -> {
            result.set(done);
            latch.countDown();
        }));
        try {
            if (!latch.await(300, TimeUnit.SECONDS)) {
                log("scheduler job timed out after 300s");
                PreviewScheduler.cancelAll();
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        PreviewScheduler.Result done = result.get();
        log("[preview-scheduler] " + (done == null ? "no result" : done.summary()));
    }

    private static List<ChunkPos> centers(ServerLevel level, int spreadRadius) {
        List<ChunkPos> centers = new ArrayList<>();
        ChunkPos spawn = new ChunkPos(level.getSharedSpawnPos());
        centers.add(spawn);
        if (spreadRadius > 0) {
            for (int i = 0; i < 8; i++) {
                double angle = Math.PI * i / 4.0;
                int x = spawn.getMinBlockX() + (int) Math.round(Math.cos(angle) * spreadRadius);
                int z = spawn.getMinBlockZ() + (int) Math.round(Math.sin(angle) * spreadRadius);
                centers.add(new ChunkPos(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)));
            }
        }
        return centers;
    }

    private static List<String> runAccuracy(ServerLevel level, SurfaceBiomeSampler sampler,
                                           List<ChunkPos> centers, int chunkRadius) {
        List<String> lines = new ArrayList<>();
        for (int step : new int[]{SurfaceBiomeSampler.MIN_STEP_BLOCKS, 16}) {
            int columns = 0;
            int matches = 0;
            int chunks = 0;
            int underground = 0;
            long nanos = 0;
            Map<String, Integer> pairs = new HashMap<>();
            for (ChunkPos center : centers) {
                PreviewAccuracy.Report report = PreviewAccuracy.run(
                        level, sampler, center.x, center.z, chunkRadius, step);
                columns += report.columns();
                matches += report.matches();
                chunks += report.chunks();
                underground += report.undergroundCells();
                nanos += report.nanos();
                report.mismatches().forEach(entry -> pairs.merge(entry.getKey(), entry.getValue(), Integer::sum));
                lines.add(String.format(Locale.ROOT,
                        "[preview-accuracy] step=%d center=(%d,%d) chunks=%d columns=%d match=%d (%.2f%%) underground=%d",
                        step, center.x, center.z, report.chunks(), report.columns(), report.matches(),
                        100.0 * report.matches() / Math.max(1, report.columns()), report.undergroundCells()));
            }
            lines.add(String.format(Locale.ROOT,
                    "[preview-accuracy] TOTAL step=%d centers=%d chunks=%d columns=%d match=%d (%.3f%%) mismatch=%d underground-biome cells=%d, gen+compare %.1f ms",
                    step, centers.size(), chunks, columns, matches,
                    100.0 * matches / Math.max(1, columns), columns - matches, underground, nanos / 1.0e6));
            List<Map.Entry<String, Integer>> worst = new ArrayList<>(pairs.entrySet());
            worst.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
            for (int i = 0; i < Math.min(8, worst.size()); i++) {
                Map.Entry<String, Integer> entry = worst.get(i);
                lines.add(String.format(Locale.ROOT, "[preview-accuracy] step=%d mismatch %s x%d",
                        step, entry.getKey(), entry.getValue()));
            }
            if (worst.isEmpty()) {
                lines.add("[preview-accuracy] step=" + step + " no mismatches");
            }
        }
        return lines;
    }

    private static void log(String line) {
        LogManager.serverInfo(TAG, line);
        System.out.println("[" + TAG + "] " + line);
    }
}
