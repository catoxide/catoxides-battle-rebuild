package example.contentpack.mapv2.structure;

import com.mojang.logging.LogUtils;
import example.contentpack.mapv2.network.StructureQueryPayload;
import example.contentpack.mapv2.network.StructureResultPayload;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 服务端结构候选扫描（D90–D95）。
 *
 * <p>只用原版的纯计算接口：{@code possibleStructureSets} 给出该维度可能生成的结构集，
 * {@code RandomSpreadStructurePlacement.getPotentialStructureChunk} 复算区域落点，
 * {@code StructurePlacement.isStructureChunk} 再叠上 frequency 与 exclusion zone，
 * 最后用 {@code BiomeSource.getNoiseBiome} 做群系门（D94 的「推测」级）。
 * <b>不生成区块、不写盘、不调 {@code /locate}</b>（D91）。候选出来后按
 * {@link StructureVerdicts}（区块写盘时的判定）升级为确认或丢弃；没判过的区块再看是否
 * 恰好已加载，仍未加载的保持候选级。
 *
 * <p>扫描跑在单线程后台队列里，服务端线程只负责取世界信息与回包；每个玩家有独立限流，
 * 单次请求还有软预算，超了就把已算完的部分标 {@code truncated} 返回。
 */
public final class StructureService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long STALE_REQUEST_NANOS = 60_000_000_000L;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "map-pack-v2-structures");
        thread.setDaemon(true);
        return thread;
    });

    private static final Map<UUID, Long> LAST_REQUEST = new ConcurrentHashMap<>();

    /** 诊断预算：启动后前若干次扫描各打一行摘要，用来定位「扫了但没有结果」。 */
    private static int diagLeft = 40;
    private static int diagResolve = 20;

    private StructureService() {
    }

    public static void query(ServerPlayer player, StructureQueryPayload request) {
        MinecraftServer server = player.getServer();
        if (server == null || !StructureServerConfig.enabled()) {
            return;
        }
        if (!allowRequest(player, StructureServerConfig.requestsPerSecond())) {
            return;
        }
        ServerLevel level = server.getLevel(
                ResourceKey.create(Registries.DIMENSION, request.dimension()));
        if (level == null) {
            return;
        }
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        BiomeSource biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
        // 群系采样只在原版群系源上开：mod 群系源可能带状态，后台线程不安全（D93 也只验收原版）
        ScanContext context = new ScanContext(
                state,
                biomeSource,
                state.randomState().sampler(),
                StructureScan.sampleHeights(level.getMinBuildHeight(), level.getMaxBuildHeight(),
                        level.getSeaLevel()),
                StructureServerConfig.verifyBiomes() && isVanillaBiomeSource(biomeSource),
                StructureServerConfig.showModdedSets());
        ResourceLocation dimension = request.dimension();
        int cellX = request.cellX();
        int cellZ = request.cellZ();
        int minSpacing = StructureServerConfig.minSpacing();
        int maxResults = StructureServerConfig.maxResults();
        int budgetMillis = StructureServerConfig.budgetMillis();

        WORKER.execute(() -> {
            Scan scan;
            try {
                scan = scan(context, cellX, cellZ, minSpacing, maxResults, budgetMillis);
            } catch (RuntimeException error) {
                LOGGER.warn("[MapPack] structure scan failed for {} cell {}/{}: {}",
                        dimension, cellX, cellZ, error.toString());
                scan = new Scan(List.of(), true);
            }
            Scan result = scan;
            server.execute(() -> {
                // 扫描期间可能已经断开：此时 connection 仍非 null，但发包会抛
                if (player.connection == null || player.hasDisconnected()) {
                    return;
                }
                List<StructureEntry> entries = resolve(level, result.candidates());
                PacketDistributor.sendToPlayer(player, new StructureResultPayload(
                        dimension, cellX, cellZ, entries, result.truncated()));
            });
        });
    }

    /**
     * 把候选点变成回包条目。只在服务端主线程调用。
     *
     * <p>先查 {@link StructureVerdicts}：区块写盘时已经判定过的，直接按结果升级或丢弃，
     * 与区块现在是否加载无关。没判过的区块再看是否恰好已加载（原版算过 STRUCTURE_STARTS），
     * 有起点升为 {@link StructureEntry#TIER_CONFIRMED}，没有起点说明候选不会长结构
     * （废弃传送门最常见的「标记有、实地没有」就是这种），直接不返回。都还没轮到就保持候选级。
     * 全程不读盘、不触发生成。
     */
    private static List<StructureEntry> resolve(ServerLevel level, List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        ResourceLocation dimension = level.dimension().location();
        List<StructureEntry> entries = new ArrayList<>(candidates.size());
        int confirmed = 0;
        int dropped = 0;
        int unloaded = 0;
        int judged = 0;
        for (Candidate candidate : candidates) {
            ChunkPos pos = candidate.pos();
            Map<ResourceLocation, Boolean> verdict = StructureVerdicts.get(dimension, pos);
            if (verdict != null) {
                Boolean present = verdict.get(candidate.setId());
                if (present != null) {
                    judged++;
                    if (!present) {
                        dropped++;
                        continue;
                    }
                    confirmed++;
                    entries.add(new StructureEntry(candidate.setId().toString(), pos.x, pos.z,
                            StructureEntry.TIER_CONFIRMED));
                    continue;
                }
            }
            byte tier = StructureEntry.TIER_CANDIDATE;
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk != null) {
                if (!StructureVerdicts.hasStart(chunk, candidate.set())) {
                    dropped++;
                    continue;
                }
                confirmed++;
                tier = StructureEntry.TIER_CONFIRMED;
            } else {
                unloaded++;
            }
            entries.add(new StructureEntry(candidate.setId().toString(), pos.x, pos.z, tier));
        }
        if (diagResolve > 0) {
            diagResolve--;
            LOGGER.info("[MapPack] structure verify: candidates={} judged={} confirmed={} "
                            + "dropped={} unloaded={}",
                    candidates.size(), judged, confirmed, dropped, unloaded);
        }
        return entries;
    }

    private static Scan scan(ScanContext context, int cellX, int cellZ,
                             int minSpacing, int maxResults, int budgetMillis) {
        ChunkGeneratorStructureState state = context.state();
        int minChunkX = StructureScan.cellOrigin(cellX);
        int minChunkZ = StructureScan.cellOrigin(cellZ);
        int maxChunkX = minChunkX + StructureScan.CELL_CHUNKS - 1;
        int maxChunkZ = minChunkZ + StructureScan.CELL_CHUNKS - 1;
        long seed = state.getLevelSeed();
        long started = System.nanoTime();
        long deadline = started + budgetMillis * 1_000_000L;

        List<Candidate> candidates = new ArrayList<>();
        Map<Long, List<BiomeSample>> biomes = new HashMap<>();
        Stats stats = new Stats();
        String gateDetail = null;
        int examined = 0;
        boolean truncated = false;
        // 密集 set 的候选数量是稀疏 set 的倍数（古城 spacing 8 约 81 个/单元，村庄 34 只有 9 个），
        // 按 spacing 从大到小扫，预算截断时牺牲的是密集 set 的一部分，而不是整类稀疏 set。
        List<Holder<StructureSet>> sets = new ArrayList<>(state.possibleStructureSets());
        sets.sort(Comparator.comparingInt(
                (Holder<StructureSet> holder) -> spacingOf(holder.value())).reversed());
        outer:
        for (Holder<StructureSet> holder : sets) {
            stats.totalSets++;
            StructureSet set = holder.value();
            ResourceLocation setId = holder.unwrapKey()
                    .map(ResourceKey::location)
                    .orElse(null);
            if (setId == null) {
                stats.keysSkipped++;
                continue;
            }
            // D93：默认只验收原版结构；mod 的 set 走同一套算法但不保证图标正确
            if (!context.showModdedSets() && !"minecraft".equals(setId.getNamespace())) {
                stats.namespaceSkipped++;
                continue;
            }
            if (set.placement() instanceof ConcentricRingsStructurePlacement rings) {
                stats.rings++;
                stats.considered++;
                // 原版在 ServerLevel 构造时就 ensureStructuresGenerated()，位置缓存在 state 里；
                // 这里通常是 join 一个已完成的 future，代价只在第一次可能要等群系搜索收尾。
                long ringStarted = System.nanoTime();
                List<ChunkPos> positions = state.getRingPositionsFor(rings);
                stats.ringWaitNanos += System.nanoTime() - ringStarted;
                if (positions == null) {
                    // 该 placement 的偏好群系在本世界的群系源里一个都不存在
                    stats.ringMissing++;
                    continue;
                }
                for (ChunkPos pos : positions) {
                    if ((++examined & 15) == 0 && System.nanoTime() > deadline) {
                        truncated = true;
                        break outer;
                    }
                    if (!StructureScan.inRect(pos.x, pos.z,
                            minChunkX, minChunkZ, maxChunkX, maxChunkZ)) {
                        continue;
                    }
                    stats.inRect++;
                    // 环形位置已经过原版群系搜索；群系门仍走同一套（要塞标签 = #is_overworld）
                    stats.placementOk++;
                    if (context.verifyBiomes() && !matchesBiome(context, biomes, pos, set)) {
                        stats.biomeRejected++;
                        if (gateDetail == null) {
                            gateDetail = describeGate(biomes.get(ChunkPos.asLong(pos.x, pos.z)),
                                    pos, set);
                        }
                        continue;
                    }
                    candidates.add(new Candidate(setId, pos, set));
                    if (candidates.size() >= maxResults) {
                        truncated = true;
                        break outer;
                    }
                }
                continue;
            }
            if (!(set.placement() instanceof RandomSpreadStructurePlacement placement)) {
                continue;
            }
            int spacing = placement.spacing();
            if (spacing < minSpacing) {
                stats.spacingSkipped++;
                continue;
            }
            stats.considered++;
            int regionMinX = StructureScan.regionIndex(minChunkX, spacing);
            int regionMaxX = StructureScan.regionIndex(maxChunkX, spacing);
            int regionMinZ = StructureScan.regionIndex(minChunkZ, spacing);
            int regionMaxZ = StructureScan.regionIndex(maxChunkZ, spacing);

            for (int regionX = regionMinX; regionX <= regionMaxX; regionX++) {
                for (int regionZ = regionMinZ; regionZ <= regionMaxZ; regionZ++) {
                    if ((++examined & 15) == 0 && System.nanoTime() > deadline) {
                        truncated = true;
                        break outer;
                    }
                    // 参数是「区块坐标」，内部自己 floorDiv 到区域；这里传区域的锚点区块
                    // （region * spacing 仍是区块坐标）。传区域下标会被二次取整，候选全体挤到原点附近。
                    ChunkPos pos = placement.getPotentialStructureChunk(seed,
                            regionX * spacing, regionZ * spacing);
                    if (!StructureScan.inRect(pos.x, pos.z,
                            minChunkX, minChunkZ, maxChunkX, maxChunkZ)) {
                        continue;
                    }
                    stats.inRect++;
                    if (!placement.isStructureChunk(state, pos.x, pos.z)) {
                        continue;
                    }
                    stats.placementOk++;
                    // 推测级：群系不符的候选块原版最终不会长结构（Structure.java:196-198）
                    if (context.verifyBiomes() && !matchesBiome(context, biomes, pos, set)) {
                        stats.biomeRejected++;
                        if (gateDetail == null) {
                            gateDetail = describeGate(biomes.get(ChunkPos.asLong(pos.x, pos.z)),
                                    pos, set);
                        }
                        continue;
                    }
                    candidates.add(new Candidate(setId, pos, set));
                    if (candidates.size() >= maxResults) {
                        truncated = true;
                        break outer;
                    }
                }
                if (System.nanoTime() > deadline) {
                    truncated = true;
                    break outer;
                }
            }
        }
        if (diagLeft > 0) {
            diagLeft--;
            LOGGER.info("[MapPack] structure scan {}/{}: sets={} spread={} rings={} ringMissing={} "
                            + "spacingSkip={} nsSkip={} considered={} inRect={} placementOk={} "
                            + "biomeRejected={} entries={} truncated={} y={} took={}ms ringWait={}ms",
                    cellX, cellZ, stats.totalSets, stats.totalSets - stats.spacingSkipped
                            - stats.keysSkipped - stats.namespaceSkipped - stats.rings,
                    stats.rings, stats.ringMissing, stats.spacingSkipped, stats.namespaceSkipped,
                    stats.considered, stats.inRect, stats.placementOk, stats.biomeRejected,
                    candidates.size(), truncated, java.util.Arrays.toString(context.sampleYs()),
                    (System.nanoTime() - started) / 1_000_000L, stats.ringWaitNanos / 1_000_000L);
            if (gateDetail != null) {
                LOGGER.info("[MapPack] structure gate sample: {}", gateDetail);
            }
        }
        return new Scan(List.copyOf(candidates), truncated);
    }

    /**
     * 排序键（降序扫描）：随机散布取 spacing（越大越稀疏，先扫）；
     * 要塞位置已在世界加载时算好，只需过滤几百个点，最便宜，排最前；未知 placement 排最后。
     */
    private static int spacingOf(StructureSet set) {
        if (set.placement() instanceof RandomSpreadStructurePlacement placement) {
            return placement.spacing();
        }
        return set.placement() instanceof ConcentricRingsStructurePlacement
                ? Integer.MAX_VALUE : -1;
    }

    /** 采样高度里任一命中该结构集任一变体的群系集合就放行。 */
    private static boolean matchesBiome(ScanContext context, Map<Long, List<BiomeSample>> cache,
                                        ChunkPos pos, StructureSet set) {
        List<BiomeSample> samples = cache.computeIfAbsent(
                ChunkPos.asLong(pos.x, pos.z), ignored -> sampleBiomes(context, pos));
        for (BiomeSample sample : samples) {
            for (StructureSet.StructureSelectionEntry entry : set.structures()) {
                if (entry.structure().value().biomes().contains(sample.biome())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<BiomeSample> sampleBiomes(ScanContext context, ChunkPos pos) {
        int blockX = pos.getMiddleBlockX();
        int blockZ = pos.getMiddleBlockZ();
        List<BiomeSample> samples = new ArrayList<>(context.sampleYs().length);
        for (int y : context.sampleYs()) {
            samples.add(new BiomeSample(y, context.biomeSource().getNoiseBiome(
                    QuartPos.fromBlock(blockX), QuartPos.fromBlock(y), QuartPos.fromBlock(blockZ),
                    context.sampler())));
        }
        return samples;
    }

    /** 一条被拒的候选长什么样：采样到的群系 vs 该集合接受哪些群系。 */
    private static String describeGate(List<BiomeSample> samples, ChunkPos pos, StructureSet set) {
        StringBuilder out = new StringBuilder("chunk=").append(pos.x).append('/').append(pos.z);
        out.append(" samples=");
        for (int i = 0; i < samples.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(samples.get(i).biome().unwrapKey()
                    .map(key -> key.location().toString()).orElse("?"));
            out.append('@').append(samples.get(i).blockY());
        }
        out.append(" variants=");
        for (StructureSet.StructureSelectionEntry entry : set.structures()) {
            Structure structure = entry.structure().value();
            out.append('[').append(entry.structure().unwrapKey()
                    .map(key -> key.location().toString()).orElse("?"));
            out.append(' ').append(structure.biomes().unwrapKey()
                    .map(key -> "#" + key.location()).orElse("direct:" + structure.biomes().size()));
            for (BiomeSample sample : samples) {
                if (structure.biomes().contains(sample.biome())) {
                    out.append(" hit@").append(sample.blockY());
                    break;
                }
            }
            out.append(']');
        }
        return out.toString();
    }

    private static boolean isVanillaBiomeSource(BiomeSource source) {
        return source instanceof MultiNoiseBiomeSource
                || source instanceof TheEndBiomeSource
                || source instanceof FixedBiomeSource;
    }

    private record ScanContext(ChunkGeneratorStructureState state, BiomeSource biomeSource,
                               Climate.Sampler sampler, int[] sampleYs,
                               boolean verifyBiomes, boolean showModdedSets) {
    }

    private record BiomeSample(int blockY, Holder<Biome> biome) {
    }

    /** 一次扫描的分段计数，只用于诊断日志。 */
    private static final class Stats {
        int totalSets;
        int spacingSkipped;
        int keysSkipped;
        int namespaceSkipped;
        int rings;
        int ringMissing;
        int considered;
        int inRect;
        int placementOk;
        int biomeRejected;
        long ringWaitNanos;
    }

    private static boolean allowRequest(ServerPlayer player, int perSecond) {
        long now = System.nanoTime();
        long interval = 1_000_000_000L / Math.max(1, perSecond);
        UUID id = player.getUUID();
        boolean[] allowed = {false};
        LAST_REQUEST.compute(id, (key, previous) -> {
            if (previous == null || now - previous >= interval) {
                allowed[0] = true;
                return now;
            }
            return previous;
        });
        if (LAST_REQUEST.size() > 512) {
            LAST_REQUEST.entrySet().removeIf(entry ->
                    now - entry.getValue() > STALE_REQUEST_NANOS);
        }
        return allowed[0];
    }

    /** 扫描产出的原始候选，结构集留着给主线程做「已加载区块」核实。 */
    private record Candidate(ResourceLocation setId, ChunkPos pos, StructureSet set) {
    }

    private record Scan(List<Candidate> candidates, boolean truncated) {
    }
}
