package example.contentpack.mapv2.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 自绘地图按钮：正常 / 悬停 / 按下三档亮度 + 顶边高光，做出触控反馈。
 *
 * <p>不用原版 Button 是为了控制暗色主题和紧凑尺寸；命中检测直接按矩形。
 */
final class MapUiButton {

    private static final int BACKGROUND = 0xFF232323;
    private static final int BACKGROUND_HOVER = 0xFF343434;
    private static final int BACKGROUND_PRESSED = 0xFF484848;
    private static final int HIGHLIGHT = 0xFF5A5A5A;
    private static final int TEXT = 0xFFD8D8D8;
    private static final int TEXT_HOVER = 0xFFFFFFFF;

    final int x;
    final int y;
    final int width;
    final int height;
    private final String label;
    private final Runnable action;

    MapUiButton(int x, int y, int width, int height, String label, Runnable action) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.label = label;
        this.action = action;
    }

    boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    void render(GuiGraphics graphics, Font font, int mouseX, int mouseY, boolean mouseDown) {
        boolean hover = contains(mouseX, mouseY);
        int background = hover
                ? (mouseDown ? BACKGROUND_PRESSED : BACKGROUND_HOVER)
                : BACKGROUND;
        graphics.fill(x, y, x + width, y + height, background);
        graphics.fill(x, y, x + width, y + 1, hover ? HIGHLIGHT : 0xFF3A3A3A);
        if (hover) {
            graphics.fill(x, y + height - 1, x + width, y + height, 0xFF101010);
        }
        graphics.drawCenteredString(font, label, x + width / 2, y + (height - 8) / 2,
                hover ? TEXT_HOVER : TEXT);
    }

    boolean click(double mouseX, double mouseY, int button) {
        if (button == 0 && contains(mouseX, mouseY)) {
            action.run();
            return true;
        }
        return false;
    }
}
