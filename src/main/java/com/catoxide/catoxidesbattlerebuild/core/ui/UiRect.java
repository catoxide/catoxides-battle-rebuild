package com.catoxide.catoxidesbattlerebuild.core.ui;

/**
 * GUI 像素下的矩形（左上角 + 尺寸）。
 *
 * <p>和 {@code GuiGraphics} / {@code Slot.x/y} 同一套坐标：GUI 缩放后的像素，
 * 不涉及窗口像素，也不做 DPI 换算。
 */
public record UiRect(int x, int y, int width, int height) {

    public UiRect offset(int dx, int dy) {
        return new UiRect(x + dx, y + dy, width, height);
    }

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
