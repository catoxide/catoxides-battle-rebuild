package com.catoxide.catoxidesbattlerebuild.core.sable;

/**
 * sub-level 的局部方块包围盒（含端点）。
 *
 * <p>坐标是 plot 局部坐标：方块实际存储的位置，不是世界坐标。
 * 与 {@link SubLevelInfo#pose()} 一起即可完成「局部 ↔ 世界」变换。
 */
public record SubLevelBounds(
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ
) {

    public static final SubLevelBounds EMPTY = new SubLevelBounds(0, 0, 0, -1, -1, -1);

    public boolean isEmpty() {
        return this.maxX < this.minX || this.maxY < this.minY || this.maxZ < this.minZ;
    }

    public boolean contains(double x, double y, double z) {
        return x >= this.minX && x <= this.maxX + 1.0
                && y >= this.minY && y <= this.maxY + 1.0
                && z >= this.minZ && z <= this.maxZ + 1.0;
    }

    public boolean containsColumn(double x, double z) {
        return x >= this.minX && x <= this.maxX + 1.0
                && z >= this.minZ && z <= this.maxZ + 1.0;
    }
}
