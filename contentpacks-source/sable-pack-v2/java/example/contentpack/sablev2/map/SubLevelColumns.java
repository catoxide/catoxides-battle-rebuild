package example.contentpack.sablev2.map;

import com.catoxide.catoxidesbattlerebuild.core.sable.ISubLevelService;
import com.catoxide.catoxidesbattlerebuild.core.sable.SubLevelBounds;
import com.catoxide.catoxidesbattlerebuild.core.sable.SubLevelInfo;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 世界竖直列 ↔ sub-level 局部包围盒求交（纯数学，不碰 sable 类型）。
 *
 * <p>地图点击只知道世界 (x,z)，不知道船体在哪一层。用「世界 +Y 直线变换到局部空间后
 * 与包围盒求交」代替「在世界 y = pose.posY() 处取一个探针点」：后者在船体倾斜/侧倾时
 * 会落到包围盒外，导致整列被判成没有船。
 */
public final class SubLevelColumns {

    private SubLevelColumns() {
    }

    /**
     * 世界 (x,z) 处的竖直线上、该 sub-level 顶面上的局部点；该列不穿过船体返回 {@code null}。
     *
     * <p>局部方向 = 世界 +1 格 Y 在局部空间的位移（含旋转与缩放），交点取世界 Y 较大的一端。
     */
    public static @Nullable Vec3 topPoint(ISubLevelService service, SubLevelInfo sub,
                                          double worldX, double worldZ) {
        SubLevelBounds bounds = sub.bounds();
        if (bounds.isEmpty()) {
            return null;
        }

        Vec3 origin = service.worldToLocal(sub, new Vec3(worldX, 0.0, worldZ));
        Vec3 direction = service.worldToLocal(sub, new Vec3(worldX, 1.0, worldZ)).subtract(origin);

        double[] o = {origin.x, origin.y, origin.z};
        double[] d = {direction.x, direction.y, direction.z};
        double[] lo = {bounds.minX(), bounds.minY(), bounds.minZ()};
        double[] hi = {bounds.maxX() + 1.0, bounds.maxY() + 1.0, bounds.maxZ() + 1.0};

        double tMin = -Double.MAX_VALUE;
        double tMax = Double.MAX_VALUE;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(d[axis]) < 1.0e-9) {
                if (o[axis] < lo[axis] || o[axis] > hi[axis]) {
                    return null;
                }
                continue;
            }
            double enter = (lo[axis] - o[axis]) / d[axis];
            double exit = (hi[axis] - o[axis]) / d[axis];
            if (enter > exit) {
                double swap = enter;
                enter = exit;
                exit = swap;
            }
            tMin = Math.max(tMin, enter);
            tMax = Math.min(tMax, exit);
            if (tMin > tMax) {
                return null;
            }
        }

        double t = Double.isFinite(tMax) ? tMax : tMin;
        return Double.isFinite(t) ? origin.add(direction.scale(t)) : null;
    }
}
