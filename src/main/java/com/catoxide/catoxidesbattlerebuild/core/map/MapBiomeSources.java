package com.catoxide.catoxidesbattlerebuild.core.map;

/**
 * 群系预测图层数据源的注册表（单提供者）。
 *
 * <p>R3：没有包注册时 {@link #get()} 返回 {@code null}，地图侧直接跳过叠加层，
 * 等同于功能不存在。
 *
 * <p>todo:deprecated（2026-10-26 定标）—— 联动已收敛为 Capability 单路线
 * （design/PACK_DEPS_AND_LINKAGE.md §3 形态 B）；本类照常工作但禁止新增使用者，迁移时机由用户定。
 */
public final class MapBiomeSources {

    private static volatile IMapBiomeSource source;

    private MapBiomeSources() {
    }

    public static void register(IMapBiomeSource provider) {
        source = provider;
    }

    public static void unregister(IMapBiomeSource provider) {
        if (source == provider) {
            source = null;
        }
    }

    public static IMapBiomeSource get() {
        return source;
    }
}
