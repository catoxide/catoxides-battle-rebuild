package example.contentpack.sablev2.map.client;

import com.catoxide.catoxidesbattlerebuild.core.map.MapRenderContext;
import com.catoxide.catoxidesbattlerebuild.core.sable.ISubLevelService;
import com.catoxide.catoxidesbattlerebuild.core.sable.SubLevelInfo;
import example.contentpack.sablev2.map.TopDownView;
import net.minecraft.world.phys.Vec3;

/**
 * 把 sub-level 的俯视纹理平面映射到地图屏幕。
 *
 * <p>纹理是「世界正俯视」离屏图：屏幕右 = 世界 +X，屏幕下 = 世界 +Z，
 * 船体的朝向/倾斜已经烘进纹理。所以这里的仿射是与世界轴对齐的，
 * 不含旋转项（{@code uz = vx = 0}）。
 *
 * <p>视口是外接球的正方形，边长 {@code diameter}：纹理左上角对应
 * {@code (centerX - diameter/2, centerZ - diameter/2)}，每纹素覆盖
 * {@code stepX × stepZ} 格（{@code stepX = diameter / 纹理宽}）。
 */
public record SubLevelProjection(
        double originScreenX,
        double originScreenZ,
        double ux,
        double uz,
        double vx,
        double vz
) {

    public static SubLevelProjection worldAligned(ISubLevelService service, SubLevelInfo sub,
                                                  MapRenderContext context, TopDownView view) {
        Vec3 centerLocal = new Vec3(view.localCenter().x, view.localCenter().y, view.localCenter().z);
        Vec3 centerWorld = service.localToWorld(sub, centerLocal);
        double half = view.diameter() / 2.0;
        double pixelsPerBlock = context.pixelsPerBlock();
        return new SubLevelProjection(
                context.screenX(centerWorld.x - half),
                context.screenZ(centerWorld.z - half),
                pixelsPerBlock * view.stepX(), 0.0,
                0.0, pixelsPerBlock * view.stepZ()
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
