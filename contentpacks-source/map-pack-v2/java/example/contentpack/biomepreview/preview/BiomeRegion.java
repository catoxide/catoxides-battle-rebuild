package example.contentpack.biomepreview.preview;

import net.minecraft.resources.ResourceLocation;

/**
 * 一块服务端算好的群系预测图（正方形采样网格，圆外格子为 {@link #UNKNOWN}）。
 *
 * <p>圆心 = {@code (centerX, centerZ)}，采样点 = {@code center - radius + i * step}，
 * 所以 {@code size == 2 * radius / step + 1}。{@code colors} 与 {@code biomeIds}
 * 是同一调色板的两种表示，{@code cells} 存调色板下标。
 */
public record BiomeRegion(ResourceLocation dimension, int centerX, int centerZ,
                          int radius, int step, int size,
                          int[] colors, String[] biomeIds, short[] cells) {

    /** 圆形范围之外的格子。旧数据没有这个哨兵，0 仍然是一个合法调色板下标。 */
    public static final short UNKNOWN = -1;

    public BiomeRegion {
        radius = Math.max(1, radius);
        step = Math.max(1, step);
        size = Math.max(0, size);
        if (cells.length != size * size) {
            throw new IllegalArgumentException(
                    "cells length " + cells.length + " does not match size " + size);
        }
    }

    public int span() {
        return size * step;
    }

    public boolean contains(int blockX, int blockZ) {
        int dx = blockX - centerX;
        int dz = blockZ - centerZ;
        return Math.abs(dx) <= radius && Math.abs(dz) <= radius;
    }

    /** 采样点下标；不在方形区域内返回 -1（调用方再用 {@link #paletteIndexAt} 判圆）。 */
    public int cellIndexAt(int blockX, int blockZ) {
        int dx = blockX - centerX + radius;
        int dz = blockZ - centerZ + radius;
        if (dx < 0 || dz < 0 || dx > radius * 2 || dz > radius * 2) {
            return -1;
        }
        int col = dx / step;
        int row = dz / step;
        if (col >= size || row >= size) {
            return -1;
        }
        return row * size + col;
    }

    /** 调色板下标；方形外、圆外或越界都返回 {@link #UNKNOWN}。 */
    public int paletteIndexAt(int blockX, int blockZ) {
        int index = cellIndexAt(blockX, blockZ);
        if (index < 0) {
            return UNKNOWN;
        }
        short value = cells[index];
        return value < 0 ? UNKNOWN : value;
    }

    public int colorAt(int blockX, int blockZ) {
        int paletteIndex = paletteIndexAt(blockX, blockZ);
        if (paletteIndex < 0 || paletteIndex >= colors.length) {
            return 0;
        }
        return colors[paletteIndex];
    }

    public String biomeIdAt(int blockX, int blockZ) {
        int paletteIndex = paletteIndexAt(blockX, blockZ);
        if (paletteIndex < 0 || paletteIndex >= biomeIds.length) {
            return null;
        }
        return biomeIds[paletteIndex];
    }
}
