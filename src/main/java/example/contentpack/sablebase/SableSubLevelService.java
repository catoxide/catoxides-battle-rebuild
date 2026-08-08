package example.contentpack.sablebase;

import com.catoxide.catoxidesbattlerebuild.core.sable.ISubLevelService;
import com.catoxide.catoxidesbattlerebuild.core.sable.PoseData;
import com.catoxide.catoxidesbattlerebuild.core.sable.SubLevelInfo;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * sable sub-level 服务实现（防腐层适配器）
 * <p>本类是<b>唯一直接接触 sable API</b> 的地方：
 * 追踪 sub-level 增删、把 sable 的 {@code SubLevel} 转换为主 mod 的 {@link SubLevelInfo}。
 * 坐标转换数学复用 {@link ISubLevelService} 的 default 实现（基于 PoseData 纯 JOML）。
 *
 * <p>sable API 升级时只需改本适配器（重新打包 sable-base contentpack），
 * 主 mod 与其他内容包零改动。
 */
public class SableSubLevelService implements ISubLevelService, SubLevelObserver {

    private static final String TAG = "SableSubLevelService";

    private final Map<UUID, SubLevel> subLevels = new ConcurrentHashMap<>();
    private boolean attached = false;

    /** 绑定到服务端世界（LevelEvent.Load 时调用） */
    public void attach(ServerLevel level) {
        if (attached) {
            return;
        }
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container != null) {
            container.addObserver(this);
            attached = true;
            LogManager.serverInfo(TAG, "Attached to level {}", level.dimension().location());
        }
    }

    public boolean isAttached() {
        return attached;
    }

    // ========== SubLevelObserver ==========

    @Override
    public void onSubLevelAdded(SubLevel subLevel) {
        subLevels.put(subLevel.getUniqueId(), subLevel);
        LogManager.serverDebug(TAG, "sub-level added: {} ({})", subLevel.getUniqueId(), subLevel.getName());
    }

    @Override
    public void onSubLevelRemoved(SubLevel subLevel, SubLevelRemovalReason reason) {
        subLevels.remove(subLevel.getUniqueId());
        LogManager.serverDebug(TAG, "sub-level removed: {} ({})", subLevel.getUniqueId(), subLevel.getName());
    }

    @Override
    public void tick(SubLevelContainer container) {
        // 无每 tick 逻辑
    }

    // ========== ISubLevelService ==========

    @Override
    public SubLevelInfo getSubLevelAt(Level level, BlockPos worldPos) {
        if (subLevels.isEmpty()) {
            return null;
        }
        double wx = worldPos.getX() + 0.5;
        double wy = worldPos.getY() + 0.5;
        double wz = worldPos.getZ() + 0.5;
        for (SubLevel sub : subLevels.values()) {
            if (sub.getLevel() != level) {
                continue;
            }
            try {
                SubLevelInfo info = toInfo(sub);
                // 世界坐标 → plot grid 局部坐标（复用防腐层 default 数学）
                Vec3 local = worldToLocal(info, new Vec3(wx, wy, wz));
                int chunkX = Mth.floor(local.x / 16.0);
                int chunkZ = Mth.floor(local.z / 16.0);
                var plot = sub.getPlot();
                ChunkPos min = plot.getChunkMin();
                ChunkPos max = plot.getChunkMax();
                if (chunkX >= min.x && chunkX <= max.x && chunkZ >= min.z && chunkZ <= max.z) {
                    return info;
                }
            } catch (Exception e) {
                LogManager.serverDebug(TAG, "Query failed for sub-level {}: {}", sub.getUniqueId(), e.getMessage());
            }
        }
        return null;
    }

    /** 把 sable 的 SubLevel 转换为防腐层值类型（提取姿态纯数据） */
    private SubLevelInfo toInfo(SubLevel sub) {
        Pose3dc pose = sub.logicalPose();
        PoseData pd = new PoseData(
                pose.position().x(), pose.position().y(), pose.position().z(),
                pose.orientation().x(), pose.orientation().y(), pose.orientation().z(), pose.orientation().w(),
                pose.scale().x(), pose.scale().y(), pose.scale().z(),
                pose.rotationPoint().x(), pose.rotationPoint().y(), pose.rotationPoint().z()
        );
        return new SubLevelInfo(sub.getUniqueId(), sub.getName(), pd);
    }
}
