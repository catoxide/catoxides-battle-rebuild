package example.contentpack.sablev2.map.client;

import com.catoxide.catoxidesbattlerebuild.core.sable.ISubLevelService;
import com.catoxide.catoxidesbattlerebuild.core.sable.SubLevelInfo;
import com.catoxide.catoxidesbattlerebuild.core.sable.SubLevelServiceRegistry;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import example.contentpack.sablev2.map.SableMapConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
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
 * 船体图解贴图的队列与生命周期。
 *
 * <p>tick 侧只做「枚举 + 更新包围盒」；真正的 GPU 渲染由叠加层按可见性排队，
 * 在 {@code RenderLevelStageEvent.AFTER_LEVEL} 每帧处理少量条目 —— 地图没打开时零开销。
 */
final class SubLevelGpuCapture {

    /** 一帧最多重渲几艘船；其余留到下一帧，避免水面/小地图同帧多个大 FBO。 */
    private static final int RENDERS_PER_FRAME = 1;

    private final Map<UUID, SubLevelGpuEntry> entries = new LinkedHashMap<>();
    private Object lastLevel;
    private long tickCounter;

    void tick() {
        if (!SableMapConfig.enabled()) {
            if (!this.entries.isEmpty()) {
                this.reset();
            }
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

        int maxTexture = SableMapConfig.maxTexture();
        Set<UUID> live = new HashSet<>();
        List<SubLevelInfo> subLevels = service.getSubLevels(level);
        for (SubLevelInfo info : subLevels) {
            live.add(info.id());
        }

        Iterator<Map.Entry<UUID, SubLevelGpuEntry>> iterator = this.entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, SubLevelGpuEntry> entry = iterator.next();
            if (!live.contains(entry.getKey())) {
                entry.getValue().release();
                iterator.remove();
            }
        }

        for (SubLevelInfo info : subLevels) {
            SubLevelGpuEntry entry = this.entries.get(info.id());
            if (entry == null) {
                if (info.bounds().isEmpty()) {
                    continue;
                }
                this.entries.put(info.id(), new SubLevelGpuEntry(info, maxTexture));
            } else {
                entry.update(info, maxTexture);
            }
        }
        this.tickCounter++;
    }

    /** 叠加层画到某艘船时调用：按需排队重渲。 */
    void requestRender(SubLevelGpuEntry entry) {
        if (entry != null) {
            entry.request(this.tickCounter);
        }
    }

    /** 渲染线程：处理本帧的渲染队列。 */
    void renderPending(float partialTick) {
        if (!SableMapConfig.enabled() || this.entries.isEmpty()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        int budget = RENDERS_PER_FRAME;
        for (SubLevelGpuEntry entry : this.entries.values()) {
            if (budget <= 0) {
                break;
            }
            if (!entry.isRequested()) {
                continue;
            }
            ClientSubLevel sub = SableClientSubLevels.get(level, entry.id());
            if (sub == null) {
                entry.clearRequest();
                continue;
            }
            entry.render(level, sub, this.tickCounter, partialTick);
            budget--;
        }
    }

    /** 局部 plot 坐标的方块变化：只重渲所属 sub-level。 */
    void markDirty(Level level, BlockPos pos) {
        ISubLevelService service = SubLevelServiceRegistry.get();
        if (service == null || level == null || pos == null) {
            return;
        }
        SubLevelInfo sub = service.getSubLevelContainingLocal(level, Vec3.atCenterOf(pos));
        if (sub == null) {
            return;
        }
        SubLevelGpuEntry entry = this.entries.get(sub.id());
        if (entry != null) {
            entry.markDirty();
        }
    }

    Collection<SubLevelGpuEntry> entries() {
        return Collections.unmodifiableCollection(this.entries.values());
    }

    void reset() {
        for (SubLevelGpuEntry entry : this.entries.values()) {
            entry.release();
        }
        this.entries.clear();
        this.lastLevel = null;
        this.tickCounter = 0;
    }
}
