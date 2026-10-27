package example.contentpack.sablebase.map.client;

import com.catoxide.catoxidesbattlerebuild.core.map.MapRenderContext;
import com.catoxide.catoxidesbattlerebuild.core.sable.ISubLevelService;
import com.catoxide.catoxidesbattlerebuild.core.sable.SubLevelInfo;
import net.minecraft.world.phys.Vec3;

/**
 * 把 sub-level 的局部像素平面映射到地图屏幕。
 *
 * <p>取局部包围盒的中心高度作为参考平面：对水平船体完全准确；对俯仰/横滚的船体，
 * 用完整 3D 姿态的 X/Z 轴投影得到仿射矩阵（不是只取 yaw），所以旋转、缩放、
 * 倾斜导致的斜切都能跟上。top-down 快照本身已经压扁了 y。
 */
public record SubLevelProjection(
        double originScreenX,
        double originScreenZ,
        double ux,
        double uz,
        double vx,
        double vz
) {

    public static SubLevelProjection of(ISubLevelService service, SubLevelInfo sub,
                                        MapRenderContext context, int step) {
        double refY = (sub.bounds().minY() + sub.bounds().maxY()) / 2.0;
        Vec3 originLocal = new Vec3(sub.bounds().minX(), refY, sub.bounds().minZ());
        Vec3 originWorld = service.localToWorld(sub, originLocal);
        Vec3 uWorld = service.localToWorld(sub, originLocal.add(1, 0, 0)).subtract(originWorld);
        Vec3 vWorld = service.localToWorld(sub, originLocal.add(0, 0, 1)).subtract(originWorld);

        double pixelsPerBlock = context.pixelsPerBlock() * step;
        return new SubLevelProjection(
                context.screenX(originWorld.x),
                context.screenZ(originWorld.z),
                uWorld.x * pixelsPerBlock,
                uWorld.z * pixelsPerBlock,
                vWorld.x * pixelsPerBlock,
                vWorld.z * pixelsPerBlock
        );
    }

    double screenX(double pixelX, double pixelZ) {
        return this.originScreenX + this.ux * pixelX + this.vx * pixelZ;
    }

    double screenZ(double pixelX, double pixelZ) {
        return this.originScreenZ + this.uz * pixelX + this.vz * pixelZ;
    }

    /**
     * 实体朝向：把船体局部 yaw 转成世界 yaw。
     * <p>实体在 sub-level 上的 {@code getYRot()} 是船体局部角，直接当世界角会让
     * 地图上的箭头在转弯后指向错误方向。
     */
    public static float worldYaw(ISubLevelService service, SubLevelInfo sub, double localY, float localYaw) {
        double radians = Math.toRadians(localYaw);
        Vec3 direction = new Vec3(-Math.sin(radians), 0.0, Math.cos(radians));
        Vec3 base = new Vec3(sub.bounds().minX(), localY, sub.bounds().minZ());
        Vec3 from = service.localToWorld(sub, base);
        Vec3 to = service.localToWorld(sub, base.add(direction));
        Vec3 worldDirection = to.subtract(from);
        return (float) Math.toDegrees(Math.atan2(-worldDirection.x, worldDirection.z));
    }
}
