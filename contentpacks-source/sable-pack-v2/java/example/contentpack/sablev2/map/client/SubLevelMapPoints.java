package example.contentpack.sablev2.map.client;

import com.catoxide.catoxidesbattlerebuild.core.map.MapPointResolver;
import com.catoxide.catoxidesbattlerebuild.core.sable.ISubLevelService;
import com.catoxide.catoxidesbattlerebuild.core.sable.SubLevelInfo;
import com.catoxide.catoxidesbattlerebuild.core.sable.SubLevelServiceRegistry;
import example.contentpack.sablev2.map.SubLevelColumns;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * map-pack 的坐标解析器：把「船体局部坐标 / 甲板高度 / 标点锚点」接进普通地图。
 *
 * <p>锚点写在标点的 {@code data} CompoundTag 里（随标点落盘并同步）：
 * {@code sable_map:sub} + {@code sable_map:x/y/z}。渲染时用最新姿态重新解析，
 * 所以船开走后标点仍固定在甲板原位置；其他客户端也一样。
 */
final class SubLevelMapPoints implements MapPointResolver {

    private static final String KEY_SUB = "sable_map:sub";
    private static final String KEY_X = "sable_map:x";
    private static final String KEY_Y = "sable_map:y";
    private static final String KEY_Z = "sable_map:z";

    @Override
    public @Nullable Vec3 toWorld(Level level, Vec3 raw, @Nullable HitResult hit, CompoundTag anchorData) {
        ISubLevelService service = SubLevelServiceRegistry.get();
        if (service == null || level == null || raw == null) {
            return null;
        }

        SubLevelInfo sub;
        Vec3 local;
        if (hit instanceof BlockHitResult) {
            // Sable 对方块射线的返回是 plot 局部坐标；直接按局部坐标找船
            sub = service.getSubLevelContainingLocal(level, raw);
            if (sub == null) {
                return null;
            }
            local = raw;
        } else {
            // 世界坐标（实体/地图点击/空手标记）：反算到某艘船的局部空间
            sub = findWorld(service, level, raw);
            if (sub == null) {
                return null;
            }
            local = service.worldToLocal(sub, raw);
        }

        if (anchorData != null) {
            anchorData.putString(KEY_SUB, sub.id().toString());
            anchorData.putDouble(KEY_X, local.x);
            anchorData.putDouble(KEY_Y, local.y);
            anchorData.putDouble(KEY_Z, local.z);
        }
        return service.localToWorld(sub, local);
    }

    @Override
    public @Nullable Vec3 resolve(Level level, double x, double y, double z,
                                  CompoundTag anchorData, float partialTick) {
        if (anchorData == null || !anchorData.contains(KEY_SUB)) {
            return null;
        }
        ISubLevelService service = SubLevelServiceRegistry.get();
        if (service == null || level == null) {
            return null;
        }

        UUID id;
        try {
            id = UUID.fromString(anchorData.getString(KEY_SUB));
        } catch (IllegalArgumentException e) {
            return null;
        }
        SubLevelInfo sub = service.getSubLevel(level, id);
        if (sub == null) {
            return null; // 船未同步/已移除：调用方回退存储的世界坐标
        }
        Vec3 local = new Vec3(
                anchorData.getDouble(KEY_X),
                anchorData.getDouble(KEY_Y),
                anchorData.getDouble(KEY_Z));
        return service.localToWorld(sub, local);
    }

