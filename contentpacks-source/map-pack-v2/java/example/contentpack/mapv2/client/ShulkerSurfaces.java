package example.contentpack.mapv2.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 潜影盒的上表面。
 *
 * <p>潜影盒是 {@code ENTITYBLOCK_ANIMATED}，方块模型只有粒子贴图，几何在
 * {@code ShulkerModel} 里，贴图在独立的 shulker_boxes 图集。这里取盖子
 * {@code texOffs(0,0).addBox(-8,-16,-8,16,12,16)} 的 DOWN 面：渲染器先用
 * {@code scale(1,-1,-1)} 翻转，模型 DOWN 面成为视觉顶面，正好整格 16×16。
 *
 * <p>贴图 UV 按 64 等分（贴图 64×64，等分即像素）：u 16..32、v 0..16。颜色与
 * {@code ShulkerBoxRenderer} 同口径 —— 无色用默认图，染色按 {@link DyeColor} 取。
 *
 * <p>朝向：{@code FACING} 是六向，立在地上时是 UP（不转）；贴墙/天花板时模型整体
 * 转 90°，从上看仍是同一块 16×16 方盖，按水平朝向转 90° 的整数倍即可。
 */
public final class ShulkerSurfaces {

    /** 盖子 DOWN 面在潜影盒贴图里的六十四等分矩形。 */
    static final int TOP_U0 = 16;
    static final int TOP_U1 = 32;
    static final int TOP_V0 = 0;
    static final int TOP_V1 = 16;
    /** 关闭状态下整格高：模型 16/16。 */
    static final float TOP_HEIGHT = 16f;

    private ShulkerSurfaces() {
    }

    /**
     * 一只潜影盒从正上方看到的那一面；取不到贴图（图集还没就绪）返回 {@code null}，
     * 调用方回落到 {@link BlockSurfaces#of} 的平色终点。
     */
    @Nullable
    public static BlockSurfaces.Surface surface(BlockState state) {
        if (!(state.getBlock() instanceof ShulkerBoxBlock block)) {
            return null;
        }
        if (Minecraft.getInstance().level == null) {
            return null;
        }
        TextureAtlasSprite sprite = BlockSurfaces.materialSprite(materialFor(block));
        if (sprite == null) {
            return null;
        }
        Direction facing = state.getValue(ShulkerBoxBlock.FACING);
        BlockSurfaces.Part part = new BlockSurfaces.Part(sprite,
                TOP_U0, TOP_V0, TOP_U1, TOP_V1,
                0, 0, 16, 16, 255, TOP_HEIGHT, rotationFor(facing), -1);
        return BlockSurfaces.surfaceOfParts(List.of(part));
    }

    /** 低缩放平色：盖子贴图的平均色（各染色与无色自然分开）。 */
    public static int flatColor(BlockState state) {
        BlockSurfaces.Surface surface = surface(state);
        if (surface == null || surface.parts().isEmpty()) {
            return -1;
        }
        return BlockSurfaces.partColor(surface.parts().get(0));
    }

    /** 水平朝向转 90° 的整数倍（×4 到 22.5° 档位）；UP/DOWN 不转。 */
    static int rotationFor(Direction facing) {
        if (!facing.getAxis().isHorizontal()) {
            return 0;
        }
        return (facing.get2DDataValue() & 3) * 4;
    }

    /** 与 {@code ShulkerBoxRenderer} 相同的材质选择。 */
    private static Material materialFor(ShulkerBoxBlock block) {
        DyeColor color = block.getColor();
        return color == null ? Sheets.DEFAULT_SHULKER_TEXTURE_LOCATION
                : Sheets.SHULKER_TEXTURE_LOCATION.get(color.getId());
    }
}
