package example.contentpack.containeroverhaul.client;

import com.catoxide.catoxidesbattlerebuild.core.ui.ScreenCuts;
import example.contentpack.containeroverhaul.ContainerOverhaulPack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * 截底的底边条：把面板「凭空截断」的那一刀收成原版面板的底边。
 *
 * <p>{@code floor_<宽>.png} 是一张 9px 高的原版面板（九宫格），只取最底下 4 行 ——
 * body / 暗棱 / 暗棱 / 黑边，左右自带原版内凹的底角。画在可见高度再往下的 4px 上
 * （裁剪之外，不会盖住还没被裁掉的内容），于是被截掉的下半块看不见，面板在这里自然收口。
 *
 * <p>宽度必须和该屏原图一致（176 默认 / 230 信标 / 256 附魔台），像素不做拉伸；
 * 没有对应宽度的屏就不画，退回硬裁（R3）。内容包没登记截底的屏也不画。
 */
final class ContainerFloorComponent implements ContainerUiComponent {

    /** 条带源图里「底边 4 行」的起点（9px 九宫格的最后 4 行）。 */
    private static final int SOURCE_TOP = 5;
    private static final int STRIP_HEIGHT = 4;
    private static final int SOURCE_HEIGHT = SOURCE_TOP + STRIP_HEIGHT;

    private static final int[] WIDTHS = {176, 230, 256};
    private static final ResourceLocation[] TEXTURES = new ResourceLocation[WIDTHS.length];

    static {
        for (int i = 0; i < WIDTHS.length; i++) {
            TEXTURES[i] = ResourceLocation.fromNamespaceAndPath(
                    ContainerOverhaulPack.ID, "textures/gui/floor_" + WIDTHS[i] + ".png");
        }
    }

    @Override
    public boolean present(AbstractContainerScreen<?> screen) {
        return ScreenCuts.visibleHeight(screen) != null && texture(screen.getXSize()) != null;
    }

    @Override
    public void render(GuiGraphics graphics, AbstractContainerScreen<?> screen) {
        Integer visible = ScreenCuts.visibleHeight(screen);
        ResourceLocation texture = texture(screen.getXSize());
        if (visible == null || texture == null) {
            return;
        }
        graphics.blit(texture, screen.getGuiLeft(), screen.getGuiTop() + visible,
                0.0F, (float) SOURCE_TOP, screen.getXSize(), STRIP_HEIGHT,
                screen.getXSize(), SOURCE_HEIGHT);
    }

    private static @Nullable ResourceLocation texture(int width) {
        for (int i = 0; i < WIDTHS.length; i++) {
            if (WIDTHS[i] == width) {
                return TEXTURES[i];
            }
        }
        return null;
    }
}
