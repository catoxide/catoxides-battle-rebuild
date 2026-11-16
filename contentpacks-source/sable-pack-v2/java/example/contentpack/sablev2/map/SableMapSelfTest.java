package example.contentpack.sablev2.map;

import com.catoxide.catoxidesbattlerebuild.core.map.MapRenderContext;
import com.catoxide.catoxidesbattlerebuild.core.sable.ISubLevelService;
import com.catoxide.catoxidesbattlerebuild.core.sable.PoseData;
import com.catoxide.catoxidesbattlerebuild.core.sable.SubLevelBounds;
import com.catoxide.catoxidesbattlerebuild.core.sable.SubLevelInfo;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import example.contentpack.sablev2.map.client.SubLevelProjection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * 纯数学自检：防腐层姿态正逆变换、包围盒、局部锚点往返、俯视相机与 2D 投影。
 *
 * <p>只依赖 core 值类型与 JOML，不启动游戏也能验证。失败记 ERROR，
 * 由 {@code scripts/validate-game.ps1} 扫日志捕获。
 */
public final class SableMapSelfTest {

    private static final String TAG = "SableMapSelfTest";
    private static final double EPS = 1.0e-4;

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
        ok &= columnCheck(service);
        ok &= topDownCheck();
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

    /** 世界竖直列 ↔ 船体包围盒求交：平移、旋转、倾斜都要命中同一条世界竖直列。 */
    private static boolean columnCheck(ISubLevelService service) {
        boolean ok = true;
        SubLevelBounds bounds = new SubLevelBounds(0, 0, 0, 9, 4, 9);

        SubLevelInfo flat = new SubLevelInfo(UUID.randomUUID(), "flat",
                pose(100, 64, -50, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0), bounds);
        Vec3 flatTop = SubLevelColumns.topPoint(service, flat, 105.5, -45.5);
        ok &= flatTop != null && near(flatTop.x, 5.5)
                && near(flatTop.y, 5.0) && near(flatTop.z, 4.5);
        ok &= SubLevelColumns.topPoint(service, flat, 120.5, -45.5) == null;

        // 绕 Y +90°：局部中心 (5,0.5,5) 转到世界 (5,0.5,-5)，该世界列仍应命中局部中心列
        double s = Math.sin(Math.PI / 4.0);
        double c = Math.cos(Math.PI / 4.0);
        SubLevelInfo yaw90 = new SubLevelInfo(UUID.randomUUID(), "yaw90",
                pose(0, 0, 0, 0, s, 0, c, 1, 1, 1, 0, 0, 0),
                new SubLevelBounds(0, 0, 0, 9, 0, 9));
        Vec3 yawTop = SubLevelColumns.topPoint(service, yaw90, 5.0, -5.0);
        ok &= yawTop != null && near(yawTop.x, 5.0)
                && near(yawTop.y, 1.0) && near(yawTop.z, 5.0);

        // 绕 Z 侧倾 30°：沿「局部中心的世界竖直列」求交，落点必须仍在该列上
        double tilt = Math.toRadians(30.0);
        SubLevelInfo tilted = new SubLevelInfo(UUID.randomUUID(), "tilted",
                pose(100, 64, -50, 0, 0, Math.sin(tilt / 2.0), Math.cos(tilt / 2.0),
                        1, 1, 1, 5, 0, 5), bounds);
        Vec3 column = service.localToWorld(tilted, new Vec3(5.5, 2.5, 4.5));
        Vec3 tiltedTop = SubLevelColumns.topPoint(service, tilted, column.x, column.z);
        ok &= tiltedTop != null;
        if (tiltedTop != null) {
            Vec3 world = service.localToWorld(tilted, tiltedTop);
            ok &= near(world.x, column.x) && near(world.z, column.z)
                    && world.y > 60.0 && world.y < 71.0;
        }

        if (!ok) {
            LogManager.clientError(TAG, "SubLevelColumns intersection failed");
        }
        return ok;
    }

