package example.contentpack.mapv2;

import com.mojang.logging.LogUtils;
import example.contentpack.mapv2.client.BedSurfaces;
import example.contentpack.mapv2.client.BlockSurfaces;
import example.contentpack.mapv2.client.ChestSurfaces;
import example.contentpack.mapv2.client.ConduitSurfaces;
import example.contentpack.mapv2.client.PotSurfaces;
import example.contentpack.mapv2.client.ShulkerSurfaces;
import example.contentpack.mapv2.client.SignSurfaces;
import example.contentpack.mapv2.client.SkullSurfaces;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ConduitBlock;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * 采集：把客户端已加载区块的 16×16 列变成瓦片像素。
 *
 * <h3>采哪些层</h3>
 * <ul>
 *   <li><b>地狱</b>：一次采全部 16 格切片 —— 基岩天花板会让 top 层永远只有基岩，没有信息量。</li>
 *   <li><b>主世界 / 末地</b>：总是采 {@code top}；玩家处在地表以下时，额外采当前切片
 *       （挖矿到哪，地下图就长到哪）。</li>
 * </ul>
 * 切片只记录自己 16 格窗口内的方块，窗口全空就留透明；「从这层往下看」由显示端
 * 把选中层及以下所有已加载切片叠起来实现（见 {@link MapLayerStack}），采集端不烘焙 fallback。
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
    /** 一列最多记多少层叠层；再多视觉上也分不出来，继续扫只是浪费。 */
    private static final int MAX_STACK_LAYERS = 8;
    private static final int STATS_INTERVAL_TICKS = 200;
    /** 同一瓦片持续被采集时，纹理失效最多每个该间隔执行一次（初次铺图不用每 tick 重建）。 */
    private static final int REVISION_COALESCE_TICKS = 10;

    /**
     * 待采集区块：按入队那一刻离玩家的欧氏距离出队，近的先采。
     *
     * <p>距离是一次性快照，存进 {@link Queued}，比较器只读这个字段 —— 比较器不能依赖
     * 会变的玩家位置（TreeSet 的排序不变式会乱），也不在玩家移动后整队重算：
     * 重排会把已经排到头的近处工作又推回后面，而且队列里大部分项在被重排前就采完了。
     * 新进来的区块自带当时的距离，插入位置自然正确。
     */
    private static final Comparator<Queued> NEAREST_FIRST = Comparator
            .comparingInt(Queued::distance)
            .thenComparingInt(queued -> queued.job().pos().x)
            .thenComparingInt(queued -> queued.job().pos().z)
            .thenComparing(queued -> queued.job().layer());
    private static final NavigableSet<Queued> QUEUE = new TreeSet<>(NEAREST_FIRST);
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
            example.contentpack.mapv2.client.TileLodTextures.clearAll();
            example.contentpack.mapv2.client.BlockTopTextures.invalidate();
            MapColors.invalidate();
            example.contentpack.mapv2.client.BiomePalette.invalidate();
            example.contentpack.mapv2.client.MapWorld.reset();
            lastConnection = connection;
        }

        // 世界身份未就绪：先问服务端，这期间不采集（否则会写错目录）
        if (example.contentpack.mapv2.client.MapWorld.id() == null) {
            example.contentpack.mapv2.client.MapWorld.requestIfDue();
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
        // 切片只存自己窗口内的方块：变化点落在哪个切片，就只重采哪个切片（叠层在显示时做）
        int changedY = pos.getY();
        for (String layer : layers) {
            if (MapLayer.TOP.equals(layer) || MapLayer.contains(layer, changedY)) {
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
     * 区块刚加载（客户端主线程）→ 带上「此刻离玩家多远」入队。
     *
     * <p>为什么不能只靠 {@link #discover} 的周期扫描：扫描半径受视距钳制，
     * 事件驱动让每块新区块当拍就能采。入队不再抢插队首 —— 原版发包本来就是近→远
     * （{@code PlayerChunkSender} 按 distanceSquared 排序），插队首会把整批顺序反转成
     * 远→近；按入队时的距离排队，进服/传送这种一次性涌入就是从中心向外铺。
     */
    public static void onChunkLoaded(ClientLevel current, ChunkPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level != current) {
            return;
        }
        if (example.contentpack.mapv2.client.MapWorld.id() == null) {
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
                enqueue(job, playerChunk);
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
     * 当前应该采集的层。
     *
     * <p>地狱：天花板让 top 永远只有基岩，玩家选哪一层切片都不该是空白。
     * 其他维度：top 常驻，玩家在地表以下时加当前切片。
     */
    private static List<String> desiredLayers(Minecraft minecraft, ClientLevel current) {
        int playerY = minecraft.player.getBlockY();
        String section = MapLayer.section(
                MapLayer.sectionStart(playerY, current.getMinBuildHeight()));
        if (current.dimension() == Level.NETHER) {
            return allSections(current);
        }
        int surfaceY = current.getHeight(Heightmap.Types.WORLD_SURFACE,
                minecraft.player.getBlockX(), minecraft.player.getBlockZ());
        if (playerY < surfaceY - 2) {
            return List.of(MapLayer.TOP, section);
        }
        return List.of(MapLayer.TOP);
    }

    /** 该维度从 minBuildHeight 对齐的全部切片层。 */
    private static List<String> allSections(ClientLevel current) {
        int minBuildHeight = current.getMinBuildHeight();
        int maxBuildHeight = current.getMaxBuildHeight();
        List<String> layers = new ArrayList<>((maxBuildHeight - minBuildHeight)
                / MapLayer.SECTION_HEIGHT);
        for (int y = minBuildHeight; y < maxBuildHeight; y += MapLayer.SECTION_HEIGHT) {
            layers.add(MapLayer.section(y));
        }
        return layers;
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
                    enqueue(job, center);
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
            Job job = QUEUE.pollFirst().job();
            if (!isLoaded(current, job.pos().x, job.pos().z)) {
                SEEN.remove(job);
                // 传送后旧位置的任务会成批在这里被丢掉；不检查预算的话单帧会超支
                if (System.nanoTime() - start >= BUDGET_NANOS) {
                    break;
                }
                continue;
            }
            captureChunk(current, job);
            if (System.nanoTime() - start >= BUDGET_NANOS) {
                break;
            }
        }
    }

    /** 区块欧氏距离：与原版发包排序（{@code PlayerChunkSender} 的 distanceSquared）同口径。 */
    private static int distanceSquared(ChunkPos origin, ChunkPos pos) {
        int dx = pos.x - origin.x;
        int dz = pos.z - origin.z;
        return dx * dx + dz * dz;
    }

    /** 入队即定距：{@code origin} 是这一拍玩家所在的区块。 */
    private static void enqueue(Job job, ChunkPos origin) {
        QUEUE.add(new Queued(job, distanceSquared(origin, job.pos())));
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
        ChunkAccess chunk = biomeChunk(current, pos);
        MapTile tile = null;
        boolean changed = false;

        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int x = baseX + dx;
                int z = baseZ + dz;
                int topY = current.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                if (topY < minBuildHeight) {
                    continue;
                }
                // 找「完整表面」：从上往下跳过从上面看不见的（十字模型/草），
                // 半透明/不完整的方块逐层记下来，停在流体面或第一块实心顶面。
                Column column = scanColumn(current, x, z, topY, minBuildHeight);
                if (column == null) {
                    continue;
                }
                // 真有像素要写时才建瓦片：空瓦片会挡住磁盘读（requestLoad 见到内存瓦片就跳过）
                if (tile == null) {
                    tile = tileFor(dimension, MapLayer.TOP, tileX, tileZ);
                }
                changed |= tile.set(localBaseX + dx, localBaseZ + dz, column.color(),
                        column.surfaceY(), column.fluidDepth(), column.blockId(), column.stack(),
                        biomeIdAt(chunk, x, column.baseY(), z));
            }
        }
        if (changed) {
            touchTile(dimension, MapLayer.TOP, tileX, tileZ);
        }
    }

    /** 一列采到的表面栈（{@code stack} 是「上 → 下」，含终点层）。 */
    private record Column(int surfaceY, int color, int fluidDepth, String blockId,
                          List<MapStackLayer> stack, int baseY) {
    }

    /**
     * 从上看下去凑一条完整的表面栈。
     *
     * <p>半透明（玻璃、冰）、镂空（树叶）、不完整（火把、拉杆、栏杆、箱子）的方块
     * 按它真实的 UP 面记成一层，继续往下；碰到「顶面占满整格且不透明」的方块就停，
     * 那一块是终点（也记进栈，渲染时先画它）。十字模型从上面看不见，跳过但保留高度。
     *
     * <p>流体按 {@link FluidSurfaces} 的表面策略记层：水很浅（≤ 3 格）时水是半透明层，
     * 继续采水底，平色按水的深度混上去；更深就按水色收尾，不再往底扫。岩浆不透明，
     * 不管源头还是哪个流动层级，都记一层岩浆表面贴图并停在那里。
     *
     * <p>含水方块（{@code WATERLOGGED=true}）的水在这一块内部：方块自己的顶面和别的
     * 方块一样记进栈，水层用水的群系色、按液面高度插进顶面之间 —— 含水树叶是
     * 「树叶 + 蓝色的水」，而不是被树叶 tint 过的水。
     */
    @Nullable
    private static Column scanColumn(ClientLevel current, int x, int z,
                                     int topY, int minBuildHeight) {
        List<MapStackLayer> layers = new ArrayList<>();
        List<MapStackLayer> base = new ArrayList<>();
        BlockState terminal = null;
        int terminalY = topY;
        int surfaceY = -1;
        int fluidDepth = 0;
        int budget = MAX_STACK_LAYERS;
        boolean fluidLayerAdded = false;

        for (int y = topY; y >= minBuildHeight; y--) {
            POS.set(x, y, z);
            BlockState state = current.getBlockState(POS);
            if (state.isAir()) {
                continue;
            }
            if (surfaceY < 0) {
                surfaceY = y;
            }
            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
                FluidSurface surface = FluidSurfaces.of(fluid);
                if (surface == null) {
                    // 未登记的流体：当终点，颜色仍从方块取，不记流体层
                    int floorY = current.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
                    fluidDepth = Math.min(127, Math.max(1, y - floorY));
                    terminal = state;
                    terminalY = y;
                    break;
                }
                // 流体色按流体自己取（水的群系色、岩浆的 JSON 色）：含水树叶的方块色是
                // 树叶绿，拿宿主方块取色会把水面染成树叶色
                int fluidArgb = surface.color(current, POS);
                if (fluid.is(FluidTags.WATER)
                        && state.hasProperty(BlockStateProperties.WATERLOGGED)
                        && state.getValue(BlockStateProperties.WATERLOGGED)) {
                    // 含水方块：水在这一块内部，液面比顶面低一点。方块自己的顶面照记，
                    // 水层按液面高度插进这些顶面之间 —— 树叶的水在叶下（透过镂空看得见），
                    // 台阶下半的水在半腰，整列仍只留这一层水面。
                    BlockSurfaces.Surface host = BlockSurfaces.of(state);
                    List<BlockSurfaces.Part> parts = host.parts();
                    int split = fluidSplitIndex(
                            fluid.getHeight(current, POS) * MapStackLayer.UNITS, parts);
                    for (int i = 0; i < split && budget > 0; i++) {
                        layers.add(layerOf(parts.get(i), state, y, current, x, z));
                        budget--;
                    }
                    if (!fluidLayerAdded && budget > 0) {
                        layers.add(fluidLayer(surface, y, fluidArgb, surface.alpha(1)));
                        fluidLayerAdded = true;
                        fluidDepth = 1;
                        budget--;
                    }
                    if (host.kind() == BlockSurfaces.Kind.TERMINAL) {
                        // 液面以下的顶面（下半台阶、活板门的板面）就是这一列的实体终点
                        terminal = state;
                        terminalY = y;
                        for (int i = split; i < parts.size() && base.size() < MAX_STACK_LAYERS; i++) {
                            base.add(layerOf(parts.get(i), state, y, current, x, z));
                        }
                        break;
                    }
                    for (int i = split; i < parts.size() && budget > 0; i++) {
                        layers.add(layerOf(parts.get(i), state, y, current, x, z));
                        budget--;
                    }
                    continue;
                }
                int floorY = current.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
                int depth = Math.max(1, y - floorY);
                if (!surface.opaque() && depth <= surface.shallowDepth() && budget > 0) {
                    // 浅流体：整列只记一层流体（深度决定透明度），继续找底
                    if (!fluidLayerAdded) {
                        layers.add(fluidLayer(surface, y, fluidArgb, surface.alpha(depth)));
                        fluidLayerAdded = true;
                        fluidDepth = depth;
                        budget--;
                    }
                    continue;
                }
                fluidDepth = surface.depthSign() * Math.min(127, depth);
                terminal = state;
                terminalY = y;
                // 深水/岩浆：流体自己就是终点；放大时也画流体贴图
                base.add(fluidLayer(surface, y, fluidArgb, 1f));
                break;
            }
            BlockSurfaces.Surface surface = BlockSurfaces.of(state);
            // 实体渲染的方块没有烘焙模型，顶面由各自的 Surface 按渲染器变换拼出来
            BlockSurfaces.Surface synthesized = synthesizedSurface(state, current, POS);
            if (synthesized != null) {
                surface = synthesized;
            }
            if (surface.kind() == BlockSurfaces.Kind.TERMINAL) {
                // 终点可能没有可画的面（箱子等实体渲染方块）：照样收尾，保住平色
                terminal = state;
                terminalY = y;
                for (BlockSurfaces.Part part : surface.parts()) {
                    if (base.size() >= MAX_STACK_LAYERS) {
                        break;
                    }
                    base.add(layerOf(part, state, y, current, x, z));
                }
                break;
            }
            if (surface.parts().isEmpty()) {
                continue; // 十字模型/草/花：从上面看是空的
            }
            for (BlockSurfaces.Part part : surface.parts()) {
                if (budget <= 0) {
                    break;
                }
                layers.add(layerOf(part, state, y, current, x, z));
                budget--;
            }
            if (budget <= 0) {
                break;
            }
        }
        if (surfaceY < 0) {
            return null;
        }
        if (terminal == null && base.isEmpty() && layers.isEmpty()) {
            return null; // 整列只有从上面看不见的东西
        }
        if (terminal == null && base.isEmpty()) {
            // 没有实心底（悬空玻璃）：最下面那层自己当终点
            base.add(layers.remove(layers.size() - 1));
        }
        List<MapStackLayer> merged = MapStackLayer.merge(layers);
        // 终点自己的顶面均值：低缩放和贴图层用同一条取色规则，别回落到 MapColor 分类色
        int recorded = base.isEmpty() ? -1 : MapStackLayer.averageArgb(base, base.size());
        int terminalColor = terminal != null
                ? MapColors.colorFor(terminal, current, POS.set(x, terminalY, z), recorded)
                : base.get(0).argb();
        int terminalY2 = terminal != null ? terminalY : base.get(0).y();
        String terminalBlockId = terminal != null
                ? BuiltInRegistries.BLOCK.getKey(terminal.getBlock()).toString()
                : base.get(0).blockId();
        // 平色（缩略图/低缩放）沿用 v4 口径：最上面一层「读起来不透明」的层
        // （读数 ≥ 0.5，树叶/冰）或流体层就是表面本身，更深的层不再混进去；
        // 只有它上面的半透明层（玻璃等）按平均不透明度叠色。
        // 透明、叠层只在高缩放铺贴图时有意义（见 BlockTopTextures）。
        int flatBase = MapStackLayer.flatBaseIndex(merged);
        // 栈顶是拼满一格的地表覆盖物（粉色花簇：四片 8×8 分占四角）时，它自己就是
        // 这一列看得见的表面。每片单独只占四分之一格、贴图 alpha 又只有两三成，
        // 按「覆盖 × alpha」混会被下面的草吃掉 —— 低缩放整块变绿。
        int coverEnd = flatBase < 0 ? coverGroupEnd(merged, current, x, z) : 0;
        int color = flatBase >= 0 ? merged.get(flatBase).argb() : terminalColor;
        int baseY = flatBase >= 0 ? merged.get(flatBase).y() : terminalY2;
        String blockId = flatBase >= 0 ? merged.get(flatBase).blockId() : terminalBlockId;
        int blendTop = flatBase >= 0 ? flatBase - 1 : merged.size() - 1;
        // 覆盖物整组按并集盖在下面，不再按单层混一遍
        for (int i = blendTop; i >= coverEnd; i--) {
            MapStackLayer layer = merged.get(i);
            float alpha = MapStackLayer.stackedAlpha(layer.alpha(), layer.count())
                    * layer.coverage();
            color = blend(color, darken(layer.argb(), layer.count()), alpha);
        }
        if (coverEnd > 0) {
            float cover = MapStackLayer.unionCoverage(merged, coverEnd)
                    / (float) (MapStackLayer.UNITS * MapStackLayer.UNITS);
            color = blend(color, MapStackLayer.averageArgb(merged, coverEnd),
                    Math.min(1f, cover));
        }
        List<MapStackLayer> stack = new ArrayList<>(merged.size() + base.size());
        stack.addAll(merged);
        stack.addAll(base);
        return new Column(surfaceY, color, fluidDepth, blockId, List.copyOf(stack), baseY);
    }

    /**
     * 栈顶「拼满一格的多部件地表覆盖物」的层数；不是这种覆盖物返回 0。
     *
     * <p>四条判据都不认方块表：
     * <ol>
     *   <li>没有任何一层够得上平色基底（{@link MapStackLayer#flatBaseIndex} 为 -1）；</li>
     *   <li>栈顶是同一方块、同一高度的多片顶面（多部件模型拼出来的地面装饰）；</li>
     *   <li>这一块没有碰撞箱 —— 铺在地上的东西（粉色花簇、珊瑚扇）；玻璃/树叶是实体方块；</li>
     *   <li>这一组的投影并集盖住格子至少一半；单片顶面是薄装饰（铁轨、红石粉、发光地衣）。</li>
     * </ol>
     *
     * <p>缺了这条规则，四片 8×8 的粉色花簇只能按「覆盖率 × 贴图 alpha」混不到两成，
     * 低缩放被下面的草色吃掉，整块看起来还是绿的。
     */
    private static int coverGroupEnd(List<MapStackLayer> merged, ClientLevel current, int x, int z) {
        int end = MapStackLayer.topGroupEnd(merged);
        if (end < 2) {
            return 0;
        }
        MapStackLayer top = merged.get(0);
        if (FluidSurfaces.isFluidLayer(top.blockId())) {
            return 0;
        }
        POS.set(x, top.y(), z);
        BlockState state = current.getBlockState(POS);
        if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(top.blockId())
                || !state.getCollisionShape(current, POS).isEmpty()) {
            return 0;
        }
        int covered = MapStackLayer.unionCoverage(merged, end);
        return covered * 2 >= MapStackLayer.UNITS * MapStackLayer.UNITS ? end : 0;
    }

    /**
     * 液面（十六等分）以上的顶面片数。方块顶面按高度从高到低排好，流体层插在
     * 前 {@code split} 片之后：树叶的顶面（16）在液面上，水在叶下；下半台阶的
     * 板面（8）在液面下，水在台阶上。
     */
    static int fluidSplitIndex(float fluidTop, List<BlockSurfaces.Part> parts) {
        int split = 0;
        while (split < parts.size() && parts.get(split).height() > fluidTop) {
            split++;
        }
        return split;
    }

    /** 流体自己的一层：整格覆盖、整张贴图，blockId 用流体 ID 供渲染端识别。 */
    private static MapStackLayer fluidLayer(FluidSurface surface, int y, int argb, float alpha) {
        return new MapStackLayer(surface.sprite(), 0, 0,
                MapStackLayer.UV_UNITS, MapStackLayer.UV_UNITS,
                0, 0, MapStackLayer.UNITS, MapStackLayer.UNITS,
                surface.layerId(), y, argb,
                Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f), 1, 0);
    }

    /**
     * 没有烘焙模型的方块（床、箱子、告示牌、潜影盒、潮涌核心、装饰陶罐、生物头）→
     * 按各自渲染器的变换拼出的顶面；普通方块返回 {@code null}，调用方沿用
     * {@link BlockSurfaces#of}。
     */
    @Nullable
    private static BlockSurfaces.Surface synthesizedSurface(BlockState state, ClientLevel level,
                                                            BlockPos pos) {
        Block block = state.getBlock();
        if (block instanceof BedBlock) {
            return BedSurfaces.surface(state);
        }
        if (block instanceof ChestBlock || block instanceof EnderChestBlock) {
            return ChestSurfaces.surface(state);
        }
        if (block instanceof SignBlock) {
            return SignSurfaces.surface(state);
        }
        if (block instanceof ShulkerBoxBlock) {
            return ShulkerSurfaces.surface(state);
        }
        if (block instanceof ConduitBlock) {
            return ConduitSurfaces.surface(level, pos);
        }
        if (block instanceof DecoratedPotBlock) {
            return PotSurfaces.surface();
        }
        if (block instanceof AbstractSkullBlock) {
            return SkullSurfaces.surface(state, level, pos);
        }
        return null;
    }

    /** 一个 UP 面 → 表面栈里的一层（贴图 ID、子矩形、投影、旋转、平色、不透明度）。 */
    private static MapStackLayer layerOf(BlockSurfaces.Part part, BlockState state, int y,
                                         ClientLevel current, int x, int z) {
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (part.flatArgb() >= 0) {
            // 生物头：贴图不在图集里，只记头顶区域的平均色，渲染端直接填矩形
            return new MapStackLayer("", 0, 0, 0, 0,
                    part.x0(), part.z0(), part.x1(), part.z1(),
                    blockId, y, 0xFF000000 | (part.flatArgb() & 0xFFFFFF),
                    part.alpha(), 1, part.rotation());
        }
        POS.set(x, y, z);
        String spriteId = BlockSurfaces.idOf(part.sprite());
        int argb;
        FluidSurface fluid = FluidSurfaces.ofSprite(spriteId);
        if (fluid != null) {
            // 炼药锅里的水/岩浆：方块自己的流体状态是空的，只能按模型里的内容物贴图
            // 取流体色；否则整口锅会被宿主方块的颜色吃掉（水锅变成全蓝、岩浆锅变灰）
            argb = fluid.color(current, POS);
        } else if (part.tintIndex() >= 0) {
            // 原版只给带 tintindex 的面套 BlockColors：炼药锅的内容物是 0，锅沿没有；
            // 拿不到 tint（有些 tint 面在特定状态下返回 -1）就按贴图原色
            int tint = MapColors.tintColor(state, current, POS, part.tintIndex());
            argb = tint != -1 ? 0xFF000000 | (tint & 0xFFFFFF) : BlockSurfaces.partColor(part);
        } else if (BlockSurfaces.hasTintedParts(state) || MapColors.textureDriven(state, current, POS)) {
            // 这一面没有 tintindex：游戏里它就是贴图原样，记录这一片自己的平均色。
            // JSON 覆盖和方块级 tint（草、树叶、炼药锅的水）只属于有 tint 的面 ——
            // 拿 MapColor 这类分类色当颜色，渲染端会把它当 tint 乘上去（PLANT 把粉花染绿）
            argb = BlockSurfaces.partColor(part);
        } else {
            argb = MapColors.colorFor(state, current, POS);
        }
        return new MapStackLayer(BlockSurfaces.idOf(part.sprite()),
                part.u0(), part.v0(), part.u1(), part.v1(),
                part.x0(), part.z0(), part.x1(), part.z1(),
                blockId, y, argb, part.alpha(), 1, part.rotation());
    }

    /** {@code under} 上盖一层 {@code over}，{@code alpha} 是覆盖强度。 */
    private static int blend(int under, int over, float alpha) {
        if (alpha <= 0.001f) {
            return under;
        }
        float weight = Math.min(1f, alpha);
        float keep = 1f - weight;
        int r = Math.round(((under >> 16) & 0xFF) * keep + ((over >> 16) & 0xFF) * weight);
        int g = Math.round(((under >> 8) & 0xFF) * keep + ((over >> 8) & 0xFF) * weight);
        int b = Math.round((under & 0xFF) * keep + (over & 0xFF) * weight);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 层数越多越暗，让树冠/多层玻璃在平色上也看得出厚度。 */
    private static int darken(int argb, int count) {
        float factor = MapStackLayer.density(count);
        int r = Math.round(((argb >> 16) & 0xFF) * factor);
        int g = Math.round(((argb >> 8) & 0xFF) * factor);
        int b = Math.round((argb & 0xFF) * factor);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * 切片：在 16 格窗口内从上往下找最高非空气方块；窗口整段空气就留透明。
     *
     * <p>不向更低层回落：留空是「这一层没有方块」的准确记录，叠加渲染时自然露出下层。
     * 若在这里烘焙 fallback，同一份下层方块会被复制进每一层，改方块后要重采整列。
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
        ChunkAccess chunk = biomeChunk(current, pos);
        MapTile tile = null;
        boolean changed = false;

        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int x = baseX + dx;
                int z = baseZ + dz;
                int y = Math.min(
                        current.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1,
                        sectionMaxY);
                BlockState found = null;
                while (y >= sectionMinY) {
                    POS.set(x, y, z);
                    BlockState state = current.getBlockState(POS);
                    if (!state.isAir()) {
                        found = state;
                        break;
                    }
                    y--;
                }
                if (found == null) {
                    continue;
                }
                if (tile == null) {
                    tile = tileFor(dimension, layer, tileX, tileZ);
                }
                changed |= put(current, tile, localBaseX + dx, localBaseZ + dz,
                        x, z, y, y, found, null, blockIds, chunk);
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

    /**
     * 切片/单层写入（top 层走 {@link #scanColumn}，不用这里）。
     *
     * @param y        完整表面的方块坐标（颜色、群系、流体深度都按它算）
     * @param surfaceY 这一列最高的非空气 y（草/玻璃比下面的完整表面高，明暗仍按它算）
     * @param stack    表面栈（无叠层为 null）
     */
    private static boolean put(ClientLevel current, MapTile tile, int localX, int localZ,
                               int x, int z, int y, int surfaceY, BlockState state,
                               @Nullable List<MapStackLayer> stack, Map<Block, String> blockIds,
                               ChunkAccess chunk) {
        int fluidDepth = 0;
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            FluidSurface surface = FluidSurfaces.of(fluid);
            if (surface != null) {
                int floorY = current.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
                int depth = Math.max(1, y - floorY);
                fluidDepth = surface.depthSign() * Math.min(127, depth);
            }
        }
        POS.set(x, y, z);
        int color = MapColors.colorFor(state, current, POS);
        String blockId = blockIds.computeIfAbsent(state.getBlock(),
                block -> BuiltInRegistries.BLOCK.getKey(block).toString());
        // 加载过的区域把群系一起写进瓦片：区块卸载后 hover 仍是「确定的群系」，不回落成预测
        return tile.set(localX, localZ, color, surfaceY, fluidDepth, blockId,
                stack == null ? List.of() : stack, biomeIdAt(chunk, x, y, z));
    }

    /** 这一列所在区块的群系 ID；区块不在缓存里返回 null（瓦片里记为 -1）。 */
    private static ChunkAccess biomeChunk(ClientLevel current, ChunkPos pos) {
        return current.getChunkSource().getChunk(pos.x, pos.z, ChunkStatus.BIOMES, false);
    }

    /**
     * 读区块自己的群系存储，不走 {@code level.getBiome}：后者做 8 角模糊采样，
     * 邻块不在缓存时会读出假「平原」（与 {@code MapProbe.loadedBiomeId} 同口径）。
     */
    private static String biomeIdAt(ChunkAccess chunk, int x, int y, int z) {
        if (chunk == null) {
            return null;
        }
        Holder<Biome> biome = chunk.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y),
                QuartPos.fromBlock(z));
        return biome.unwrapKey().map(key -> key.location().toString()).orElse(null);
    }

    private record Job(String layer, ChunkPos pos) {
    }

    /** 队列项：入队那一刻算好的距离，排序只读它，不随玩家移动刷新。 */
    private record Queued(Job job, int distance) {
    }

    private record TileRef(ResourceLocation dimension, String layer, int tileX, int tileZ) {
    }
}
