package example.contentpack.mapv2.client;

import example.contentpack.mapv2.MapStackLayer;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.Arrays;

/**
 * 实体渲染方块（箱子、告示牌、潜影盒、潮涌核心、陶罐、生物头）的纯规则自检：
 * 合成顶面的 UV 矩形、本地投影、旋转档位。
 *
 * <p>不碰 {@code BlockState} / 图集：那两样要游戏 bootstrap。UV 常量按
 * {@code ModelPart.Cube} 的面展开逐项核对（见各类注释），这里只守住「值有没有被改错」。
 *
 * <p>v8 起瓦片存的是<b>旋转之前的本地矩形</b>加上 22.5° 一档的旋转，渲染端先铺矩形
 * 再转位姿。{@link MapStackLayer#unrotatedRect} 只对 90° 整档成立（烘焙四边形、旧瓦片）。
 */
public final class EntitySurfacesSelfTest {

    private EntitySurfacesSelfTest() {
    }

    public static void run() {
        testLocalRect();
        testChest();
        testSign();
        testShulker();
        testConduit();
        testPot();
        testSkull();
    }

    /** 世界矩形只能折回 90° 整档的本地矩形；斜 22.5° 的档位不能在矩形上「就近取整」。 */
    private static void testLocalRect() {
        int[][] rects = {{1, 1, 15, 15}, {0, 1, 16, 15}, {0, 0, 16, 2}, {2, 3, 5, 9}};
        for (int r = 0; r < 4; r++) {
            for (int[] rect : rects) {
                int[] local = MapStackLayer.unrotatedRect(r, rect[0], rect[1], rect[2], rect[3]);
                require(local[0] >= 0 && local[1] >= 0
                                && local[2] <= MapStackLayer.UNITS && local[3] <= MapStackLayer.UNITS
                                && local[0] < local[2] && local[1] < local[3],
                        "折回的本地矩形必须留在格子里: r=" + r + " " + Arrays.toString(local));
            }
        }
        // 45° 斜放的竖牌：档位必须是 2（90°），不能被「就近取整」压成 0 画成正面
        require(SignSurfaces.nearestSegment(45f) == 2, "45° 是第 2 档，不能取整成 0");
        require(SignSurfaces.nearestSegment(22.5f) == 1, "22.5° 是第 1 档");
        require(SignSurfaces.nearestSegment(67.5f) == 3, "67.5° 是第 3 档");
        require(SignSurfaces.nearestSegment(337.5f) == 15, "337.5° 是第 15 档");
        require(SignSurfaces.nearestSegment(359f) == 0, "359° 归到 0 档");
    }

    private static void testChest() {
        require(ChestSurfaces.TOP_V0 == 0 && ChestSurfaces.TOP_V1 == 14, "箱盖顶面 v 0..14");
        require(ChestSurfaces.SINGLE_U0 == 28 && ChestSurfaces.SINGLE_U1 == 42,
                "单箱盖顶 u 28..42（texOffs(0,0)、dx=14、dz=14 的 UP 面）");
        require(ChestSurfaces.DOUBLE_U0 == 29 && ChestSurfaces.DOUBLE_U1 == 44,
                "双箱盖顶 u 29..44（dx=15）");
        require(ChestSurfaces.TOP_HEIGHT == 14f, "箱盖顶面高度 14/16");

        require(Arrays.equals(ChestSurfaces.modelRect(ChestType.SINGLE), new int[]{1, 1, 15, 15}),
                "单箱模型投影 1..15");
        require(Arrays.equals(ChestSurfaces.modelRect(ChestType.LEFT), new int[]{0, 1, 15, 15}),
                "双箱左半模型投影 0..15");
        require(Arrays.equals(ChestSurfaces.modelRect(ChestType.RIGHT), new int[]{1, 1, 16, 15}),
                "双箱右半模型投影 1..16");
        require(Arrays.equals(ChestSurfaces.uv(ChestType.SINGLE), new int[]{28, 0, 42, 14}),
                "单箱 UV");
        require(Arrays.equals(ChestSurfaces.uv(ChestType.LEFT), new int[]{29, 0, 44, 14}),
                "双箱左半 UV");

        // ChestRenderer 绕 Y 转 -FACING.toYRot()：SOUTH/WEST/NORTH/EAST 各差 90°，即 4 档
        require(ChestSurfaces.rotationFor(Direction.SOUTH) == 0, "箱子朝南 r0");
        require(ChestSurfaces.rotationFor(Direction.WEST) == 4, "箱子朝西 r4");
        require(ChestSurfaces.rotationFor(Direction.NORTH) == 8, "箱子朝北 r8");
        require(ChestSurfaces.rotationFor(Direction.EAST) == 12, "箱子朝东 r12");
    }

