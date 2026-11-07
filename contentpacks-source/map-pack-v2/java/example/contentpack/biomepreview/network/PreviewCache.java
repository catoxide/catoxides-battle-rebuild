package example.contentpack.biomepreview.network;

import example.contentpack.biomepreview.preview.PreviewScheduler;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务端采样结果缓存：同一块区域第二次请求直接复用，不再跑采样。
 *
 * <p>只保留最近 {@link #MAX} 块（每块大约几百 KB），LRU 淘汰。
 * 地图拖动、多个玩家看同一片区域都走这里。
 */
final class PreviewCache {

    private static final int MAX = 8;

    private static final Map<Key, PreviewScheduler.Result> CACHE =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Key, PreviewScheduler.Result> eldest) {
                    return size() > MAX;
                }
            };

    private PreviewCache() {
    }

    static PreviewScheduler.Result get(Key key) {
        return CACHE.get(key);
    }

    static void put(Key key, PreviewScheduler.Result result) {
        CACHE.put(key, result);
    }

    record Key(ResourceLocation dimension, int centerX, int centerZ, int radius, int step) {
    }
}
