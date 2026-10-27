package com.catoxide.catoxidesbattlerebuild.core.map;

/**
 * 地图叠加层。
 *
 * <p>小地图与世界地图都会调用；用 {@link MapRenderContext#fullscreen()} 区分。
 * 调用发生在基础地形之后、标记之前，渲染器已经按地图区域设置好 scissor。
 * 实现方只负责画，不要改动视口中心或缩放。
 */
public interface MapOverlay {

    void render(MapRenderContext context);

    /**
     * 绘制顺序：小的先画（在地形之上、标记之下）。默认 0。
     */
    default int priority() {
        return 0;
    }
}