    private static void testSign() {
        // 竖牌/墙牌：texOffs(0,0)，dx 24、dz 2，顶面是 DOWN 面 u 2..26、像素 v 0..2
        require(SignSurfaces.BOARD_U0 == 2 && SignSurfaces.BOARD_U1 == 26,
                "竖牌板顶 u 2..26");
        require(SignSurfaces.BOARD_V0 == 0 && SignSurfaces.BOARD_V1 == 4,
                "竖牌板顶 v 0..4（64×32 贴图里 0..2 像素）");
        // 天花板悬挂牌：texOffs(0,12)，dx 14、dz 2 → u 2..16、像素 v 12..14
        require(SignSurfaces.HANG_BOARD_U0 == 2 && SignSurfaces.HANG_BOARD_U1 == 16,
                "悬挂牌顶棱 u 2..16");
        require(SignSurfaces.HANG_BOARD_V0 == 24 && SignSurfaces.HANG_BOARD_V1 == 28,
                "悬挂牌顶棱 v 24..28（64×32 贴图里 12..14 像素）");
        // 墙挂悬挂牌横板：texOffs(0,0)，dx 16、dz 4 → u 4..20、像素 v 0..4
        require(SignSurfaces.PLANK_U0 == 4 && SignSurfaces.PLANK_U1 == 20, "横板 u 4..20");
        require(SignSurfaces.PLANK_V0 == 0 && SignSurfaces.PLANK_V1 == 8,
                "横板 v 0..8（64×32 贴图里 0..4 像素）");

        // UV 按「该轴尺寸的 64 等分」存，两个轴的上限都是 64
        require(SignSurfaces.BOARD_U1 <= MapStackLayer.UV_UNITS
                        && SignSurfaces.HANG_BOARD_U1 <= MapStackLayer.UV_UNITS
                        && SignSurfaces.PLANK_U1 <= MapStackLayer.UV_UNITS
                        && SignSurfaces.BOARD_V1 <= MapStackLayer.UV_UNITS
                        && SignSurfaces.HANG_BOARD_V1 <= MapStackLayer.UV_UNITS
                        && SignSurfaces.PLANK_V1 <= MapStackLayer.UV_UNITS,
                "告示牌 UV 不能超出六十四等分");
        // 64×32 贴图：v 的 64 等分换算回像素 = v×32/64，必须落在 32 像素内
        require(SignSurfaces.BOARD_V1 * 32 / MapStackLayer.UV_UNITS == 2
                        && SignSurfaces.HANG_BOARD_V0 * 32 / MapStackLayer.UV_UNITS == 12
                        && SignSurfaces.HANG_BOARD_V1 * 32 / MapStackLayer.UV_UNITS == 14
                        && SignSurfaces.PLANK_V1 * 32 / MapStackLayer.UV_UNITS == 4,
                "告示牌 v 换算回 64×32 的像素区域");

        // 高度：竖牌比一格高、墙牌比它低 5/16，横板在格顶
        require(Math.abs(SignSurfaces.STANDING_HEIGHT - 17.333f) < 1e-3f, "竖牌高度 17.33");
        require(Math.abs(SignSurfaces.WALL_HEIGHT - 12.333f) < 1e-3f, "墙牌高度 12.33");
        require(Math.abs(SignSurfaces.STANDING_HEIGHT - SignSurfaces.WALL_HEIGHT - 5f) < 1e-3f,
                "墙牌只比竖牌低 5/16（0.3125 的挂墙偏移）");
        require(SignSurfaces.HANG_BOARD_HEIGHT == 10f, "悬挂牌顶棱 10/16");
        require(SignSurfaces.PLANK_HEIGHT == 16f, "横板 16/16");
    }

    private static void testShulker() {
        // 盖子 texOffs(0,0).addBox(-8,-16,-8,16,12,16)：DOWN 面 u 16..32 / v 0..16，贴图 64×64
        require(ShulkerSurfaces.TOP_U0 == 16 && ShulkerSurfaces.TOP_U1 == 32,
                "潜影盒盖顶 u 16..32");
        require(ShulkerSurfaces.TOP_V0 == 0 && ShulkerSurfaces.TOP_V1 == 16,
                "潜影盒盖顶 v 0..16");
        require(ShulkerSurfaces.TOP_HEIGHT == 16f, "潜影盒整格高 16/16");
        require(ShulkerSurfaces.TOP_U1 <= MapStackLayer.UV_UNITS
                        && ShulkerSurfaces.TOP_V1 <= MapStackLayer.UV_UNITS,
                "潜影盒 UV 不能超出六十四等分");

        // FACING 是六向：立在地上（UP）不转，贴墙时按水平朝向转 90° 的整数倍
        require(ShulkerSurfaces.rotationFor(Direction.UP) == 0, "潜影盒朝上不转");
        require(ShulkerSurfaces.rotationFor(Direction.DOWN) == 0, "潜影盒朝下不转");
        require(ShulkerSurfaces.rotationFor(Direction.SOUTH) == 0, "潜影盒朝南 r0");
        require(ShulkerSurfaces.rotationFor(Direction.WEST) == 4, "潜影盒朝西 r4");
        require(ShulkerSurfaces.rotationFor(Direction.NORTH) == 8, "潜影盒朝北 r8");
        require(ShulkerSurfaces.rotationFor(Direction.EAST) == 12, "潜影盒朝东 r12");
    }

