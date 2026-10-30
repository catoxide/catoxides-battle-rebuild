package example.contentpack.map;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 采集：把客户端已加载区块的 16×16 列变成瓦片像素。
 *
 * <h3>采哪些层</h3>
 * <ul>
 *   <li><b>地狱</b>：只采玩家当前 y 所在的 16 格切片 —— 基岩天花板会让 top 层永远只有基岩，
 *       没有信息量。</li>
 *   <li><b>主世界 / 末地</b>：总是采 {@code top}；玩家处在地表以下时，额外采当前切片
 *       （挖矿到哪，地下图就长到哪）。</li>
 * </ul>
 * 切片起始 y 从 {@code level.getMinBuildHeight()} 对齐，层名用绝对 y，
 * 所以改世界高度后旧存档不会错位。
 *
 * <h3>线程</h3>
 * 方块读取必须在客户端主线程，因此「发现 → 入队 → 每 tick 限额出队」，
 * 单 tick 最多花 {@link #BUDGET_NANOS}，不请求加载区块。
 */
public final class MapCapture {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long BUDGET_NANOS = 2_000_000L;
    private static final int DISCOVER_INTERVAL_TICKS = 10;
    private static final int MAX_DIRTY_PER_TICK = 4;
    private static final int STATS_INTERVAL_TICKS = 200;
    /** 同一瓦片持续被采集时，纹理失效最多每个该间隔执行一次（初次铺图不用每 tick 重建）。 */
    private static final int REVISION_COALESCE_TICKS = 10;

    private static final ArrayDeque<Job> QUEUE = new ArrayDeque<>();
    private static final Set<Job> SEEN = new HashSet<>();
    private static final Set<Job> DIRTY = new HashSet<>();
    /** 本窗口采过的瓦片 → 最后一次被采的 tick；满 {@link #REVISION_COALESCE_TICKS} 才 bump。 */
    private static final Map<TileRef, Integer> TOUCHED = new HashMap<>();
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();

    private static ClientLevel level;
    private static Object lastConnection;
    private static int tickCounter;
    private static int statsTicks;
    private static int revisionTicks;
    private static int capturedChunks;
    private static volatile int lastRadius;

    private MapCapture() {
    }

    /** 每客户端 tick 调用。 */
    public static void tick(Minecraft minecraft) {
        Object connection = minecraft.getConnection();
        if (connection != lastConnection) {
            MapStorage.flushAll();
            MapStorage.clear();
            reset();
            MapStore.clearAll();
            example.contentpack.map.client.TileLodTextures.clearAll();
            example.contentpack.map.client.BlockTopTextures.invalidate();
            MapColors.invalidate();
            example.contentpack.map.client.BiomePalette.invalidate();
            example.contentpack.map.client.MapWorld.reset();
            lastConnection = connection;
        }

        // 世界身份未就绪：先问服务端，这期间不采集（否则会写错目录）
        if (example.contentpack.map.client.MapWorld.id() == null) {
            example.contentpack.map.client.MapWorld.requestIfDue();
            return;
        }

        ClientLevel current = minecraft.level;
        if (current == null || minecraft.player == null) {
            if (level != null) {
                reset();
            }
            return;
        }
        if (current != level) {
            reset();
            level = current;
        }

        drainDirty(current);
        if (++tickCounter % DISCOVER_INTERVAL_TICKS == 0) {
            discover(minecraft, current);
        }
        drainQueue(current);
        flushTouched();
        logStats();
        MapStorage.tick();
    }

    /** 本地方块变化 → 标记受影响层里该区块重采。 */
    public static void markDirty(Level changedLevel, BlockPos pos) {
        if (!(changedLevel instanceof ClientLevel clientLevel)) {
            return;
        }
        // 分离空间（Sable plot 里的船体）不属于任何地图层；只由对应扩展包自己采集
        if (MapHooks.INSTANCE.isDetached(changedLevel, pos)) {
            return;
        }
        ResourceLocation dimension = clientLevel.dimension().location();
        ChunkPos chunk = new ChunkPos(pos);
        Set<String> layers = MapStore.layers(dimension);
        if (layers.isEmpty()) {
            DIRTY.add(new Job(MapLayer.TOP, chunk));
            return;
        }
        for (String layer : layers) {
            if (MapLayer.TOP.equals(layer) || MapLayer.contains(layer, pos.getY())) {
                DIRTY.add(new Job(layer, chunk));
            }
        }
    }

    /** 远端变化（服务端广播）→ 内存里该维度的所有层都重采。 */
    public static void markDirtyChunk(ClientLevel clientLevel, ChunkPos pos) {
        if (MapHooks.INSTANCE.isDetached(clientLevel,
                new BlockPos(pos.getMinBlockX(), clientLevel.getMinBuildHeight(), pos.getMinBlockZ()))) {
            return;
        }
        Set<String> layers = MapStore.layers(clientLevel.dimension().location());
        if (layers.isEmpty()) {
            DIRTY.add(new Job(MapLayer.TOP, pos));
            return;
        }
        for (String layer : layers) {
            DIRTY.add(new Job(layer, pos));
        }
    }

    /**
     * 区块刚加载（客户端主线程）→ 立刻插到队首。
     *
     * <p>为什么不能只靠 {@link #discover} 的周期扫描：扫描半径受视距钳制，
     * 且队列是 FIFO —— 玩家往前走时新出现的区块排在旧积压之后，
     * 表现出来就是「越走越远、身后反而补不上」。事件驱动让每块新区块当拍就采。
     */
    public static void onChunkLoaded(ClientLevel current, ChunkPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level != current) {
            return;
        }
        if (example.contentpack.map.client.MapWorld.id() == null) {
            return;
        }
        // 选层要看玩家脚下地表高度；玩家自己的区块还没到时读不了，交给周期扫描补
        ChunkPos playerChunk = minecraft.player.chunkPosition();
        if (!isLoaded(current, playerChunk.x, playerChunk.z)) {
            return;
        }
        for (String layer : desiredLayers(minecraft, current)) {
            Job job = new Job(layer, pos);
            if (SEEN.add(job)) {
                QUEUE.addFirst(job);
            }
        }
    }

    public static int queuedChunks() {
        return QUEUE.size();
    }

    public static int dirtyChunks() {
        return DIRTY.size();
    }

    /** 清空队列与去重集合（不动已采集的瓦片）。 */
    public static void reset() {
        QUEUE.clear();
        SEEN.clear();
        DIRTY.clear();
        TOUCHED.clear();
        level = null;
        tickCounter = 0;
        statsTicks = 0;
        revisionTicks = 0;
        capturedChunks = 0;
        lastRadius = 0;
    }

    /**
     * 当前应该采集的层。地狱只切片；其他维度 top 常驻，玩家在地表以下时加当前切片。
     */
    private static List<String> desiredLayers(Minecraft minecraft, ClientLevel current) {
        int playerY = minecraft.player.getBlockY();
        String section = MapLayer.section(
                MapLayer.sectionStart(playerY, current.getMinBuildHeight()));
        if (current.dimension() == Level.NETHER) {
            return List.of(section);
        }
        int surfaceY = current.getHeight(Heightmap.Types.WORLD_SURFACE,
                minecraft.player.getBlockX(), minecraft.player.getBlockZ());
        if (playerY < surfaceY - 2) {
            return List.of(MapLayer.TOP, section);
        }
        return List.of(MapLayer.TOP);
    }

    private static void discover(Minecraft minecraft, ClientLevel current) {
        int radius = MapScan.captureRadius(minecraft.options.getEffectiveRenderDistance());
        lastRadius = radius;
        ChunkPos center = minecraft.player.chunkPosition();
        List<String> layers = desiredLayers(minecraft, current);
        MapScan.centerOut(radius, (dx, dz) -> {
            int cx = center.x + dx;
            int cz = center.z + dz;
            if (!isLoaded(current, cx, cz)) {
                return true;
            }
            for (String layer : layers) {
                Job job = new Job(layer, new ChunkPos(cx, cz));
                if (SEEN.add(job)) {
                    QUEUE.add(job);
                }
            }
            return true;
        });
    }

    private static void drainDirty(ClientLevel current) {
        if (DIRTY.isEmpty()) {
            return;
        }
        int processed = 0;
        Iterator<Job> iterator = DIRTY.iterator();
        while (iterator.hasNext() && processed < MAX_DIRTY_PER_TICK) {
            Job job = iterator.next();
            iterator.remove();
            if (!isLoaded(current, job.pos().x, job.pos().z)) {
                continue;
            }
            captureChunk(current, job);
            processed++;
        }
    }

    private static void drainQueue(ClientLevel current) {
        if (QUEUE.isEmpty()) {
            return;
        }
        long start = System.nanoTime();
        while (!QUEUE.isEmpty()) {
            Job job = QUEUE.poll();
            if (!isLoaded(current, job.pos().x, job.pos().z)) {
                SEEN.remove(job);
                continue;
            }
            captureChunk(current, job);
            if (System.nanoTime() - start >= BUDGET_NANOS) {
                break;
            }
        }
    }

    private static boolean isLoaded(ClientLevel current, int cx, int cz) {
        return current.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false)
                instanceof LevelChunk;
    }

    private static void captureChunk(ClientLevel current, Job job) {
        if (MapLayer.TOP.equals(job.layer())) {
            captureTop(current, job.pos());
        } else {
            captureSection(current, job.pos(), MapLayer.absoluteY(job.layer()));
        }
        capturedChunks++;
    }

    private static void captureTop(ClientLevel current, ChunkPos pos) {
        ResourceLocation dimension = current.dimension().location();
        int minBuildHeight = current.getMinBuildHeight();
        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();
        // 区块 16 格、瓦片 512 格（16 的倍数）：一块区块整体落在一块瓦片里，句柄只需取一次
        int tileX = MapTile.tileCoord(baseX);
        int tileZ = MapTile.tileCoord(baseZ);
        int localBaseX = MapTile.localCoord(baseX);
        int localBaseZ = MapTile.localCoord(baseZ);
        Map<Block, String> blockIds = new HashMap<>();
        MapTile tile = null;
        boolean changed = false;

        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int x = baseX + dx;
                int z = baseZ + dz;
                int y = current.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                if (y < minBuildHeight) {
                    continue;
                }
                POS.set(x, y, z);
                BlockState state = current.getBlockState(POS);
                if (state.isAir()) {
                    continue;
                }
                // 真有像素要写时才建瓦片：空瓦片会挡住磁盘读（requestLoad 见到内存瓦片就跳过）
                if (tile == null) {
                    tile = tileFor(dimension, MapLayer.TOP, tileX, tileZ);
                }
                changed |= put(current, tile, localBaseX + dx, localBaseZ + dz,
                        x, z, y, state, blockIds);
            }
        }
        if (changed) {
            touchTile(dimension, MapLayer.TOP, tileX, tileZ);
        }
    }

    /**
     * 切片：只在该 16 格窗口内从上往下找最高非空气方块；整段空气 = 未采集（透明）。
     */
    private static void captureSection(ClientLevel current, ChunkPos pos, int sectionMinY) {
        if (sectionMinY == Integer.MIN_VALUE) {
            return;
        }
        ResourceLocation dimension = current.dimension().location();
        String layer = MapLayer.section(sectionMinY);
        int sectionMaxY = Math.min(current.getMaxBuildHeight() - 1,
                sectionMinY + MapLayer.SECTION_HEIGHT - 1);
        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();
        int tileX = MapTile.tileCoord(baseX);
        int tileZ = MapTile.tileCoord(baseZ);
        int localBaseX = MapTile.localCoord(baseX);
        int localBaseZ = MapTile.localCoord(baseZ);
        Map<Block, String> blockIds = new HashMap<>();
        MapTile tile = null;
        boolean changed = false;

        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int x = baseX + dx;
                int z = baseZ + dz;
                int y = Math.min(
                        current.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1,
                        sectionMaxY);
                if (y < sectionMinY) {
                    continue;
                }
                // 窗口内向下找第一个非空气；整段空气则该列在本层未知
                while (y >= sectionMinY) {
                    POS.set(x, y, z);
                    BlockState state = current.getBlockState(POS);
                    if (!state.isAir()) {
                        if (tile == null) {
                            tile = tileFor(dimension, layer, tileX, tileZ);
                        }
                        changed |= put(current, tile, localBaseX + dx, localBaseZ + dz,
                                x, z, y, state, blockIds);
                        break;
                    }
                    y--;
                }
            }
        }
        if (changed) {
            touchTile(dimension, layer, tileX, tileZ);
        }
    }

    /**
     * 采集要写的瓦片；内存里还没有时先让存储层把磁盘旧数据补进来。
     *
     * <p>不这么做的话，重新进世界新建的空瓦片一落盘就把这块分区以前的采集抹掉了。
     */
    private static MapTile tileFor(ResourceLocation dimension, String layer,
                                   int tileX, int tileZ) {
        MapTile tile = MapStore.get(dimension, layer, tileX, tileZ);
        if (tile != null) {
            return tile;
        }
        MapStorage.requestMerge(dimension, layer, tileX, tileZ);
        return MapStore.getOrCreate(dimension, layer, tileX, tileZ);
    }

    /** 记录重采过的瓦片：立即排队落盘，纹理失效留到该瓦片静下来再做。 */    private static void touchTile(ResourceLocation dimension, String layer, int tileX, int tileZ) {
        TOUCHED.put(new TileRef(dimension, layer, tileX, tileZ), revisionTicks);
        MapStorage.markDirty(dimension, layer, tileX, tileZ);
    }

    private static void flushTouched() {
        revisionTicks++;
        if (TOUCHED.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<TileRef, Integer>> iterator = TOUCHED.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<TileRef, Integer> entry = iterator.next();
            if (revisionTicks - entry.getValue() < REVISION_COALESCE_TICKS) {
                continue;
            }
            TileRef ref = entry.getKey();
            MapTile tile = MapStore.get(ref.dimension(), ref.layer(), ref.tileX(), ref.tileZ());
            if (tile != null) {
                tile.bumpRevision();
            }
            iterator.remove();
        }
    }

    private static void logStats() {
        if (++statsTicks < STATS_INTERVAL_TICKS) {
            return;
        }
        statsTicks = 0;
        int captured = capturedChunks;
        capturedChunks = 0;
        if (captured == 0 && QUEUE.isEmpty()) {
            return;
        }
        LOGGER.info("[MapPack] capture 10s: chunks={} queued={} dirty={} seen={} radius={}",
                captured, QUEUE.size(), DIRTY.size(), SEEN.size(), lastRadius);
    }

    private static boolean put(ClientLevel current, MapTile tile, int localX, int localZ,
                               int x, int z, int y, BlockState state, Map<Block, String> blockIds) {
        int fluidDepth = 0;
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            int floorY = current.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
            int depth = Math.max(1, y - floorY);
            if (fluid.is(FluidTags.WATER)) {
                fluidDepth = Math.min(127, depth);
            } else if (fluid.is(FluidTags.LAVA)) {
                fluidDepth = -Math.min(127, depth);
            }
        }
        int color = MapColors.colorFor(state, current, POS);
        String blockId = blockIds.computeIfAbsent(state.getBlock(),
                block -> BuiltInRegistries.BLOCK.getKey(block).toString());
        return tile.set(localX, localZ, color, y, fluidDepth, blockId);
    }

    private record Job(String layer, ChunkPos pos) {
    }

    private record TileRef(ResourceLocation dimension, String layer, int tileX, int tileZ) {
    }
}