    /** 世界正俯视相机：正方视口、外接球直径、16 px/格 上限、屏幕轴朝向。 */
    private static boolean topDownCheck() {
        boolean ok = true;

        SubLevelBounds square = new SubLevelBounds(0, 0, 0, 9, 0, 9);
        TopDownView full = TopDownView.of(square, 384);
        // 10×10×1 的外接球直径 = sqrt(201) + 2 ≈ 16.18，预算够 16 px/格
        double diameter = Math.sqrt(100.0 + 100.0 + 1.0) + 2.0;
        ok &= near(full.diameter(), diameter);
        ok &= full.imageWidth() == full.imageHeight();
        ok &= full.imageWidth() == (int) Math.round(diameter * 16.0);
        ok &= near(full.stepX(), diameter / full.imageWidth());
        ok &= near(full.stepZ(), full.stepX());
        ok &= near(full.localCenter().x, 5.0)
                && near(full.localCenter().y, 0.5)
                && near(full.localCenter().z, 5.0);
        // 相机在外接球顶再抬 PADDING/2
        ok &= near(full.cameraLift(), diameter / 2.0);

        // 预算比超采样目标还小：预算优先，仍是正方形
        TopDownView budgeted = TopDownView.of(square, 100);
        ok &= budgeted.imageWidth() == 100 && budgeted.imageHeight() == 100;
        ok &= near(budgeted.stepX(), diameter / 100.0);

        // 屏幕右 = 世界 +X；屏幕上 = 世界 -Z（北）；相机朝世界 +Y
        Vector3f right = full.orientation().transform(new Vector3f(1, 0, 0));
        Vector3f up = full.orientation().transform(new Vector3f(0, 1, 0));
        Vector3f forward = full.orientation().transform(new Vector3f(0, 0, 1));
        ok &= near(right.x, 1) && near(right.y, 0) && near(right.z, 0);
        ok &= near(up.x, 0) && near(up.y, 0) && near(up.z, -1);
        ok &= near(forward.x, 0) && near(forward.y, 1) && near(forward.z, 0);

        // 降采样：大船长边仍会被压到 maxTexture，但外接球保证任意姿态都装得下
        SubLevelBounds longShip = new SubLevelBounds(0, 0, 0, 999, 0, 199);
        TopDownView reduced = TopDownView.of(longShip, 100);
        ok &= reduced.imageWidth() == 100 && reduced.imageHeight() == 100;
        ok &= near(reduced.stepX(), reduced.diameter() / 100.0);
        ok &= reduced.stepX() * reduced.imageWidth() >= 1000
                && reduced.stepZ() * reduced.imageHeight() >= 200;

        if (!ok) {
            LogManager.clientError(TAG, "TopDownView mapping failed");
        }
        return ok;
    }

    /** 世界对齐投影：只有平移与各向同性缩放，船体旋转不进入 2D 仿射。 */
    private static boolean projectionCheck(ISubLevelService service) {
        MapRenderContext context = new MapRenderContext(
                null, false, 0, 0, 100, 100, 2.0, 0, 0, null, 0.0f);
        SubLevelBounds bounds = new SubLevelBounds(0, 0, 0, 9, 0, 9);
        TopDownView view = TopDownView.of(bounds, 384);
        double half = view.diameter() / 2.0;
        double perTexturePixel = context.pixelsPerBlock() * view.stepX();

        SubLevelInfo identity = new SubLevelInfo(UUID.randomUUID(), "identity",
                pose(0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0), bounds);
        SubLevelProjection p0 = SubLevelProjection.worldAligned(service, identity, context, view);
        boolean ok = near(p0.originScreenX(), context.screenX(5.0 - half))
                && near(p0.originScreenZ(), context.screenZ(5.0 - half))
                && near(p0.ux(), perTexturePixel) && near(p0.uz(), 0.0)
                && near(p0.vx(), 0.0) && near(p0.vz(), perTexturePixel);

        // 绕 Y +90° 后中心变到世界 (5, -, -5)，但投影仍与世界轴对齐
        double s = Math.sin(Math.PI / 4.0);
        double c = Math.cos(Math.PI / 4.0);
        SubLevelInfo yaw90 = new SubLevelInfo(UUID.randomUUID(), "yaw90",
                pose(0, 0, 0, 0, s, 0, c, 1, 1, 1, 0, 0, 0), bounds);
        SubLevelProjection p1 = SubLevelProjection.worldAligned(service, yaw90, context, view);
        ok &= near(p1.originScreenX(), context.screenX(5.0 - half))
                && near(p1.originScreenZ(), context.screenZ(-5.0 - half))
                && near(p1.ux(), perTexturePixel) && near(p1.uz(), 0.0)
                && near(p1.vx(), 0.0) && near(p1.vz(), perTexturePixel);

        // 船体局部 yaw 0（局部 +Z）在 90° 船上应变成世界 -90°（+X）
        float worldYaw = SubLevelProjection.worldYaw(service, yaw90, 0.0, 0.0f);
        ok &= near(worldYaw, -90.0);

        // 预算更小时每个纹理像素覆盖更多格，投影按步长等比缩放
        TopDownView coarse = TopDownView.of(bounds, 64);
        SubLevelProjection p2 = SubLevelProjection.worldAligned(service, identity, context, coarse);
        ok &= p2.ux() > p0.ux()
                && near(p2.ux(), context.pixelsPerBlock() * coarse.stepX())
                && near(p2.vz(), context.pixelsPerBlock() * coarse.stepZ());

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
