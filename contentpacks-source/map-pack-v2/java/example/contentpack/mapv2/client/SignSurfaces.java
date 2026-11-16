package example.contentpack.mapv2.client;

import example.contentpack.mapv2.MapStackLayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.WoodType;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 告示牌的上表面。
 *
 * <p>告示牌的方块模型是空的（只有粒子贴图），几何全在 {@code SignRenderer} /
 * {@code HangingSignRenderer} 的模型里，贴图在独立的 signs 图集。从正上方看到的
 * 只有板子的顶棱，所以这里合成的是那条窄面：
 *
 * <ul>
 *   <li><b>竖牌</b>：板 16×1.33、顶在 17.33/16（比一格还高）；</li>
 *   <li><b>墙牌</b>：板贴着朝向那一侧，顶在 12.33/16；</li>
 *   <li><b>墙挂悬挂牌</b>：横板 16×4 在 16/16，把下面的挂牌整片盖住；</li>
 *   <li><b>天花板悬挂牌</b>：没有横板，看到的是挂牌顶棱 14×2、在 10/16。</li>
 * </ul>
 *
 * <p>竖牌和天花板悬挂牌的方块状态是 16 档旋转（22.5° 一档），地图层现在按同样的
 * 16 档存（{@link MapStackLayer#rotation()}），斜 45°/22.5° 也能画成斜的；
 * 墙牌/墙挂牌走 {@code FACING}，正好是 90° 的整数倍（档位 ×4）。
 */
public final class SignSurfaces {

    /**
     * 竖牌/墙牌板顶的 UV：texOffs(0,0)，dx 24、dz 2 的 DOWN 面。
     *
     * <p>UV 跟 {@link MapStackLayer#UV_UNITS} 一样按「贴图该轴尺寸的 64 等分」存，
     * 不是像素：告示牌贴图是 64×32，u 的 64 等分恰好是像素、v 的 64 等分是半像素，
     * 所以下面 v 的像素值要 ×2（板顶 v 0..2 → 0..4）。
     */
    static final int BOARD_U0 = 2;
    static final int BOARD_U1 = 26;
    static final int BOARD_V0 = 0;
    static final int BOARD_V1 = 4;
    /** 悬挂牌挂牌顶棱：texOffs(0,12)，dx 14、dz 2 的 DOWN 面（像素 u 2..16、v 12..14）。 */
    static final int HANG_BOARD_U0 = 2;
    static final int HANG_BOARD_U1 = 16;
    static final int HANG_BOARD_V0 = 24;
    static final int HANG_BOARD_V1 = 28;
    /** 墙挂悬挂牌的横板：texOffs(0,0)，dx 16、dz 4 的 DOWN 面（像素 v 0..4）。 */
    static final int PLANK_U0 = 4;
    static final int PLANK_U1 = 20;
    static final int PLANK_V0 = 0;
    static final int PLANK_V1 = 8;

    /** 竖牌板顶高度 0.5 + 14×2/3/16。 */
    static final float STANDING_HEIGHT = 17.333f;
    /** 墙牌板顶高度 0.1875 + 14×2/3/16。 */
    static final float WALL_HEIGHT = 12.333f;
    /** 悬挂牌挂牌顶棱高度（模型 y=0）。 */
    static final float HANG_BOARD_HEIGHT = 10f;
    /** 墙挂悬挂牌横板高度（模型 y=-6）。 */
    static final float PLANK_HEIGHT = 16f;

    private SignSurfaces() {
    }

    /**
     * 一块告示牌从正上方看到的那一面；取不到贴图（图集还没就绪）返回 {@code null}，
     * 调用方回落到 {@link BlockSurfaces#of} 的平色终点。
     */
    @Nullable
    public static BlockSurfaces.Surface surface(BlockState state) {
        Block block = state.getBlock();
        if (!(block instanceof SignBlock sign)) {
            return null;
        }
        if (Minecraft.getInstance().level == null) {
            return null;
        }
        WoodType woodType = SignBlock.getWoodType(block);
        boolean hanging = block instanceof CeilingHangingSignBlock
                || block instanceof WallHangingSignBlock;
        Material material;
        try {
            material = hanging ? Sheets.getHangingSignMaterial(woodType)
                    : Sheets.getSignMaterial(woodType);
        } catch (RuntimeException ex) {
            return null;
        }
        if (material == null) {
            return null;
        }
        TextureAtlasSprite sprite = BlockSurfaces.materialSprite(material);
        if (sprite == null) {
            return null;
        }
        int rotation = rotationFor(state, sign);
        int[] model = footprint(state);
        int[] uv = uv(state);
        BlockSurfaces.Part part = new BlockSurfaces.Part(sprite,
                uv[0], uv[1], uv[2], uv[3],
                model[0], model[1], model[2], model[3],
                255, height(state), rotation, -1);
        return BlockSurfaces.surfaceOfParts(List.of(part));
    }

    /** 低缩放平色：板顶贴图的平均色（不同木材不同，和方块本身的材质一致）。 */
    public static int flatColor(BlockState state) {
        BlockSurfaces.Surface surface = surface(state);
        if (surface == null || surface.parts().isEmpty()) {
            return -1;
        }
        return BlockSurfaces.partColor(surface.parts().get(0));
    }

    /**
     * 模型空间里板顶的投影矩形（未按朝向旋转）。
     *
     * <p>竖牌板宽 24×2/3 = 16、厚 2×2/3 = 1.33，居中落在 z 7..9；
     * 墙牌整块向朝向那侧偏移，落在 z 0..2。
     */
    static int[] footprint(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CeilingHangingSignBlock) {
            return new int[]{1, 7, 15, 9};
        }
        if (block instanceof WallHangingSignBlock) {
            return new int[]{0, 6, 16, 10};
        }
        if (block instanceof WallSignBlock) {
            return new int[]{0, 0, 16, 2};
        }
        return new int[]{0, 7, 16, 9};
    }

    /** 板顶贴图矩形（六十四等分）。 */
    static int[] uv(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CeilingHangingSignBlock) {
            return new int[]{HANG_BOARD_U0, HANG_BOARD_V0, HANG_BOARD_U1, HANG_BOARD_V1};
        }
        if (block instanceof WallHangingSignBlock) {
            return new int[]{PLANK_U0, PLANK_V0, PLANK_U1, PLANK_V1};
        }
        return new int[]{BOARD_U0, BOARD_V0, BOARD_U1, BOARD_V1};
    }

    /** 板顶高度（十六等分，竖牌超过 16）。 */
    static float height(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CeilingHangingSignBlock) {
            return HANG_BOARD_HEIGHT;
        }
        if (block instanceof WallHangingSignBlock) {
            return PLANK_HEIGHT;
        }
        if (block instanceof WallSignBlock) {
            return WALL_HEIGHT;
        }
        return STANDING_HEIGHT;
    }

    /**
     * 贴图要顺时针转几档（22.5° 一档）：墙牌按 FACING（90° 的整数倍），
     * 竖牌/天花板挂架按方块状态自带的 22.5° 档位。
     */
    static int rotationFor(BlockState state, SignBlock sign) {
        Block block = state.getBlock();
        if (block instanceof WallSignBlock || block instanceof WallHangingSignBlock) {
            Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
            return (facing.get2DDataValue() & 3) * 4;
        }
        return nearestSegment(sign.getYRotationDegrees(state));
    }

    /** 任意偏航角 → 最近的 22.5° 档位（0..15，顺时针）。 */
    static int nearestSegment(float degrees) {
        return Math.round(degrees / 22.5f) & 15;
    }
}
