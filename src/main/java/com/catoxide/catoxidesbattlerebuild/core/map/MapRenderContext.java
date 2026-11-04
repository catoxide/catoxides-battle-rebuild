package com.catoxide.catoxidesbattlerebuild.core.map;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 一次地图叠加渲染的视口信息。
 *
 * <p>坐标映射与基础地形瓦片一致：屏幕 {@code (left, top)} 对应世界
 * {@code (minWorldX, minWorldZ)}，每像素 {@link #blocksPerPixel()} 格。
 *
 * <p>视口是矩形而不是正方形（世界地图按屏幕比例铺满）；坐标单位由调用方决定：
 * 小地图用 GUI 像素，全屏地图用画布像素（已按 1/guiScale 缩放后的帧缓冲像素）。
 * 叠加层只做「世界坐标 → 屏幕坐标」的算术，不关心单位。
 *
 * <p>只在客户端使用。
 */
public record MapRenderContext(
        GuiGraphics graphics,
        boolean fullscreen,
        int left,
        int top,
        int viewWidth,
        int viewHeight,
        double blocksPerPixel,
        double minWorldX,
        double minWorldZ,
        ResourceLocation dimension,
        float partialTick
) {

    public double screenX(double worldX) {
        return this.left + (worldX - this.minWorldX) / this.blocksPerPixel;
    }

    public double screenZ(double worldZ) {
        return this.top + (worldZ - this.minWorldZ) / this.blocksPerPixel;
    }

    public double pixelsPerBlock() {
        return 1.0 / this.blocksPerPixel;
    }

    public int right() {
        return this.left + this.viewWidth;
    }

    public int bottom() {
        return this.top + this.viewHeight;
    }

    /** 屏幕矩形是否与地图视口相交（用于廉价裁剪）。 */
    public boolean intersects(double x1, double z1, double x2, double z2) {
        double minX = Math.min(x1, x2);
        double maxX = Math.max(x1, x2);
        double minZ = Math.min(z1, z2);
        double maxZ = Math.max(z1, z2);
        return maxX >= this.left && minX <= this.right()
                && maxZ >= this.top && minZ <= this.bottom();
    }
}
