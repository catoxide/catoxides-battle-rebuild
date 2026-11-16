package example.contentpack.mapv2.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.ConduitRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ConduitBlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 潮涌核心的上表面。
 *
 * <p>潮涌核心是 {@code ENTITYBLOCK_ANIMATED}，贴图在方块图集里（不是独立图集）。
 * 未激活时渲染 {@code entity/conduit/base} 的 6×6×6 外壳；激活后换成
 * {@code entity/conduit/cage} 的 8×8×8 笼子，并绕斜轴缓慢旋转。
 *
 * <p>取模型 UP 面（渲染器不翻转 Y）：
 * <ul>
 *   <li>外壳 {@code addBox(-3,-3,-3,6,6,6)}、texOffs(0,0)、贴图 32×16：UP 面像素
 *       u 12..18 / v 0..6 → 64 等分 24..36 / 0..24；</li>
 *   <li>笼子 {@code addBox(-4,-4,-4,8,8,8)}：UP 面像素 u 16..24 / v 0..8 →
 *       64 等分 32..48 / 0..32（镂空格栅，平色按贴图均值）。</li>
 * </ul>
 *
 * <p>激活状态只有方块实体自己知道（{@code ConduitBlockEntity.isActive()}），
 * 所以这里需要 level + pos，而不只是方块状态。
 */
public final class ConduitSurfaces {

    /** 外壳 UP 面的六十四等分矩形。 */
    static final int SHELL_U0 = 24;
    static final int SHELL_U1 = 36;
    static final int SHELL_V0 = 0;
    static final int SHELL_V1 = 24;
    /** 激活笼子 UP 面的六十四等分矩形。 */
    static final int CAGE_U0 = 32;
    static final int CAGE_U1 = 48;
    static final int CAGE_V0 = 0;
    static final int CAGE_V1 = 32;
    /** 外壳顶面高度：模型 y -3..3 平移到 5..11。 */
    static final float SHELL_HEIGHT = 11f;
    /** 笼子从上方看到的高度：绕斜轴旋转，取中上沿。 */
    static final float CAGE_HEIGHT = 12f;
    /** 外壳/笼子的格内投影范围（十六等分）。 */
    static final int SHELL_MIN = 5;
    static final int SHELL_MAX = 11;
    static final int CAGE_MIN = 4;
    static final int CAGE_MAX = 12;

    private ConduitSurfaces() {
    }

    /**
     * 潮涌核心从正上方看到的那一面；取不到贴图返回 {@code null}，调用方回落到
     * {@link BlockSurfaces#of} 的平色终点。
     */
    @Nullable
    public static BlockSurfaces.Surface surface(Level level, BlockPos pos) {
        if (Minecraft.getInstance().level == null) {
            return null;
        }
        boolean active = level.getBlockEntity(pos) instanceof ConduitBlockEntity conduit
                && conduit.isActive();
        TextureAtlasSprite sprite = BlockSurfaces.materialSprite(active
                ? ConduitRenderer.ACTIVE_SHELL_TEXTURE : ConduitRenderer.SHELL_TEXTURE);
        if (sprite == null) {
            return null;
        }
        int min = active ? CAGE_MIN : SHELL_MIN;
        int max = active ? CAGE_MAX : SHELL_MAX;
        BlockSurfaces.Part part = new BlockSurfaces.Part(sprite,
                active ? CAGE_U0 : SHELL_U0,
                active ? CAGE_V0 : SHELL_V0,
                active ? CAGE_U1 : SHELL_U1,
                active ? CAGE_V1 : SHELL_V1,
                min, min, max, max, 255,
                active ? CAGE_HEIGHT : SHELL_HEIGHT, 0, -1);
        return BlockSurfaces.surfaceOfParts(List.of(part));
    }

    /** 低缩放平色：当前状态的顶面贴图平均色。 */
    public static int flatColor(Level level, BlockPos pos) {
        BlockSurfaces.Surface surface = surface(level, pos);
        if (surface == null || surface.parts().isEmpty()) {
            return -1;
        }
        return BlockSurfaces.partColor(surface.parts().get(0));
    }
}
