package com.catoxide.catoxidesbattlerebuild.core.map;

import com.catoxide.catoxidesbattlerebuild.core.contentpack.Capability;

/**
 * 地图扩展能力的 token。
 *
 * <p>token 与 {@link MapApi} 必须住在主 mod 包名下（parent-only），
 * 这样提供者与消费者的 Class 对象一致，跨包转换才成立。
 */
public final class MapCaps {

    /** 地图包提供；需要往地图上叠东西的内容包消费。 */
    public static final Capability<MapApi> API = Capability.of("map", "api", MapApi.class);

    private MapCaps() {
    }
}
