package com.catoxide.catoxidesbattlerebuild.core.map;

import net.minecraft.resources.ResourceLocation;

/**
 * 地图的「群系预测图层」数据源（core ↔ 包 的桥，不含任何实现）。
 *
 * <p>由 biome-preview-pack 之类的包提供，map-pack 只读。实现方自己负责采样、
 * 缓存和网络（服务端算、客户端存），地图侧只做两件事：
 * <ol>
 *   <li>视口变化时调 {@link #ensureArea} 提示需要哪块数据；</li>
 *   <li>渲染未知区域时逐点调 {@link #colorAt} 取预测色。</li>
 * </ol>
 *
 * <p>线程：两个方法都在客户端主线程调用。
 */
public interface IMapBiomeSource {

    /**
     * 地图需要这块区域的数据。实现方可以异步准备/发网络请求，不要求立即可用。
     *
     * @param sizeBlocks 区域边长（方块），以 {@code minBlockX/minBlockZ} 为左上角
     */
    void ensureArea(ResourceLocation dimension, int minBlockX, int minBlockZ, int sizeBlocks);

    /**
     * @return 预测的群系颜色（ARGB）；{@code 0} 表示该点没有预测数据
     */
    int colorAt(ResourceLocation dimension, int blockX, int blockZ);

    /**
     * @return 预测的群系 ID（如 {@code minecraft:plains}）；{@code null} 表示该点没有预测数据
     */
    String biomeIdAt(ResourceLocation dimension, int blockX, int blockZ);

    /** 每次有新数据到达时递增；地图用它判断要不要重建叠加纹理。 */
    long version();
}