    private static void testConduit() {
        // 外壳 texOffs(0,0).addBox(-3,-3,-3,6,6,6)、贴图 32×16：UP 面像素 u 12..18 / v 0..6
        require(ConduitSurfaces.SHELL_U0 == 24 && ConduitSurfaces.SHELL_U1 == 36,
                "潮涌核心外壳顶面 u 24..36（像素 12..18）");
        require(ConduitSurfaces.SHELL_V0 == 0 && ConduitSurfaces.SHELL_V1 == 24,
                "潮涌核心外壳顶面 v 0..24（像素 0..6）");
        require(ConduitSurfaces.SHELL_HEIGHT == 11f, "外壳顶面 11/16");
        require(ConduitSurfaces.SHELL_MIN == 5 && ConduitSurfaces.SHELL_MAX == 11,
                "外壳投影 5..11");
        // 激活笼子 addBox(-4,-4,-4,8,8,8)：UP 面像素 u 16..24 / v 0..8
        require(ConduitSurfaces.CAGE_U0 == 32 && ConduitSurfaces.CAGE_U1 == 48,
                "笼子顶面 u 32..48（像素 16..24）");
        require(ConduitSurfaces.CAGE_V0 == 0 && ConduitSurfaces.CAGE_V1 == 32,
                "笼子顶面 v 0..32（像素 0..8）");
        require(ConduitSurfaces.CAGE_HEIGHT == 12f, "笼子顶面 12/16");
        require(ConduitSurfaces.CAGE_MIN == 4 && ConduitSurfaces.CAGE_MAX == 12,
                "笼子投影 4..12");
        require(ConduitSurfaces.CAGE_U1 <= MapStackLayer.UV_UNITS
                        && ConduitSurfaces.CAGE_V1 <= MapStackLayer.UV_UNITS,
                "潮涌核心 UV 不能超出六十四等分");
    }

    private static void testPot() {
        // texOffs(-14,13) 的零厚板：UP 面 u+dz+dx..u+dz+2dx、v+dz..v = 像素 14..28 / 13..27
        require(PotSurfaces.TOP_U0 == 28 && PotSurfaces.TOP_U1 == 56,
                "陶罐罐盖 u 28..56（32×32 贴图里 14..28 像素）");
        require(PotSurfaces.TOP_V0 == 26 && PotSurfaces.TOP_V1 == 54,
                "陶罐罐盖 v 26..54（32×32 贴图里 13..27 像素）");
        require(PotSurfaces.TOP_HEIGHT == 16f, "罐盖在格顶 16/16");
        require(PotSurfaces.TOP_MIN == 1 && PotSurfaces.TOP_MAX == 15, "罐盖投影 1..15");
        require(PotSurfaces.TOP_U1 <= MapStackLayer.UV_UNITS
                        && PotSurfaces.TOP_V1 <= MapStackLayer.UV_UNITS,
                "陶罐 UV 不能超出六十四等分");
    }

    private static void testSkull() {
        // 头顶是模型底面 u+dz..u+dz+dx、v..v+dz：8×8 头取像素 8..16 / 0..8
        require(Arrays.equals(SkullSurfaces.region(SkullBlock.Types.SKELETON),
                        new int[]{8, 0, 16, 8}),
                "骷髅头顶区域");
        require(Arrays.equals(SkullSurfaces.region(SkullBlock.Types.PLAYER),
                        new int[]{8, 0, 16, 8}),
                "玩家头顶区域");
        require(Arrays.equals(SkullSurfaces.region(SkullBlock.Types.PIGLIN),
                        new int[]{8, 0, 18, 8}),
                "猪灵头是 10 宽，头顶多 2 像素");
        require(Arrays.equals(SkullSurfaces.region(SkullBlock.Types.DRAGON),
                        new int[]{128, 30, 144, 46}),
                "龙头上颅 texOffs(112,30) 的底面");
        require(SkullSurfaces.FALLBACK_COLOR == 0x8A8A8A, "贴图缺失时的兜底灰");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
