package example.contentpack.sablev2;

import com.catoxide.catoxidesbattlerebuild.core.sable.ISubLevelService;
import com.catoxide.catoxidesbattlerebuild.core.sable.PoseData;
import com.catoxide.catoxidesbattlerebuild.core.sable.SubLevelBounds;
import com.catoxide.catoxidesbattlerebuild.core.sable.SubLevelInfo;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * sable sub-level 服务实现（防腐层适配器）。
 *
 * <p>本类是<b>唯一直接接触 sable API</b> 的地方（客户端子类访问在
 * {@code map.client.SableClientSubLevels}）：追踪 sub-level 增删、把 sable 的
 * {@code SubLevel} 转换为主 mod 的 {@link SubLevelInfo}，并为客户端地图提供只读的
 * 方块/范围查询。坐标转换数学复用 {@link ISubLevelService} 的 default 实现。
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

    // ========== ISubLevelService：查询 ==========

    @Override
    public @Nullable SubLevelInfo getSubLevelAt(Level level, BlockPos worldPos) {
        if (level == null) {
            return null;
        }
        double wx = worldPos.getX() + 0.5;
        double wy = worldPos.getY() + 0.5;
        double wz = worldPos.getZ() + 0.5;
        for (SubLevelInfo info : getSubLevels(level)) {
            if (info.bounds().isEmpty()) {
                continue;
            }
            Vec3 local = worldToLocal(info, new Vec3(wx, wy, wz));
            if (info.bounds().contains(local.x, local.y, local.z)) {
                return info;
            }
        }
        return null;
    }

    @Override
    public List<SubLevelInfo> getSubLevels(Level level) {
        if (level == null) {
            return List.of();
        }
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return List.of();
        }
        List<SubLevelInfo> result = new ArrayList<>();
        for (SubLevel sub : container.getAllSubLevels()) {
            if (sub.getLevel() == level && sub.getUniqueId() != null) {
                result.add(toInfo(sub));
            }
        }
        return result;
    }

    @Override
    public @Nullable SubLevelInfo getSubLevel(Level level, UUID id) {
        if (level == null || id == null) {
            return null;
        }
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        SubLevel sub = container.getSubLevel(id);
        return sub != null && sub.getLevel() == level && sub.getUniqueId() != null
                ? toInfo(sub) : null;
    }

    @Override
    public @Nullable SubLevelInfo getSubLevelContainingLocal(Level level, Vec3 localPos) {
        if (level == null || localPos == null) {
            return null;
        }
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        int chunkX = Mth.floor(localPos.x) >> 4;
        int chunkZ = Mth.floor(localPos.z) >> 4;
        LevelPlot plot = container.getPlot(chunkX, chunkZ);
        if (plot == null) {
            return null;
        }
        SubLevel sub = plot.getSubLevel();
        return sub != null && sub.getLevel() == level && sub.getUniqueId() != null
                ? toInfo(sub) : null;
    }

    @Override
    public BlockState getBlockState(Level level, SubLevelInfo sub, int x, int y, int z) {
        SubLevelBounds bounds = sub == null ? SubLevelBounds.EMPTY : sub.bounds();
        if (bounds.isEmpty()
                || x < bounds.minX() || x > bounds.maxX()
                || y < bounds.minY() || y > bounds.maxY()
                || z < bounds.minZ() || z > bounds.maxZ()) {
            return Blocks.AIR.defaultBlockState();
        }
        LevelChunk chunk = chunkOf(level, sub, x, z);
        return chunk == null ? Blocks.AIR.defaultBlockState()
                : chunk.getBlockState(new BlockPos(x, y, z));
    }

    @Override
    public int topBlockY(Level level, SubLevelInfo sub, int x, int z) {
        SubLevelBounds bounds = sub == null ? SubLevelBounds.EMPTY : sub.bounds();
        if (bounds.isEmpty() || !bounds.containsColumn(x + 0.5, z + 0.5)) {
            return Integer.MIN_VALUE;
        }
        LevelChunk chunk = chunkOf(level, sub, x, z);
        if (chunk == null) {
            return Integer.MIN_VALUE;
        }

        int minY = Math.max(bounds.minY(), chunk.getMinBuildHeight());
        int maxY = Math.min(bounds.maxY(), chunk.getMaxBuildHeight() - 1);
        int height = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        // 客户端的 plot chunk 高度图来自网络包；若缺失/失真（<= minY 或超出包围盒），
        // 从包围盒顶向下扫，宁可慢一点也不能把整列判成空。
        int y = height <= minY || height > maxY + 1 ? maxY : height - 1;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int probe = y; probe >= minY; probe--) {
            if (!chunk.getBlockState(pos.set(x, probe, z)).isAir()) {
                return probe;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** 取局部列所在的已加载 plot chunk；越界/未加载返回 null。 */
    private static @Nullable LevelChunk chunkOf(Level level, SubLevelInfo sub, int x, int z) {
        if (level == null || sub == null) {
            return null;
        }
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        SubLevel subLevel = container.getSubLevel(sub.id());
        if (subLevel == null || subLevel.getLevel() != level) {
            return null;
        }
        LevelPlot plot = subLevel.getPlot();
        if (plot == null) {
            return null;
        }
        ChunkPos local = plot.toLocal(new ChunkPos(x >> 4, z >> 4));
        return plot.getChunk(local);
    }

    // ========== 转换 ==========

    /** 把 sable 的 SubLevel 转换为防腐层值类型（提取姿态 + 局部包围盒纯数据） */
    private SubLevelInfo toInfo(SubLevel sub) {
        Pose3dc pose = sub.logicalPose();
        PoseData pd = new PoseData(
                pose.position().x(), pose.position().y(), pose.position().z(),
                pose.orientation().x(), pose.orientation().y(), pose.orientation().z(), pose.orientation().w(),
                pose.scale().x(), pose.scale().y(), pose.scale().z(),
                pose.rotationPoint().x(), pose.rotationPoint().y(), pose.rotationPoint().z()
        );

        BoundingBox3ic box = sub.getPlot().getBoundingBox();
        SubLevelBounds bounds = box == null
                ? SubLevelBounds.EMPTY
                : new SubLevelBounds(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
        return new SubLevelInfo(sub.getUniqueId(), sub.getName(), pd, bounds);
    }
}
