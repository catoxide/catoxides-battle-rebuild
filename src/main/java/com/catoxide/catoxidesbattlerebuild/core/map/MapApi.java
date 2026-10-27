package com.catoxide.catoxidesbattlerebuild.core.map;

/**
 * 地图扩展 API（跨内容包能力，{@link MapCaps#API}）。
 *
 * <p>地图包（map-pack）在 {@code register()} 阶段 provide 本能力；渲染层（如
 * sable-base-pack 的地图联动）消费它，把自己的 overlay 与坐标解析器挂进地图，而<b>不 import
 * 任何 map-pack 实现类</b>（内容包之间不共享类空间）。
 *
 * <p>所有方法只在客户端有实现；服务端没有地图 UI，也不提供本能力。
 */
public interface MapApi {

    /** 注册一个叠加层。同一实例重复注册由实现方去重。 */
    void addOverlay(MapOverlay overlay);

    /** 注册一个坐标解析器（标点锚定 / 甲板高度 / plot 空间识别）。 */
    void addPointResolver(MapPointResolver resolver);

    /**
     * 注册一个玩家皮肤来源（标记头像用）。先注册的来源优先。
     * <p>默认实现不做事，方便旧实现继续编译。
     */
    default void addPlayerSkinSource(IMapPlayerSkinSource source) {
    }
}
