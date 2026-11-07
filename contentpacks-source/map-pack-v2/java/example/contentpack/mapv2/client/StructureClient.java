package example.contentpack.mapv2.client;

import com.mojang.logging.LogUtils;
import example.contentpack.mapv2.MapConfig;
import example.contentpack.mapv2.network.StructureQueryPayload;
import example.contentpack.mapv2.network.StructureResultPayload;
import example.contentpack.mapv2.structure.StructureEntry;
import example.contentpack.mapv2.structure.StructureScan;
import example.contentpack.mapv2.view.MapProjection;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构图层的客户端缓存。
 *
 * <p>缓存单元与服务端协议一致（{@link StructureScan#CELL_CHUNKS} 区块见方）：
 * 视口跨到没问过的单元就发一个 {@link StructureQueryPayload}，结果按单元存起来，
 * 平移/缩放时只补缺的单元，不重算已拿到的。
 *
 * <p>只信任服务端结果，不在客户端复算——结构注册表不在客户端（D92）。
 */
public final class StructureClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 请求发出去后这么久没有回应就允许重问（服务端限流/丢包时不会永久空白）。 */
    private static final long REQUEST_TIMEOUT_NANOS = 10_000_000_000L;
    /**
     * 发包间隔：服务端按「每秒 N 次」均匀窗口限流（默认 8），背靠背发第二包会被丢。
     * 250ms 一拍（≤4 次/秒）留足余量；一个视口通常只有几个单元，两秒内补得完。
     */
    private static final long SEND_INTERVAL_NANOS = 250_000_000L;
    private static final int MAX_CELLS_PER_VIEW = 64;
    private static final int MAX_CACHED_CELLS = 512;
    /** 预算截断的结果不是终态；最多补问这么多次，之后接受残留结果。 */
    private static final int MAX_RETRIES = 2;
    /**
     * 已拿到结果的单元最短重问间隔。区块加载会把单元标脏（服务端查询时会现场核实已加载区块），
     * 但走一路会连续加载成百上千个区块，不设冷却就等于每帧重扫。
     */
    private static final long REFRESH_INTERVAL_NANOS = 5_000_000_000L;

    /** 诊断预算：只在前几次请求/回包打日志，避免刷屏。 */
    private static int diagSends = 12;
    private static int diagReceives = 12;
    private static double lastHiddenBpp = Double.NaN;
    private static long lastSendAtNanos;

    private static final ResourceLocation FALLBACK_ICON =
            ResourceLocation.withDefaultNamespace("compass");

    /**
     * 客户端收到区块时调用：单元里可能已有新生成/已保存的区块，之前的「推测」可以核实了。
     *
     * <p>只标脏不算，真正重问由 {@link #ensureVisible} 按冷却发出；
     * 没拿到过结果的单元不用标，它本来就会被问。
     */
    static void markChunkLoaded(ResourceLocation dimension, int chunkX, int chunkZ) {
        Cell cell = CELLS.get(new CellKey(dimension,
                StructureScan.cellIndex(chunkX), StructureScan.cellIndex(chunkZ)));
        if (cell != null && cell.loaded) {
            cell.marks++;
        }
    }
    /**
     * 变体优先的图标：一个结构集里只有需要区分的变体才登记（下界复合 = 要塞 + 堡垒遗迹）。
     * 没登记的回落到 {@link #ICONS_BY_SET}，所以村庄那 5 个变体仍统一画钟。
     */
    private static final Map<String, ResourceLocation> ICONS_BY_STRUCTURE = Map.ofEntries(
            Map.entry("fortress", ResourceLocation.withDefaultNamespace("nether_bricks")),
            Map.entry("bastion_remnant", ResourceLocation.withDefaultNamespace("gilded_blackstone")));
    private static final Map<String, ResourceLocation> ICONS_BY_SET = Map.ofEntries(
            Map.entry("villages", ResourceLocation.withDefaultNamespace("bell")),
            Map.entry("pillager_outposts", ResourceLocation.withDefaultNamespace("crossbow")),
            Map.entry("desert_pyramids", ResourceLocation.withDefaultNamespace("sandstone")),
            Map.entry("jungle_temples", ResourceLocation.withDefaultNamespace("mossy_cobblestone")),
            Map.entry("swamp_huts", ResourceLocation.withDefaultNamespace("mud_bricks")),
            Map.entry("igloos", ResourceLocation.withDefaultNamespace("snow_block")),
            Map.entry("ocean_monuments", ResourceLocation.withDefaultNamespace("prismarine")),
            Map.entry("ocean_ruins", ResourceLocation.withDefaultNamespace("prismarine_bricks")),
            Map.entry("shipwrecks", ResourceLocation.withDefaultNamespace("oak_boat")),
            Map.entry("woodland_mansions", ResourceLocation.withDefaultNamespace("dark_oak_log")),
            Map.entry("ancient_cities", ResourceLocation.withDefaultNamespace("sculk_catalyst")),
            Map.entry("trail_ruins", ResourceLocation.withDefaultNamespace("terracotta")),
            Map.entry("trial_chambers", ResourceLocation.withDefaultNamespace("trial_spawner")),
            Map.entry("ruined_portals", ResourceLocation.withDefaultNamespace("crying_obsidian")),
            Map.entry("buried_treasures", ResourceLocation.withDefaultNamespace("gold_ingot")),
            Map.entry("mineshafts", ResourceLocation.withDefaultNamespace("rail")),
            Map.entry("nether_complexes", ResourceLocation.withDefaultNamespace("nether_bricks")),
            Map.entry("nether_fossils", ResourceLocation.withDefaultNamespace("bone_block")),
            Map.entry("end_cities", ResourceLocation.withDefaultNamespace("purpur_block")),
            Map.entry("strongholds", ResourceLocation.withDefaultNamespace("ender_eye")));

    private static final Map<CellKey, Cell> CELLS = new HashMap<>();
    private static final Map<String, ItemStack> ICONS = new HashMap<>();

    private static boolean visible = true;
    private static boolean dirty = true;
    private static ResourceLocation snapshotDimension;
    private static List<StructureEntry> snapshot = List.of();
    private static long snapshotRevealVersion = -1L;

    private StructureClient() {
    }

    /** 底部按钮用；关闭时不发请求也不画。 */
    public static boolean isVisible() {
        return visible;
    }

    public static void setVisible(boolean value) {
        if (visible == value) {
            return;
        }
        visible = value;
        dirty = true;
    }

    public static void reset() {
        CELLS.clear();
        snapshot = List.of();
        snapshotDimension = null;
        snapshotRevealVersion = -1L;
        dirty = true;
    }

    static void tick() {
        if (CELLS.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        for (Cell cell : CELLS.values()) {
            if (cell.requested && now - cell.requestedAtNanos > REQUEST_TIMEOUT_NANOS) {
                cell.requested = false;
            }
        }
        if (CELLS.size() > MAX_CACHED_CELLS) {
            CELLS.clear();
            dirty = true;
        }
    }

    /**
     * 为当前视口补齐缺的单元，并返回本维度已到手的所有条目。
     * 缩放太浅、图层关闭或无世界身份时返回空表（连请求都不发）。
     */
    static List<StructureEntry> ensureVisible(ResourceLocation dimension,
                                              double originX, double originZ,
                                              int viewWidth, int viewHeight, double bpp) {
        if (!visible || !MapConfig.structureOverlay() || MapWorld.id() == null) {
            return List.of();
        }
        if (bpp > MapConfig.structureMaxBpp()) {
            double max = MapConfig.structureMaxBpp();
            if (bpp != lastHiddenBpp) {
                lastHiddenBpp = bpp;
                LOGGER.info("[MapPack] structure overlay hidden by zoom: bpp={} > max={}",
                        bpp, max);
            }
            return List.of();
        }
        lastHiddenBpp = Double.NaN;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.getConnection() == null) {
            return List.of();
        }

        int minChunkX = Math.floorDiv((int) Math.floor(originX), 16);
        int minChunkZ = Math.floorDiv((int) Math.floor(originZ), 16);
        int maxChunkX = Math.floorDiv((int) Math.floor(originX + viewWidth * bpp), 16);
        int maxChunkZ = Math.floorDiv((int) Math.floor(originZ + viewHeight * bpp), 16);
        int minCellX = StructureScan.cellIndex(minChunkX);
        int minCellZ = StructureScan.cellIndex(minChunkZ);
        int maxCellX = StructureScan.cellIndex(maxChunkX);
        int maxCellZ = StructureScan.cellIndex(maxChunkZ);
        long cells = (long) (maxCellX - minCellX + 1) * (maxCellZ - minCellZ + 1);
        if (cells > MAX_CELLS_PER_VIEW) {
            return List.of();
        }

        long now = System.nanoTime();
        if (now - lastSendAtNanos < SEND_INTERVAL_NANOS) {
            return visible(dimension);
        }
        outer:
        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                // 探明范围外的单元不问：省一次服务端扫描，也不缓存注定被过滤的结果
                if (!MapRevealClient.anyInStructureCell(dimension, cellX, cellZ)) {
                    continue;
                }
                Cell cell = CELLS.computeIfAbsent(
                        new CellKey(dimension, cellX, cellZ), ignored -> new Cell());
                if (cell.requested) {
                    continue;
                }
                if (cell.loaded) {
                    // 有区块加载过（marks 前移）且过了冷却才重问；否则候选级永远不会升级
                    if (cell.marks == cell.answered
                            || now - cell.respondedAtNanos < REFRESH_INTERVAL_NANOS) {
                        continue;
                    }
                }
                cell.requested = true;
                cell.requestedAtNanos = now;
                cell.requestedMarks = cell.marks;
                lastSendAtNanos = now;
                PacketDistributor.sendToServer(new StructureQueryPayload(dimension, cellX, cellZ));
                if (diagSends > 0) {
                    diagSends--;
                    LOGGER.info("[MapPack] structure query sent {} cell {}/{}", dimension,
                            cellX, cellZ);
                }
                break outer;
            }
        }
        return visible(dimension);
    }

    /** 服务端回包（客户端线程）。只接受当前维度的结果。 */
    public static void receive(StructureResultPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null
                || !minecraft.level.dimension().location().equals(payload.dimension())) {
            return;
        }
        Cell cell = CELLS.computeIfAbsent(
                new CellKey(payload.dimension(), payload.cellX(), payload.cellZ()),
                ignored -> new Cell());
        cell.entries = payload.entries();
        cell.requested = false;
        cell.respondedAtNanos = System.nanoTime();
        // 只承认「发出请求那一刻之前」的区块加载；飞行途中新加载的区块下一轮再问
        cell.answered = cell.requestedMarks;
        // 预算截断 ≠ 这个单元真的只有这些：冷启动的第一次扫描经常超时，
        // 直接标 loaded 会让单元永远是空的。补问有上限，超了才接受残留结果。
        if (payload.truncated() && cell.retries < MAX_RETRIES) {
            cell.loaded = false;
            cell.retries++;
        } else {
            cell.loaded = true;
        }
        dirty = true;
        if (diagReceives > 0) {
            diagReceives--;
            LOGGER.info("[MapPack] structure result {} cell {}/{} entries={} truncated={}",
                    payload.dimension(), payload.cellX(), payload.cellZ(),
                    payload.entries().size(), payload.truncated());
        }
    }

    static List<StructureEntry> visible(ResourceLocation dimension) {
        long revealVersion = MapRevealClient.version();
        if (!dirty && dimension.equals(snapshotDimension)
                && revealVersion == snapshotRevealVersion) {
            return snapshot;
        }
        List<StructureEntry> out = new ArrayList<>();
        for (Map.Entry<CellKey, Cell> entry : CELLS.entrySet()) {
            if (!entry.getKey().dimension().equals(dimension)) {
                continue;
            }
            for (StructureEntry structure : entry.getValue().entries) {
                // 单元里只要有一格探明就会问；显示仍按结构所在区块逐条过滤
                if (MapRevealClient.isRevealed(dimension, structure.blockX(), structure.blockZ())) {
                    out.add(structure);
                }
            }
        }
        snapshot = List.copyOf(out);
        snapshotDimension = dimension;
        snapshotRevealVersion = revealVersion;
        dirty = false;
        return snapshot;
    }

    /** 悬停命中：画布坐标下最近的条目（半径按 GUI 尺寸缩放）。 */
    static StructureEntry entryAt(int canvasX, int canvasY, int left, int top,
                                  double originX, double originZ, double bpp,
                                  int guiScale, ResourceLocation dimension) {
        // 和渲染用同一套门槛：看不见的图标不能被悬停命中，否则右键菜单会给出屏幕外的东西
        if (!visible || !MapConfig.structureOverlay() || bpp > MapConfig.structureMaxBpp()) {
            return null;
        }
        int radius = 10 * Math.max(1, guiScale);
        double best = (double) radius * radius;
        StructureEntry nearest = null;
        for (StructureEntry entry : visible(dimension)) {
            int px = left + MapProjection.pixel(entry.blockX(), originX, bpp);
            int py = top + MapProjection.pixel(entry.blockZ(), originZ, bpp);
            double distance = (double) (px - canvasX) * (px - canvasX)
                    + (double) (py - canvasY) * (py - canvasY);
            if (distance < best) {
                best = distance;
                nearest = entry;
            }
        }
        return nearest;
    }

    /**
     * 变体 id → 本地化名，没有词条再退回结构集 id；都没有时显示「未知结构」。
     *
     * <p>变体词条只在需要区分时登记（{@code fortress} / {@code bastion_remnant}），
     * 其它结构集的变体（如 5 种村庄）自然回落到集合名。
     */
    static Component name(StructureEntry entry) {
        Component translated = structureName(entry.structureId());
        if (translated != null) {
            return translated;
        }
        translated = structureName(entry.setId());
        if (translated != null) {
            return translated;
        }
        return Component.translatable("catoxidesbattlerebuild.map.structure.unknown",
                entry.structureId());
    }

    private static Component structureName(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) {
            return null;
        }
        String key = "catoxidesbattlerebuild.map.structure." + location.getPath();
        Component translated = Component.translatable(key);
        return translated.getString().equals(key) ? null : translated;
    }

    static ItemStack iconFor(StructureEntry entry) {
        return ICONS.computeIfAbsent(entry.structureId() + "|" + entry.setId(),
                ignored -> loadIcon(entry));
    }

    private static ItemStack loadIcon(StructureEntry entry) {
        ResourceLocation structure = ResourceLocation.tryParse(entry.structureId());
        ResourceLocation set = ResourceLocation.tryParse(entry.setId());
        ResourceLocation icon = null;
        if (structure != null) {
            icon = ICONS_BY_STRUCTURE.get(structure.getPath());
        }
        if (icon == null && set != null) {
            icon = ICONS_BY_SET.get(set.getPath());
        }
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(
                icon == null ? FALLBACK_ICON : icon));
        return stack.isEmpty()
                ? new ItemStack(BuiltInRegistries.ITEM.get(FALLBACK_ICON))
                : stack;
    }

    private record CellKey(ResourceLocation dimension, int cellX, int cellZ) {
    }

    private static final class Cell {
        List<StructureEntry> entries = List.of();
        boolean loaded;
        boolean requested;
        long requestedAtNanos;
        long respondedAtNanos;
        /** 区块加载计数与最近一次请求发出时的计数；两者不等说明结果可能过期。 */
        long marks;
        long requestedMarks;
        long answered;
        int retries;
    }
}
