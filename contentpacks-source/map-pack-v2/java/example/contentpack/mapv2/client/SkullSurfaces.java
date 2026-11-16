package example.contentpack.mapv2.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 生物头（含玩家头）的上表面。
 *
 * <p>头颅是 {@code INVISIBLE} 方块，模型和贴图都在 {@code SkullBlockRenderer} 里，
 * 贴图是实体用的原始 PNG，<b>没进图集</b>（玩家皮肤还是运行时下载的），所以瓦片里
 * 存不了 sprite，只存一个平色层（{@link BlockSurfaces.Part#flat}）。
 *
 * <p>渲染器先 {@code scale(-1,-1,1)}：模型底面翻成视觉顶面。头部方块是
 * {@code texOffs(0,0)} 的 8×8×8 立方体，底面 UV {@code u+dz..u+dz+dx, v..v+dz} 就是
 * 皮肤里头顶那一块：像素 u 8..16 / v 0..8。
 * <ul>
 *   <li>猪灵头是 10×8×8（{@code -5..5}），取 u 8..18；</li>
 *   <li>龙头大一圈（{@code upper_head} 16×16×16，{@code texOffs(112,30)}），取
 *       u 128..144 / v 30..46 —— 龙头比整格还宽，这里按方块形状裁到格内。</li>
 * </ul>
 *
 * <p>占地与高度直接取方块形状（{@code getShape}）：地板头 4..12 高 8，猪灵 3..13，
 * 墙上的头 4..12 / 4..12 高 12。头颅的朝向只转头顶纹理，平色不受影响，旋转记 0。
 */
public final class SkullSurfaces {

    /** 普通头（骷髅/凋灵骷髅/僵尸/苦力怕/玩家）头顶：64×32 或 64×64 皮肤里的像素 u 8..16 / v 0..8。 */
    static final int DEFAULT_U0 = 8;
    static final int DEFAULT_U1 = 16;
    /** 猪灵头是 10 宽的立方体，头顶多占 2 像素。 */
    static final int PIGLIN_U1 = 18;
    static final int V0 = 0;
    static final int V1 = 8;
    /** 龙头上颅（16×16×16，texOffs(112,30)）的 DOWN 面：像素 u 128..144 / v 30..46。 */
    static final int DRAGON_U0 = 128;
    static final int DRAGON_U1 = 144;
    static final int DRAGON_V0 = 30;
    static final int DRAGON_V1 = 46;
    /** 贴图读不到时的兜底灰色：头颅总得在图上留个点。 */
    static final int FALLBACK_COLOR = 0x8A8A8A;

    private SkullSurfaces() {
    }

    /**
     * 一颗头从正上方看到的那一面。永远是平色层，取不到贴图也用兜底灰色。
     */
    public static BlockSurfaces.Surface surface(BlockState state, Level level, BlockPos pos) {
        if (!(state.getBlock() instanceof AbstractSkullBlock skull)) {
            return null;
        }
        AABB box = state.getShape(level, pos).bounds();
        int x0 = clamp((int) Math.round(box.minX * 16.0), 0, 16);
        int z0 = clamp((int) Math.round(box.minZ * 16.0), 0, 16);
        int x1 = clamp((int) Math.round(box.maxX * 16.0), 0, 16);
        int z1 = clamp((int) Math.round(box.maxZ * 16.0), 0, 16);
        float height = (float) Math.min(16.0, Math.round(box.maxY * 16.0));
        if (x1 <= x0 || z1 <= z0) {
            return null;
        }
        SkullBlock.Type type = skull.getType();
        int argb = color(type, level, pos);
        return BlockSurfaces.surfaceOfParts(List.of(BlockSurfaces.Part.flat(argb, x0, z0, x1, z1, height)));
    }

    /** 低缩放平色：同 {@link #surface} 的头顶平均色。 */
    public static int flatColor(BlockState state, Level level, BlockPos pos) {
        if (!(state.getBlock() instanceof AbstractSkullBlock skull)) {
            return -1;
        }
        return color(skull.getType(), level, pos);
    }

    /** 头顶贴图的平均色；贴图缺失/未下载时用兜底灰。 */
    private static int color(SkullBlock.Type type, Level level, BlockPos pos) {
        ResourceLocation texture = texture(type, level, pos);
        if (texture == null) {
            return FALLBACK_COLOR;
        }
        int[] region = region(type);
        Integer sampled = BlockSurfaces.rawTextureColor(texture, region[0], region[1], region[2], region[3]);
        return sampled == null ? FALLBACK_COLOR : sampled;
    }

    /** 头顶在各自贴图里的像素矩形：x0, y0, x1, y1。 */
    static int[] region(SkullBlock.Type type) {
        if (type == SkullBlock.Types.DRAGON) {
            return new int[]{DRAGON_U0, DRAGON_V0, DRAGON_U1, DRAGON_V1};
        }
        if (type == SkullBlock.Types.PIGLIN) {
            return new int[]{DEFAULT_U0, V0, PIGLIN_U1, V1};
        }
        return new int[]{DEFAULT_U0, V0, DEFAULT_U1, V1};
    }

    /** 与 {@code SkullBlockRenderer.getRenderType} 相同的贴图选择（玩家头用名下皮肤）。 */
    @Nullable
    private static ResourceLocation texture(SkullBlock.Type type, Level level, BlockPos pos) {
        if (type == SkullBlock.Types.PLAYER
                && level.getBlockEntity(pos) instanceof SkullBlockEntity skull) {
            ResolvableProfile profile = skull.getOwnerProfile();
            if (profile != null) {
                SkinManager manager = Minecraft.getInstance().getSkinManager();
                return manager.getInsecureSkin(profile.gameProfile()).texture();
            }
        }
        return SkullBlockRenderer.SKIN_BY_TYPE.get(type);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
