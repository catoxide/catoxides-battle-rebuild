package example.contentpack.biomepreview.preview;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 群系预览的后台调度器：采样全部在低优先级 worker 上跑，服务端主线程只做两件事——
 * 记录上一 tick 的平均耗时（用于让路）、每 tick 最多派发 {@value #MAIN_QUEUE_BUDGET} 个完成回调。
 *
 * <p>让路策略（不追求"绝对零影响"，而是"tick 慢时自动退让"）：
 * <ul>
 *   <li>每攒够 4 ms 的采样工作量就休眠等长的时间 → 约 50% 占空比（逐行睡会被 Windows
 *       约 1 ms 的 sleep 粒度放大成两倍以上的拖慢）；
 *   <li>平均 tick &gt; 35 ms → 提到 25% 占空比；&gt; 45 ms → 整段暂停，每 50 ms 重新判断；
 *   <li>2 秒没见到 tick（单机暂停 / 正在关服）→ 同样退避；
 *   <li>worker 是 daemon 且 {@link Thread#MIN_PRIORITY}（Windows 上优先级基本无效，
 *       真正起作用的是占空比 + tick 守卫）。
 * </ul>
 *
 * <p>同一时刻只保留一个任务：新任务会取消旧任务，符合"地图跟着玩家/视口移动"的用法。
 * 采样对象（{@link SurfaceBiomeSampler}）不可变，构造必须在服务端主线程完成，
 * 之后可被 worker 线程安全共享。
 */
public final class PreviewScheduler {

    private static final String TAG = "BiomePreviewScheduler";

    private static final long PAUSE_TICK_NANOS = 45_000_000L;
    private static final long THROTTLE_TICK_NANOS = 35_000_000L;
    private static final long STALE_TICK_NANOS = 2_000_000_000L;
    private static final long IDLE_SLEEP_MILLIS = 50L;
    private static final long WORK_BATCH_NANOS = 4_000_000L;
    private static final int MAIN_QUEUE_BUDGET = 4;
    private static final int WORKERS = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));

    private static final ConcurrentLinkedQueue<Runnable> MAIN_QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicReference<Job> CURRENT = new AtomicReference<>();
    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    private static volatile long lastTickNanos;
    private static volatile long lastTickAtNanos = System.nanoTime();
    private static ExecutorService pool;

    private PreviewScheduler() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
            long started = System.nanoTime();
            MinecraftServer server = event.getServer();
            lastTickNanos = server.getAverageTickTimeNanos();
            lastTickAtNanos = System.nanoTime();
            Job job = CURRENT.get();
            if (job != null && lastTickNanos > job.maxTickNanos) {
                job.maxTickNanos = lastTickNanos;
            }
            drainMainQueue();
            // 这是预览真正占服务端主线程的时间（读 tick 均值 + 派发完成回调），应为微秒级
            if (job != null) {
                long hookNanos = System.nanoTime() - started;
                if (hookNanos > job.maxHookNanos) {
                    job.maxHookNanos = hookNanos;
                }
            }
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> cancelAll());
    }

    /**
     * 提交一次预览。必须在服务端主线程调用（sampler 由调用方在该线程构造）。
     *
     * @param radius 半径（方块）；网格覆盖 {@code [center-radius, center-radius+size*step)}
     * @param step   采样步长（方块），来自 {@link PreviewSettings#stepBlocks()}
     * @param onDone 完成回调，保证在服务端主线程执行（任务被取消时不执行）
     */
    public static Job submit(SurfaceBiomeSampler sampler, int centerX, int centerZ,
                             int radius, int step, Consumer<Result> onDone) {
        Job job = new Job(centerX, centerZ, radius, step, onDone);
        Job previous = CURRENT.getAndSet(job);
        if (previous != null) {
            previous.cancel();
        }
        LogManager.serverInfo(TAG, "job queued center=({},{}) radius={} step={} samples={} workers={}",
                centerX, centerZ, radius, step, job.size * job.size, WORKERS);
        ExecutorService executor = pool();
        for (int i = 0; i < WORKERS; i++) {
            executor.execute(() -> work(job, sampler));
        }
        return job;
    }

    public static boolean isRunning() {
        Job job = CURRENT.get();
        return job != null && !job.cancelled;
    }

    /** 取消当前任务（不触发完成回调）。 */
    public static void cancelAll() {
        Job job = CURRENT.getAndSet(null);
        if (job != null && !job.cancelled) {
            job.cancel();
            LogManager.serverInfo(TAG, "job cancelled at %d/%d rows", job.doneRows.get(), job.size);
        }
    }

    private static void work(Job job, SurfaceBiomeSampler sampler) {
        long pending = 0;
        while (!job.cancelled && CURRENT.get() == job) {
            if (System.nanoTime() - lastTickAtNanos > STALE_TICK_NANOS) {
                sleepNanos(IDLE_SLEEP_MILLIS * 1_000_000L);
                continue;
            }
            if (lastTickNanos > PAUSE_TICK_NANOS) {
                sleepNanos(IDLE_SLEEP_MILLIS * 1_000_000L);
                continue;
            }
            int row = job.nextRow.getAndIncrement();
            if (row >= job.size) {
                break;
            }
            long started = System.nanoTime();
            job.sampleRow(sampler, row);
            long cost = System.nanoTime() - started;
            job.doneRows.incrementAndGet();
            pending += cost;
            // 攒到 4 ms 再睡：Windows 的 sleep 粒度约 1 ms，逐行睡会把任务拖慢一倍多
            if (pending >= WORK_BATCH_NANOS) {
                // 50% 占空比；tick 已经在变慢时降到 25%
                sleepNanos(lastTickNanos > THROTTLE_TICK_NANOS ? pending * 3 : pending);
                pending = 0;
            }
        }
        job.finishIfComplete();
    }

    private static void drainMainQueue() {
        for (int i = 0; i < MAIN_QUEUE_BUDGET; i++) {
            Runnable task = MAIN_QUEUE.poll();
            if (task == null) {
                return;
            }
            try {
                task.run();
            } catch (Throwable throwable) {
                LogManager.serverError(TAG, "completion callback failed: {}", throwable);
            }
        }
    }

    private static synchronized ExecutorService pool() {
        if (pool == null) {
            pool = Executors.newFixedThreadPool(WORKERS, runnable -> {
                Thread thread = new Thread(runnable, "biomepreview-worker-" + THREAD_SEQ.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });
        }
        return pool;
    }

    private static void sleepNanos(long nanos) {
        if (nanos <= 0) {
            return;
        }
        try {
            Thread.sleep(nanos / 1_000_000L, (int) (nanos % 1_000_000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 完成结果；{@code palette} 是群系 id，{@code cells[row * size + col]} 是调色板下标。 */
    public record Result(int centerX, int centerZ, int radius, int step, int size,
                         List<String> palette, short[] cells,
                         long wallNanos, long maxHookNanos, long maxTickNanos) {

        public String summary() {
            return String.format(Locale.ROOT,
                    "tile %dx%d center=(%d,%d) step=%d biomes=%d samples=%d wall=%.1f ms "
                            + "serverThread=%.3f ms ambientTick=%.1f ms",
                    size, size, centerX, centerZ, step, palette.size(), (long) size * size,
                    wallNanos / 1.0e6, maxHookNanos / 1.0e6, maxTickNanos / 1.0e6);
        }
    }

    public static final class Job {

        private final int centerX;
        private final int centerZ;
        private final int radius;
        private final int step;
        private final int size;
        private final Consumer<Result> onDone;
        private final short[] cells;
        private final Map<Holder<Biome>, Integer> paletteIndex = new ConcurrentHashMap<>();
        private final List<Holder<Biome>> palette = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger nextRow = new AtomicInteger();
        private final AtomicInteger doneRows = new AtomicInteger();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final long startedNanos = System.nanoTime();
        private volatile boolean cancelled;
        private volatile long maxHookNanos;
        private volatile long maxTickNanos;

        private Job(int centerX, int centerZ, int radius, int step, Consumer<Result> onDone) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radius = Math.max(1, radius);
            this.step = Math.max(SurfaceBiomeSampler.MIN_STEP_BLOCKS, step);
            this.size = (this.radius * 2) / this.step + 1;
            this.cells = new short[this.size * this.size];
            this.onDone = onDone;
        }

        private void sampleRow(SurfaceBiomeSampler sampler, int row) {
            int z = centerZ - radius + row * step;
            int base = row * size;
            for (int col = 0; col < size; col++) {
                int x = centerX - radius + col * step;
                cells[base + col] = (short) paletteIndex(sampler.surfaceAt(x, z));
            }
        }

        private int paletteIndex(Holder<Biome> biome) {
            Integer existing = paletteIndex.get(biome);
            if (existing != null) {
                return existing;
            }
            synchronized (palette) {
                Integer again = paletteIndex.get(biome);
                if (again != null) {
                    return again;
                }
                int index = palette.size();
                palette.add(biome);
                paletteIndex.put(biome, index);
                return index;
            }
        }

        private void finishIfComplete() {
            if (cancelled || CURRENT.get() != this) {
                return;
            }
            if (doneRows.get() < size) {
                return;
            }
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            CURRENT.compareAndSet(this, null);
            List<String> ids;
            synchronized (palette) {
                ids = new ArrayList<>(palette.size());
                for (Holder<Biome> biome : palette) {
                    ids.add(SurfaceBiomeSampler.idOf(biome));
                }
            }
            Result result = new Result(centerX, centerZ, radius, step, size, List.copyOf(ids), cells,
                    System.nanoTime() - startedNanos, maxHookNanos, maxTickNanos);
            LogManager.serverInfo(TAG, "job done {}", result.summary());
            MAIN_QUEUE.add(() -> onDone.accept(result));
        }

        private void cancel() {
            cancelled = true;
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }
}
