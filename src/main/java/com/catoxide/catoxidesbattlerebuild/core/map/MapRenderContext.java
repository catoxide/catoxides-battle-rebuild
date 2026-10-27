package com.catoxide.catoxidesbattlerebuild.core.map;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 一次地图叠加渲染的视口信息。
 *
 * <p>坐标映射与基础地形瓦片一致：屏幕像素 {@code (left, top)} 对应世界
 * {@code (minWorldX, minWorldZ)}，每像素 {@link #blocksPerPixel()} 格。
 *
 * <p>只在客户端使用。
 */
public record MapRenderContext(
        GuiGraphics graphics,
        boolean fullscreen,
        int left,
        int top,
        int size,
        int blocksPerPixel,
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

    /** 屏幕矩形是否与地图视口相交（用于廉价裁剪）。 */
    public boolean intersects(double x1, double z1, double x2, double z2) {
        double minX = Math.min(x1, x2);
        double maxX = Math.max(x1, x2);
        double minZ = Math.min(z1, z2);
        double maxZ = Math.max(z1, z2);
        return maxX >= this.left && minX <= this.left + this.size
                && maxZ >= this.top && minZ <= this.top + this.size;
    }
}
