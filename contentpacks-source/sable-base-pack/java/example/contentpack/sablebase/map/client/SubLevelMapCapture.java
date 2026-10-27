package example.contentpack.sablebase.map.client;

import com.catoxide.catoxidesbattlerebuild.core.sable.ISubLevelService;
import com.catoxide.catoxidesbattlerebuild.core.sable.SubLevelInfo;
import com.catoxide.catoxidesbattlerebuild.core.sable.SubLevelServiceRegistry;
import example.contentpack.sablebase.map.SableMapConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * sub-level 俯视快照的采集与生命周期。
 *
 * <p>只读 sable-base 提供的 core {@link ISubLevelService}：枚举已同步的 sub-level，
 * 逐列取顶面方块，写进每个 sub-level 自己的 {@link SubLevelMapEntry}。
 * 每 tick 只处理少量 16×16 chunk，避免大船一次性卡住主线程。
 */
final class SubLevelMapCapture {

    private static final int CHUNK_BUDGET_PER_TICK = 8;

    private final Map<UUID, SubLevelMapEntry> entries = new LinkedHashMap<>();
    private Object lastLevel;
    private long tickCounter;

    void tick() {
        if (!SableMapConfig.enabled()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            this.reset();
            return;
        }

        ISubLevelService service = SubLevelServiceRegistry.get();
        if (service == null) {
            return;
        }

        if (level != this.lastLevel) {
            this.reset();
            this.lastLevel = level;
        }

        Set<UUID> live = new HashSet<>();
        List<SubLevelInfo> subLevels = service.getSubLevels(level);
        for (SubLevelInfo info : subLevels) {
            live.add(info.id());
        }

        Iterator<Map.Entry<UUID, SubLevelMapEntry>> iterator = this.entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, SubLevelMapEntry> entry = iterator.next();
            if (!live.contains(entry.getKey())) {
                entry.getValue().release(minecraft);
                iterator.remove();
            }
        }

        int maxTexture = SableMapConfig.maxTexture();
        for (SubLevelInfo info : subLevels) {
            SubLevelMapEntry entry = this.entries.get(info.id());
            if (entry == null) {
                if (info.bounds().isEmpty()) {
                    continue;
                }
                entry = new SubLevelMapEntry(info);
                this.entries.put(info.id(), entry);
            } else {
                entry.updateInfo(info);
            }
            entry.ensureImage(minecraft, maxTexture);
            entry.maybeRefresh(this.tickCounter);
        }

        int budget = CHUNK_BUDGET_PER_TICK;
        for (SubLevelMapEntry entry : this.entries.values()) {
            if (budget <= 0) {
                break;
            }
            budget = entry.process(service, level, budget);
        }
        for (SubLevelMapEntry entry : this.entries.values()) {
            entry.uploadIfDirty();
        }
        this.tickCounter++;
    }

    /** 局部 plot 坐标的方块变化：只重采所属 sub-level 的对应 chunk。 */
    void markDirty(Level level, BlockPos pos) {
        ISubLevelService service = SubLevelServiceRegistry.get();
        if (service == null || level == null || pos == null) {
            return;
        }
        SubLevelInfo sub = service.getSubLevelContainingLocal(level, Vec3.atCenterOf(pos));
        if (sub == null) {
            return;
        }
        SubLevelMapEntry entry = this.entries.get(sub.id());
        if (entry != null) {
            entry.queueChunk(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
        }
    }

    Collection<SubLevelMapEntry> entries() {
        return Collections.unmodifiableCollection(this.entries.values());
    }

    void reset() {
        Minecraft minecraft = Minecraft.getInstance();
        for (SubLevelMapEntry entry : this.entries.values()) {
            entry.release(minecraft);
        }
        this.entries.clear();
        this.lastLevel = null;
        this.tickCounter = 0;
    }
}
