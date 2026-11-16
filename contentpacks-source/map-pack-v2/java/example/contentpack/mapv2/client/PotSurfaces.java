package example.contentpack.mapv2.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 装饰陶罐的上表面。
 *
 * <p>陶罐是 {@code INVISIBLE} 方块，模型在 {@code DecoratedPotRenderer} 里、贴图在
 * 独立的 decorated_pot 图集。罐身平顶是 {@code "top"} 部件：{@code texOffs(-14,13)}
 * 的零厚板 {@code addBox(0,0,0,14,0,14)}，位姿 {@code (1,16,1)} —— 正好铺在格内
 * 1..15、高度 16/16。
 *
 * <p>零厚板的 UP/DOWN 两面重合，渲染器两面都画。从上看看到的是 UP 面：
 * {@code u+dz+dx..u+dz+2dx}、{@code v+dz..v}，即像素 u 14..28 / v 13..27，贴图 32×32
 * ⇒ 六十四等分 28..56 / 26..54（两张面在贴图里同色，取哪面都不影响观感）。
 *
 * <p>罐口还有一圈高 17..21 的凸沿，从上看会盖住中间 8×8；那只是轮廓，按用户口径
 * 「罐子只做一个顶面」，这里只画罐盖，不做凸沿。
 */
public final class PotSurfaces {

    /** 罐盖 UP 面在 32×32 贴图里的六十四等分矩形。 */
    static final int TOP_U0 = 28;
    static final int TOP_U1 = 56;
    static final int TOP_V0 = 26;
    static final int TOP_V1 = 54;
    /** 罐盖所在的格子高度：模型 y=16/16。 */
    static final float TOP_HEIGHT = 16f;
    /** 罐盖投影：模型 x/z 1..15（十六等分）。 */
    static final int TOP_MIN = 1;
    static final int TOP_MAX = 15;

    private PotSurfaces() {
    }

    /**
     * 一只陶罐从正上方看到的那一面；取不到贴图（图集还没就绪）返回 {@code null}，
     * 调用方回落到 {@link BlockSurfaces#of} 的平色终点。
     */
    @Nullable
    public static BlockSurfaces.Surface surface() {
        if (Minecraft.getInstance().level == null) {
            return null;
        }
        TextureAtlasSprite sprite = BlockSurfaces.materialSprite(Sheets.DECORATED_POT_BASE);
        if (sprite == null) {
            return null;
        }
        BlockSurfaces.Part part = new BlockSurfaces.Part(sprite,
                TOP_U0, TOP_V0, TOP_U1, TOP_V1,
                TOP_MIN, TOP_MIN, TOP_MAX, TOP_MAX, 255, TOP_HEIGHT, 0, -1);
        return BlockSurfaces.surfaceOfParts(List.of(part));
    }

    /** 低缩放平色：罐盖贴图的平均色。 */
    public static int flatColor() {
        BlockSurfaces.Surface surface = surface();
        if (surface == null || surface.parts().isEmpty()) {
            return -1;
        }
        return BlockSurfaces.partColor(surface.parts().get(0));
    }
}
