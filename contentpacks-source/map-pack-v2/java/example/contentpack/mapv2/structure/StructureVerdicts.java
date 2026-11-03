package example.contentpack.mapv2.structure;

import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 区块写盘时的结构判定。
 *
 * <p>区块一写盘，它里面的结构起点就已经定下来了（起点在 {@code STRUCTURE_STARTS} 阶段算完，
 * 之后不再变）。所以「某个候选结构到底长没长出来」只需要在保存那一刻看一眼：
 * 这个区块是不是某结构集的落点（放置数学），是的话再看 {@code getAllStarts()} 里有没有它。
 * 结果按区块存起来，之后地图查询直接查表——不用读盘、不用等区块被加载，也不再反复重扫。
 *
 * <p>每条记录只覆盖「这个区块是落点」的结构集；查询到没判过的结构集返回 {@code null}
 * 并退回候选级，所以跳过的那类 placement 不会误伤。缓存只活在本次服务端运行期，
 * 重启后随区块再次写盘重建。
 */
public final class StructureVerdicts {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** 每个维度最多记这么多区块；超了清空重建（判定只是显示增益，丢了退回候选级）。 */
    private static final int MAX_CHUNKS_PER_DIMENSION = 1 << 15;

    /** 维度 → 区块长坐标 → (结构集 id → 这个区块里是否真长出了该结构)。 */
    private static final Map<ResourceLocation, Map<Long, Map<ResourceLocation, Boolean>>> VERDICTS =
            new ConcurrentHashMap<>();

    private static int diagJudged = 20;

    private StructureVerdicts() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener((ChunkDataEvent.Save event) -> {
            if (event.getLevel() instanceof ServerLevel level) {
                judge(level, event.getChunk());
            }
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> VERDICTS.clear());
    }

    /** {@code null} = 没判过，或这个区块不是该结构集的落点；否则值就是「长出来了 / 确定没有」。 */
    public static Map<ResourceLocation, Boolean> get(ResourceLocation dimension, ChunkPos pos) {
        Map<Long, Map<ResourceLocation, Boolean>> chunks = VERDICTS.get(dimension);
        return chunks == null ? null : chunks.get(pos.toLong());
    }

    /** 主线程调用（区块保存在主线程）。 */
    public static void judge(ServerLevel level, ChunkAccess chunk) {
        if (!StructureServerConfig.enabled()
                || !chunk.getPersistedStatus().isOrAfter(ChunkStatus.STRUCTURE_STARTS)) {
            return;
        }
        ResourceLocation dimension = level.dimension().location();
        ChunkPos pos = chunk.getPos();
        Map<Long, Map<ResourceLocation, Boolean>> chunks = VERDICTS.get(dimension);
        if (chunks != null && chunks.containsKey(pos.toLong())) {
            return;
        }
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        Map<ResourceLocation, Boolean> considered = null;
        for (Holder<StructureSet> holder : state.possibleStructureSets()) {
            StructureSet set = holder.value();
            ResourceLocation id = holder.unwrapKey().map(ResourceKey::location).orElse(null);
            if (id == null || !isCandidate(set, state, pos)) {
                continue;
            }
            if (considered == null) {
                considered = new HashMap<>(4);
            }
            considered.put(id, hasStart(chunk, set));
        }
        if (considered == null) {
            // 没有任何结构集把这个区块当落点：地图查询也不会在这里出条目，不必记
            return;
        }
        chunks = VERDICTS.computeIfAbsent(dimension, ignored -> new HashMap<>());
        if (chunks.size() >= MAX_CHUNKS_PER_DIMENSION) {
            chunks.clear();
        }
        Map<ResourceLocation, Boolean> verdict = Map.copyOf(considered);
        chunks.put(pos.toLong(), verdict);
        if (diagJudged > 0) {
            diagJudged--;
            int present = 0;
            for (boolean value : verdict.values()) {
                present += value ? 1 : 0;
            }
            LOGGER.info("[MapPack] structure judged {}/{}: candidates={} present={}",
                    dimension, pos, verdict.size(), present);
        }
    }

    /** 这个区块是不是该结构集的放置落点（与查询扫描用的是同一套数学）。 */
    private static boolean isCandidate(StructureSet set, ChunkGeneratorStructureState state,
                                       ChunkPos pos) {
        if (set.placement() instanceof RandomSpreadStructurePlacement placement) {
            // 参数是区块坐标，方法内部再 floorDiv 到区域；传本区块即可判断它是不是落点
            return placement.getPotentialStructureChunk(state.getLevelSeed(), pos.x, pos.z)
                    .equals(pos)
                    && placement.isStructureChunk(state, pos.x, pos.z);
        }
        // 要塞（ConcentricRings）不在这里判：位置 future 可能还没算完，主线程 join 会卡 tick；
        // 查询侧「区块恰好已加载」的回退路径仍会核实它。
        return false;
    }

    /** 区块里的结构起点是否属于这个集合的任一变体（注册表实例是单例，按引用比）。 */
    static boolean hasStart(ChunkAccess chunk, StructureSet set) {
        for (StructureSet.StructureSelectionEntry variant : set.structures()) {
            StructureStart start = chunk.getAllStarts().get(variant.structure().value());
            if (start != null && start.isValid()) {
                return true;
            }
        }
        return false;
    }
}
