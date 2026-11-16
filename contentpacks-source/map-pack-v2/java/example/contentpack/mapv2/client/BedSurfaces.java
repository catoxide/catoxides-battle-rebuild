package example.contentpack.mapv2.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import org.jetbrains.annotations.Nullable;

/**
 * 床的上表面。
 *
 * <p>床是 {@code ENTITYBLOCK_ANIMATED}：方块模型里只有个粒子贴图，几何和贴图都在
 * {@code BedRenderer} 的 BED_HEAD / BED_FOOT 模型与独立的 beds 图集里，
 * {@link BlockSurfaces} 拿不到任何顶面。这里按渲染器的变换直接拼出那一面。
 *
 * <p>变换（{@code BedRenderer.renderPiece}，世界里 {@code foot} 参数恒为 false）：
 * 模型空间 Y 朝下，绕 X 转 90° 后模型的北面变成世界 UP 面、整块平铺；
 * 再绕（此时已变成世界竖直轴的）Z 转 {@code 180° + FACING.toYRot()}。
 * 结果：顶面仍是整格 16×16 的轴对齐矩形，纹理 +u 在 SOUTH 时指向 −X、+v 指向 −Z，
 * 每转 90° 一档（4 个 22.5° 档）—— 正好是 {@link BlockSurfaces.Part#rotation()} 的口径。
 *
 * <p>贴图：head 的 main 立方体 {@code texOffs(0,0)}，顶面落在 64×64 贴图的
 * u 6..22 / v 6..22；foot 是 {@code texOffs(0,22)}，顶面 v 28..44。
 * 两片用真实的头/脚纹理，所以枕头皮和床尾分得开。
 */
public final class BedSurfaces {

    /** 床头顶面在床贴图里的六十四等分矩形。 */
    static final int TOP_U0 = 6;
    static final int TOP_U1 = 22;
    static final int HEAD_V0 = 6;
    static final int HEAD_V1 = 22;
    static final int FOOT_V0 = 28;
    static final int FOOT_V1 = 44;
    /** 床顶面的高度：模型 9/16（{@code BedBlock.HEIGHT}）。 */
    static final float TOP_HEIGHT = 9f;

    private BedSurfaces() {
    }

    /**
     * 一张床从正上方看到的那一面；取不到贴图（图集还没就绪）返回 {@code null}，
     * 调用方回落到 {@link BlockSurfaces#of} 的平色终点。
     */
    @Nullable
    public static BlockSurfaces.Surface surface(BlockState state) {
        if (!(state.getBlock() instanceof BedBlock bed)) {
            return null;
        }
        if (Minecraft.getInstance().level == null) {
            return null;
        }
        TextureAtlasSprite sprite;
        try {
            sprite = BlockSurfaces.materialSprite(Sheets.BED_TEXTURES[bed.getColor().getId()]);
        } catch (RuntimeException ex) {
            return null;
        }
        if (sprite == null) {
            return null;
        }
        boolean head = state.getValue(BedBlock.PART) == BedPart.HEAD;
        Direction facing = state.getValue(BedBlock.FACING);
        BlockSurfaces.Part part = new BlockSurfaces.Part(sprite,
                TOP_U0, head ? HEAD_V0 : FOOT_V0, TOP_U1, head ? HEAD_V1 : FOOT_V1,
                0, 0, 16, 16, 255, TOP_HEIGHT, rotationFor(facing), -1);
        return BlockSurfaces.surfaceOfParts(java.util.List.of(part));
    }

    /** 低缩放平色：这一片床纹理的平均色（头/脚不同，枕头那一片会浅一些）。 */
    public static int flatColor(BlockState state) {
        BlockSurfaces.Surface surface = surface(state);
        if (surface == null || surface.parts().isEmpty()) {
            return -1;
        }
        return BlockSurfaces.partColor(surface.parts().get(0));
    }

    /**
     * 贴图相对格子的旋转（0..15，22.5° 顺时针一档；床只有 90° 的 4 档）。
     *
     * <p>顶面上 +u 指向 {@code (cosθ, sinθ)}、+v 指向 {@code (−sinθ, cosθ)}，
     * θ = 180° + FACING.toYRot() —— 和 {@code BlockSurfaces.rotationOf} 的
     * TARGET_U / TARGET_V 一一对应。
     */
    static int rotationFor(Direction facing) {
        return switch (facing) {
            case SOUTH -> 8;
            case WEST -> 12;
            case NORTH -> 0;
            case EAST -> 4;
            default -> 8;
        };
    }
}
