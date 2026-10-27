package example.contentpack.map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
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

    private static final long BUDGET_NANOS = 2_000_000L;
    private static final int DISCOVER_INTERVAL_TICKS = 10;
    private static final int MAX_DIRTY_PER_TICK = 4;

    private static final ArrayDeque<Job> QUEUE = new ArrayDeque<>();
    private static final Set<Job> SEEN = new HashSet<>();
    private static final Set<Job> DIRTY = new HashSet<>();
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();

    private static ClientLevel level;
    private static Object lastConnection;
    private static int tickCounter;

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
        level = null;
        tickCounter = 0;
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
        int radius = Math.min(8, Math.max(2, minecraft.options.getEffectiveRenderDistance()));
        ChunkPos center = minecraft.player.chunkPosition();
        for (String layer : desiredLayers(minecraft, current)) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    int cx = center.x + dx;
                    int cz = center.z + dz;
                    if (!isLoaded(current, cx, cz)) {
                        continue;
                    }
                    Job job = new Job(layer, new ChunkPos(cx, cz));
                    if (SEEN.add(job)) {
                        QUEUE.add(job);
                    }
                }
            }
        }
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
    }

    private static void captureTop(ClientLevel current, ChunkPos pos) {
        ResourceLocation dimension = current.dimension().location();
        int minBuildHeight = current.getMinBuildHeight();
        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();

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
                put(current, dimension, MapLayer.TOP, x, z, y, state);
            }
        }
        bumpTile(dimension, MapLayer.TOP, baseX, baseZ);
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
                        put(current, dimension, layer, x, z, y, state);
                        break;
                    }
                    y--;
                }
            }
        }
        bumpTile(dimension, layer, baseX, baseZ);
    }

    /** 重采后让纹理缓存失效并安排落盘。 */
    private static void bumpTile(ResourceLocation dimension, String layer, int baseX, int baseZ) {
        int tileX = MapTile.tileCoord(baseX);
        int tileZ = MapTile.tileCoord(baseZ);
        MapTile tile = MapStore.get(dimension, layer, tileX, tileZ);
        if (tile != null) {
            tile.bumpRevision();
        }
        MapStorage.markDirty(dimension, layer, tileX, tileZ);
    }

    private static void put(ClientLevel current, ResourceLocation dimension, String layer,
                            int x, int z, int y, BlockState state) {
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
        String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock()).toString();
        MapStore.getOrCreate(dimension, layer, MapTile.tileCoord(x), MapTile.tileCoord(z))
                .set(MapTile.localCoord(x), MapTile.localCoord(z), color, y, fluidDepth, blockId);
    }

    private record Job(String layer, ChunkPos pos) {
    }
}
