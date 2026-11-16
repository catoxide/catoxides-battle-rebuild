package example.contentpack.mapv2.client;

import net.minecraft.core.Direction;

/**
 * 床顶面的纯规则自检：头/脚两片贴图矩形、朝向旋转。
 *
 * <p>旋转不是照抄一张表：这里按 {@code BedRenderer.renderPiece} 的变换
 * （绕 X 90°、再绕竖直轴 {@code 180° + FACING.toYRot()}）把模型顶面的四个角
 * 算到世界 XZ，再和地图旋转的四个档位比对 —— 与 {@code BlockSurfaces.rotationOf}
 * 的口径一致。
 */
public final class BedSurfacesSelfTest {

    /** 地图旋转档位下 +u / +v 在格子里的方向（x 向东、z 向南，90° 顺时针一档）。 */
    private static final float[][] TARGET_U = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
    private static final float[][] TARGET_V = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}};

    private BedSurfacesSelfTest() {
    }

    public static void run() {
        // 头/脚是同一块立方体的两面纹理区域：u 都是 6..22，v 分别是 6..22 / 28..44
        require(BedSurfaces.TOP_U0 == 6 && BedSurfaces.TOP_U1 == 22, "顶面 u 范围");
        require(BedSurfaces.HEAD_V0 == 6 && BedSurfaces.HEAD_V1 == 22, "床头顶面 v 范围");
        require(BedSurfaces.FOOT_V0 == 28 && BedSurfaces.FOOT_V1 == 44, "床尾顶面 v 范围");
        require(BedSurfaces.HEAD_V1 <= BedSurfaces.FOOT_V0, "头脚的贴图区域不能重叠");
        require(BedSurfaces.TOP_U1 - BedSurfaces.TOP_U0 == 16
                        && BedSurfaces.HEAD_V1 - BedSurfaces.HEAD_V0 == 16
                        && BedSurfaces.FOOT_V1 - BedSurfaces.FOOT_V0 == 16,
                "顶面在贴图里占 16×16 像素");
        require(Math.abs(BedSurfaces.TOP_HEIGHT - 9f) < 1e-6f, "床顶面高度 9/16");

        // 模型北面是顶面：+u 沿模型 +x，+v 沿模型 +y（与 Cube 的 NORTH 面一致）
        float[][] corners = {
                {0, 0, 0, 0}, {1, 0, 1, 0}, {1, 1, 1, 1}, {0, 1, 0, 1}};
        for (Direction facing : Direction.values()) {
            if (!facing.getAxis().isHorizontal()) {
                continue;
            }
            requireFacing(facing, corners);
        }
    }

    /** 按渲染器的变换把模型顶面四个角换算到世界 XZ，再比对 +u/+v 指向。 */
    private static void requireFacing(Direction facing, float[][] corners) {
        double theta = Math.toRadians(180.0 + yRotOf(facing));
        double c = Math.cos(theta);
        double s = Math.sin(theta);
        float[][] world = new float[4][2];
        for (int i = 0; i < 4; i++) {
            double u = corners[i][2];
            double v = corners[i][3];
            // 模型空间 Y 朝下：绕 X 90° 后顶面平铺，再绕竖直轴转 180° + toYRot()
            world[i][0] = (float) (0.5 + (u - 0.5) * c - (v - 0.5) * s);
            world[i][1] = (float) (0.5 + (u - 0.5) * s + (v - 0.5) * c);
            require(world[i][0] >= -1e-4 && world[i][0] <= 1.0001
                            && world[i][1] >= -1e-4 && world[i][1] <= 1.0001,
                    "床顶面必须落在本格内: " + facing + " -> " + java.util.Arrays.toString(world[i]));
        }
        // 仿射变换：u=0→1、v=0→1 的差就是 +u / +v 的世界方向
        float[] gu = normalize(world[1][0] - world[0][0], world[1][1] - world[0][1]);
        float[] gv = normalize(world[3][0] - world[0][0], world[3][1] - world[0][1]);
        // 存档里的 rotation 是 22.5° 一档（0..15），床只会落在整 90° 上
        int rotation = BedSurfaces.rotationFor(facing);
        require(rotation % 4 == 0, "床只能落在整 90° 档位: " + facing + " r=" + rotation);
        int r = rotation / 4;
        require(dot(gu, TARGET_U[r]) > 0.9999,
                "床 +u 方向与旋转档位不符: " + facing + " r=" + rotation
                        + " gu=" + java.util.Arrays.toString(gu));
        require(dot(gv, TARGET_V[r]) > 0.9999,
                "床 +v 方向与旋转档位不符: " + facing + " r=" + rotation
                        + " gv=" + java.util.Arrays.toString(gv));
    }

    private static int yRotOf(Direction facing) {
        return switch (facing) {
            case SOUTH -> 0;
            case WEST -> 90;
            case NORTH -> 180;
            case EAST -> 270;
            default -> 0;
        };
    }

    private static float[] normalize(float x, float z) {
        float length = (float) Math.hypot(x, z);
        return new float[]{x / length, z / length};
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1];
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
