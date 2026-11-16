package example.contentpack.mapv2.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.TrappedChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.jetbrains.annotations.Nullable;

import java.util.Calendar;
import java.util.List;

/**
 * 箱子的上表面。
 *
 * <p>箱子是 {@code ENTITYBLOCK_ANIMATED}，方块模型只有粒子贴图，几何在
 * {@code ChestRenderer} 的 {@code ChestModel} 里，贴图在独立的 chest 图集。
 * 这里按盖子的顶面拼一层：单箱 14×14、双箱各半 15×14，高度 14/16。
 *
 * <p>{@code ChestRenderer.render} 绕 Y 转 {@code -FACING.toYRot()}，所以模型空间
 * 的 1/16 格子转 90° 一档就是世界朝向；{@code Direction.get2DDataValue()} 正好是
 * 「SOUTH 0 / WEST 1 / NORTH 2 / EAST 3」这套顺时针档位。
 *
 * <p>双箱的贴图（left/right）是各自独立的 64×64 图，盖顶 UV 从 29 起（单箱从 28 起）。
 * 贴图选择与 {@code Sheets.chooseMaterial} 同口径：末影箱、陷阱箱、十二月的礼物贴图。
 */
public final class ChestSurfaces {

    /** 单箱盖顶在箱子贴图里的六十四等分矩形。 */
    static final int SINGLE_U0 = 28;
    static final int SINGLE_U1 = 42;
    /** 双箱各半的盖顶 UV（left/right 两张图同布局）。 */
    static final int DOUBLE_U0 = 29;
    static final int DOUBLE_U1 = 44;
    static final int TOP_V0 = 0;
    static final int TOP_V1 = 14;
    /** 盖顶高度：模型 9（盖偏移）+ 5（盖厚）= 14。 */
    static final float TOP_HEIGHT = 14f;

    private ChestSurfaces() {
    }

    /**
     * 一只箱子从正上方看到的那一面；取不到贴图（图集还没就绪）返回 {@code null}，
     * 调用方回落到 {@link BlockSurfaces#of} 的平色终点。
     */
    @Nullable
    public static BlockSurfaces.Surface surface(BlockState state) {
        Block block = state.getBlock();
        if (!(block instanceof ChestBlock) && !(block instanceof EnderChestBlock)) {
            return null;
        }
        if (Minecraft.getInstance().level == null) {
            return null;
        }
        ChestType type = state.hasProperty(ChestBlock.TYPE)
                ? state.getValue(ChestBlock.TYPE) : ChestType.SINGLE;
        Material material = materialFor(block, type);
        if (material == null) {
            return null;
        }
        TextureAtlasSprite sprite = BlockSurfaces.materialSprite(material);
        if (sprite == null) {
            return null;
        }
        Direction facing = state.hasProperty(ChestBlock.FACING)
                ? state.getValue(ChestBlock.FACING) : Direction.SOUTH;
        int rotation = rotationFor(facing);
        int[] model = modelRect(type);
        int[] uv = uv(type);
        BlockSurfaces.Part part = new BlockSurfaces.Part(sprite,
                uv[0], uv[1], uv[2], uv[3],
                model[0], model[1], model[2], model[3],
                255, TOP_HEIGHT, rotation, -1);
        return BlockSurfaces.surfaceOfParts(List.of(part));
    }

    /** 低缩放平色：盖顶贴图的平均色（末影箱偏青、普通箱偏棕）。 */
    public static int flatColor(BlockState state) {
        BlockSurfaces.Surface surface = surface(state);
        if (surface == null || surface.parts().isEmpty()) {
            return -1;
        }
        return BlockSurfaces.partColor(surface.parts().get(0));
    }

    /** 模型空间里盖顶的投影矩形（未按朝向旋转）：单箱 1..15，双箱各让出中缝那一列。 */
    static int[] modelRect(ChestType type) {
        return switch (type) {
            case LEFT -> new int[]{0, 1, 15, 15};
            case RIGHT -> new int[]{1, 1, 16, 15};
            default -> new int[]{1, 1, 15, 15};
        };
    }

    /** 盖顶贴图矩形（六十四等分）。 */
    static int[] uv(ChestType type) {
        return switch (type) {
            case LEFT, RIGHT -> new int[]{DOUBLE_U0, TOP_V0, DOUBLE_U1, TOP_V1};
            default -> new int[]{SINGLE_U0, TOP_V0, SINGLE_U1, TOP_V1};
        };
    }

    /** 朝向下贴图要顺时针转几档（22.5° 一档）：与 {@code Direction.get2DDataValue()} 同序，×4。 */
    static int rotationFor(Direction facing) {
        return (facing.get2DDataValue() & 3) * 4;
    }

    /** 材质选择与 {@code Sheets.chooseMaterial} 一致（这里没有 BlockEntity，按方块类型判）。 */
    @Nullable
    private static Material materialFor(Block block, ChestType type) {
        if (block instanceof EnderChestBlock) {
            return Sheets.ENDER_CHEST_LOCATION;
        }
        if (block instanceof TrappedChestBlock) {
            return switch (type) {
                case LEFT -> Sheets.CHEST_TRAP_LOCATION_LEFT;
                case RIGHT -> Sheets.CHEST_TRAP_LOCATION_RIGHT;
                default -> Sheets.CHEST_TRAP_LOCATION;
            };
        }
        if (isChristmas()) {
            return switch (type) {
                case LEFT -> Sheets.CHEST_XMAS_LOCATION_LEFT;
                case RIGHT -> Sheets.CHEST_XMAS_LOCATION_RIGHT;
                default -> Sheets.CHEST_XMAS_LOCATION;
            };
        }
        return switch (type) {
            case LEFT -> Sheets.CHEST_LOCATION_LEFT;
            case RIGHT -> Sheets.CHEST_LOCATION_RIGHT;
            default -> Sheets.CHEST_LOCATION;
        };
    }

    /** 与 {@code ChestRenderer} 相同的十二月 24–26 礼物贴图窗口。 */
    private static boolean isChristmas() {
        Calendar calendar = Calendar.getInstance();
        int month = calendar.get(Calendar.MONTH) + 1;
        int day = calendar.get(Calendar.DAY_OF_MONTH);
        return month == 12 && day >= 24 && day <= 26;
    }
}