    /**
     * 在 sub-level 局部空间里步进射线。普通 level.clip 看不到船体方块，
     * 所以标记需要在世界射线转换到局部后自己找最近的非空气方块。
     */
    @Override
    public @Nullable HitResult raycast(Level level, Vec3 from, Vec3 to, Entity source) {
        ISubLevelService service = SubLevelServiceRegistry.get();
        if (service == null || level == null || from == null || to == null) {
            return null;
        }
        SubLevelInfo bestSub = null;
        Vec3 bestLocal = null;
        BlockPos bestPos = null;
        double bestWorldDistance = Double.MAX_VALUE;

        double worldLength = from.distanceTo(to);
        int steps = (int) Math.min(4096, Math.max(8, worldLength * 4));
        for (SubLevelInfo sub : service.getSubLevels(level)) {
            if (sub.bounds().isEmpty()) {
                continue;
            }
            Vec3 localFrom = service.worldToLocal(sub, from);
            Vec3 localTo = service.worldToLocal(sub, to);
            for (int i = 1; i <= steps; i++) {
                double t = i / (double) steps;
                Vec3 local = localFrom.lerp(localTo, t);
                if (!sub.bounds().contains(local.x, local.y, local.z)) {
                    continue;
                }
                BlockPos pos = BlockPos.containing(local);
                if (service.getBlockState(level, sub, pos.getX(), pos.getY(), pos.getZ()).isAir()) {
                    continue;
                }
                Vec3 hitLocal = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                double worldDistance = from.distanceTo(service.localToWorld(sub, hitLocal));
                if (worldDistance < bestWorldDistance) {
                    bestWorldDistance = worldDistance;
                    bestSub = sub;
                    bestLocal = hitLocal;
                    bestPos = pos;
                }
                break; // 这个 sub-level 里第一个命中的方块
            }
        }
        if (bestLocal == null) {
            return null;
        }
        return new BlockHitResult(bestLocal, Direction.UP, bestPos, false);
    }

    @Override
    public @Nullable Double surfaceY(Level level, int x, int z) {
        ColumnHit hit = topHit(level, x + 0.5, z + 0.5);
        return hit == null ? null : hit.world().y;
    }

    @Override
    public @Nullable Vec3 surfaceAnchor(Level level, double x, double z, CompoundTag anchorData) {
        ColumnHit hit = topHit(level, x, z);
        if (hit == null) {
            return null;
        }
        if (anchorData != null) {
            anchorData.putString(KEY_SUB, hit.sub().id().toString());
            anchorData.putDouble(KEY_X, hit.local().x);
            anchorData.putDouble(KEY_Y, hit.local().y);
            anchorData.putDouble(KEY_Z, hit.local().z);
        }
        return hit.world();
    }

    /**
     * 该世界列上最高那艘船的锚点。
     *
     * <p>船体可能倾斜/侧倾，所以用世界竖直线与局部包围盒求交定位列，而不是在
     * {@code pose.posY()} 处取一个探针点；包围盒内恰好是空列时退回求交点本身，
     * 保证「点在船体图像上」不会因为该列没方块而跑去地表。
     */
    private static @Nullable ColumnHit topHit(Level level, double x, double z) {
        ISubLevelService service = SubLevelServiceRegistry.get();
        if (service == null || level == null) {
            return null;
        }
        ColumnHit best = null;
        List<SubLevelInfo> subs = service.getSubLevels(level);
        for (SubLevelInfo sub : subs) {
            Vec3 point = SubLevelColumns.topPoint(service, sub, x, z);
            if (point == null) {
                continue;
            }
            int blockX = (int) Math.floor(point.x);
            int blockZ = (int) Math.floor(point.z);
            int top = service.topBlockY(level, sub, blockX, blockZ);
            Vec3 local = top == Integer.MIN_VALUE
                    ? point
                    : new Vec3(blockX + 0.5, top + 1.0, blockZ + 0.5);
            Vec3 world = service.localToWorld(sub, local);
            if (best == null || world.y > best.world().y) {
                best = new ColumnHit(sub, local, world);
            }
        }
        return best;
    }

    private record ColumnHit(SubLevelInfo sub, Vec3 local, Vec3 world) {
    }

    @Override
    public boolean isDetached(Level level, BlockPos pos) {
        ISubLevelService service = SubLevelServiceRegistry.get();
        if (service == null || level == null || pos == null) {
            return false;
        }
        return service.getSubLevelContainingLocal(level, Vec3.atCenterOf(pos)) != null;
    }

    private static @Nullable SubLevelInfo findWorld(ISubLevelService service, Level level, Vec3 world) {
        for (SubLevelInfo sub : service.getSubLevels(level)) {
            if (sub.bounds().isEmpty()) {
                continue;
            }
            Vec3 local = service.worldToLocal(sub, world);
            if (sub.bounds().contains(local.x, local.y, local.z)) {
                return sub;
            }
        }
        return null;
    }
}
