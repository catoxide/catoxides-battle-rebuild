package example.contentpack.sablebase.map;

import com.catoxide.catoxidesbattlerebuild.core.map.MapRenderContext;
import com.catoxide.catoxidesbattlerebuild.core.sable.ISubLevelService;
import com.catoxide.catoxidesbattlerebuild.core.sable.PoseData;
import com.catoxide.catoxidesbattlerebuild.core.sable.SubLevelBounds;
import com.catoxide.catoxidesbattlerebuild.core.sable.SubLevelInfo;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import example.contentpack.sablebase.map.client.SubLevelProjection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 纯数学自检：防腐层的姿态正逆变换、包围盒、局部锚点往返。
 *
 * <p>只依赖 core 值类型，不启动游戏也能验证。失败记 ERROR，
 * 由 {@code scripts/validate-game.ps1} 扫日志捕获。
 */
public final class SableMapSelfTest {

    private static final String TAG = "SableMapSelfTest";
    private static final double EPS = 1.0e-6;

    private SableMapSelfTest() {
    }

    public static boolean run() {
        ISubLevelService service = new ISubLevelService() {
            @Override
            public @Nullable SubLevelInfo getSubLevelAt(Level level, BlockPos worldPos) {
                return null;
            }
        };
        boolean ok = true;
        ok &= check("identity", service, pose(0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0),
                new Vec3(4.5, 70.0, -3.25), new Vec3(4.5, 70.0, -3.25));
        // 绕 Y +90°（右手系）：+X → -Z，+Z → +X
        double s = Math.sin(Math.PI / 4.0);
        double c = Math.cos(Math.PI / 4.0);
        ok &= check("yaw90", service, pose(0, 0, 0, 0, s, 0, c, 1, 1, 1, 0, 0, 0),
                new Vec3(1, 0, 0), new Vec3(0, 0, -1));
        ok &= check("translate+scale+rotPoint", service,
                pose(100, 64, -200, 0, s, 0, c, 2, 3, 4, 5, 6, 7),
                new Vec3(8.5, 9.25, 10.75), null);
        ok &= projectionCheck(service);
        ok &= viewportCheck();
        ok &= boundsCheck();
        if (!ok) {
            LogManager.clientError(TAG, "Sable map math self-test FAILED");
        } else {
            LogManager.clientStartup(TAG, "Sable map math self-test passed");
        }
        return ok;
    }

    private static boolean check(String name, ISubLevelService service, PoseData pose,
                                 Vec3 local, @Nullable Vec3 expectedWorld) {
        SubLevelInfo sub = new SubLevelInfo(UUID.randomUUID(), "self-test", pose,
                new SubLevelBounds(-32, 0, -32, 63, 255, 63));
        Vec3 world = service.localToWorld(sub, local);
        Vec3 actual = expectedWorld == null ? world : expectedWorld;
        Vec3 back = service.worldToLocal(sub, actual);
        boolean posOk = near(world, actual) && near(back, local);
        if (!posOk) {
            LogManager.clientError(TAG, String.format(
                    "%s failed: local=%s world=%s expected=%s back=%s",
                    name, local, world, actual, back));
        }
        return posOk;
    }

    private static boolean boundsCheck() {
        SubLevelBounds bounds = new SubLevelBounds(10, 60, 20, 19, 80, 29);
        boolean ok = bounds.containsColumn(10, 20)
                && bounds.containsColumn(20.0, 30.0)
                && !bounds.containsColumn(9.99, 20)
                && !bounds.containsColumn(19, 30.01)
                && bounds.contains(20.0, 81.0, 30.0)
                && !bounds.contains(20.0, 81.01, 30.0);
        if (!ok) {
            LogManager.clientError(TAG, "SubLevelBounds containment failed");
        }
        return ok;
    }

    /** 俯视投影：位置/旋转/缩放到屏幕仿射矩阵的基向量。 */
    private static boolean projectionCheck(ISubLevelService service) {
        MapRenderContext context = new MapRenderContext(
                null, false, 0, 0, 100, 100, 2.0, 0, 0, null, 0.0f);
        SubLevelBounds bounds = new SubLevelBounds(0, 0, 0, 9, 0, 9);

        SubLevelInfo identity = new SubLevelInfo(UUID.randomUUID(), "identity",
                pose(0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0), bounds);
        SubLevelProjection p0 = SubLevelProjection.of(service, identity, context, 1);
        boolean ok = near(p0.originScreenX(), 0.0) && near(p0.originScreenZ(), 0.0)
                && near(p0.ux(), 0.5) && near(p0.uz(), 0.0)
                && near(p0.vx(), 0.0) && near(p0.vz(), 0.5);

        double s = Math.sin(Math.PI / 4.0);
        double c = Math.cos(Math.PI / 4.0);
        SubLevelInfo yaw90 = new SubLevelInfo(UUID.randomUUID(), "yaw90",
                pose(0, 0, 0, 0, s, 0, c, 1, 1, 1, 0, 0, 0), bounds);
        SubLevelProjection p1 = SubLevelProjection.of(service, yaw90, context, 1);
        ok &= near(p1.ux(), 0.0) && near(p1.uz(), -0.5)
                && near(p1.vx(), 0.5) && near(p1.vz(), 0.0);

        // 船体局部 yaw 0（局部 +Z）在 90° 船上应变成世界 -90°（+X）
        float worldYaw = SubLevelProjection.worldYaw(service, yaw90, 0.0, 0.0f);
        ok &= near(worldYaw, -90.0);

        // 全屏 v2：分数 bpp（1/2）下每局部方块占 2 像素
        MapRenderContext deep = new MapRenderContext(
                null, true, 0, 0, 128, 64, 0.5, 0, 0, null, 0.0f);
        SubLevelProjection p2 = SubLevelProjection.of(service, identity, deep, 1);
        ok &= near(p2.ux(), 2.0) && near(p2.vz(), 2.0) && near(p2.originScreenX(), 0.0);

        if (!ok) {
            LogManager.clientError(TAG, "SubLevelProjection mapping failed");
        }
        return ok;
    }

    /** 矩形视口 + 分数 bpp：world→screen 与裁剪框边界。 */
    private static boolean viewportCheck() {
        MapRenderContext context = new MapRenderContext(
                null, true, 10, 20, 200, 100, 0.5, -50.0, -25.0, null, 0.0f);
        boolean ok = near(context.screenX(-50.0), 10.0)
                && near(context.screenZ(-25.0), 20.0)
                && near(context.screenX(50.0), 210.0)
                && near(context.screenZ(25.0), 120.0)
                && near(context.pixelsPerBlock(), 2.0)
                && context.right() == 210 && context.bottom() == 120
                && context.intersects(0, 0, 20, 30)
                && !context.intersects(211, 0, 300, 30)
                && !context.intersects(0, 121, 30, 200);
        if (!ok) {
            LogManager.clientError(TAG, "MapRenderContext viewport mapping failed");
        }
        return ok;
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) <= EPS;
    }

    private static PoseData pose(double x, double y, double z,
                                 double qx, double qy, double qz, double qw,
                                 double sx, double sy, double sz,
                                 double rx, double ry, double rz) {
        return new PoseData(x, y, z, qx, qy, qz, qw, sx, sy, sz, rx, ry, rz);
    }

    private static boolean near(Vec3 a, Vec3 b) {
        return Math.abs(a.x - b.x) <= EPS
                && Math.abs(a.y - b.y) <= EPS
                && Math.abs(a.z - b.z) <= EPS;
    }
}
