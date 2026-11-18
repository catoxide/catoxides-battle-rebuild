package example.contentpack.containeroverhaul.client;

import com.catoxide.catoxidesbattlerebuild.core.ui.UiRect;
import example.contentpack.containeroverhaul.ContainerOverhaulPack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 运行时面板：{@code panel_skin.png} 是一张 16×16 的九宫格原面板（角 4px、边 1px、心 1px），
 * 按目标尺寸现切现拉，板子多大就画多大，不再为每种规格烘一张整板贴图。
 *
 * <p>切法与贴图生产链的 {@code nine_slice} 同一套：四角 1:1，四边沿一个方向最近邻复制，
 * 中心填充。同一份 tokens 生成的 skin 与 {@code board_N.png} 的边框逐像素一致
 * （{@code ui-specs/verify-alignment.mjs} 用 skin 重拼整板来证明这一点）。
 *
 * <p>只管面板框和格底：原版 {@code renderSlot} 在 1.21 只画物品和悬停高亮，不画格底
 * （格底是容器贴图的一部分），所以格子要这里补。物品叠在格底上。
 */
final class PanelPainter {

    private static final ResourceLocation SKIN = ResourceLocation.fromNamespaceAndPath(
            ContainerOverhaulPack.ID, "textures/gui/panel_skin.png");
    private static final ResourceLocation SLOT = ResourceLocation.fromNamespaceAndPath(
            ContainerOverhaulPack.ID, "textures/gui/slot.png");
    /** 九宫格角边长，与元素库 {@code panel.cornerSize} 一致。 */
    private static final int CORNER = 4;
    private static final int TEXTURE = 16;
    /** 格子贴图边长，与元素库 {@code slot.size} 一致。 */
    private static final int SLOT_SIZE = 18;

    private PanelPainter() {}

    static void paint(GuiGraphics graphics, UiRect rect) {
        int x = rect.x();
        int y = rect.y();
        int w = rect.width();
        int h = rect.height();
        if (w < CORNER * 2 || h < CORNER * 2) {
            return;
        }
        int innerW = w - CORNER * 2;
        int innerH = h - CORNER * 2;

        blit(graphics, x, y, CORNER, CORNER, 0, 0, CORNER, CORNER);
        blit(graphics, x + w - CORNER, y, CORNER, CORNER, TEXTURE - CORNER, 0, CORNER, CORNER);
        blit(graphics, x, y + h - CORNER, CORNER, CORNER, 0, TEXTURE - CORNER, CORNER, CORNER);
        blit(graphics, x + w - CORNER, y + h - CORNER, CORNER, CORNER,
                TEXTURE - CORNER, TEXTURE - CORNER, CORNER, CORNER);

        blit(graphics, x + CORNER, y, innerW, CORNER, CORNER, 0, 1, CORNER);
        blit(graphics, x + CORNER, y + h - CORNER, innerW, CORNER, CORNER, TEXTURE - CORNER, 1, CORNER);
        blit(graphics, x, y + CORNER, CORNER, innerH, 0, CORNER, CORNER, 1);
        blit(graphics, x + w - CORNER, y + CORNER, CORNER, innerH, TEXTURE - CORNER, CORNER, CORNER, 1);

        blit(graphics, x + CORNER, y + CORNER, innerW, innerH, CORNER, CORNER, 1, 1);
    }

    /** 格底：18×18 原版 slot 精灵 1:1 画在格点上（格点 = 槽位位置左上各 1px）。 */
    static void paintSlot(GuiGraphics graphics, int x, int y) {
        graphics.blit(SLOT, x, y, 0.0F, 0.0F, SLOT_SIZE, SLOT_SIZE, SLOT_SIZE, SLOT_SIZE);
    }

    private static void blit(GuiGraphics graphics, int x, int y, int width, int height,
                             int u, int v, int uWidth, int vHeight) {
        graphics.blit(SKIN, x, y, width, height, (float) u, (float) v,
                uWidth, vHeight, TEXTURE, TEXTURE);
    }
}
