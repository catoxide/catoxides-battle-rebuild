package example.contentpack.mapv2.client;

import com.catoxide.catoxidesbattlerebuild.core.map.IMapBiomeSource;
import com.catoxide.catoxidesbattlerebuild.core.map.MapBiomeSources;
import com.mojang.logging.LogUtils;
import example.contentpack.mapv2.MapConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

/**
 * 群系预测叠加层的薄封装。
 *
 * <p>数据源由 {@code MapV2Pack} 通过 core 的 {@link MapBiomeSources} 注册（同包的
 * {@code biomepreview} 模块）；没有数据源时全部返回 0，地图行为与之前完全一致。
 *
 * <p>渲染约定：预测色只填「未采集」像素，具体画法（柔和填色 + 描边）在
 * {@link BiomeOverlay}，这样一眼能区分「预测」和「真的走过」。
 *
 * <p>这里只回答<b>预测</b>。客户端已加载的区块有真实群系，走 {@link MapProbe}；
 * 谁需要「已确认优先」，谁自己先判 {@link MapProbe#isLoaded} 再回落到这里。
 */
final class MapBiomeLayer {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static long lastLogNanos;

    private MapBiomeLayer() {
    }

    static void ensureVisible(ResourceLocation dimension, int minBlockX, int minBlockZ, int span) {
        if (!MapConfig.showBiomePrediction()) {
            return;
        }
        IMapBiomeSource source = MapBiomeSources.get();
        if (source == null) {
            logThrottled("[MapPack] prediction enabled but no biome source registered");
            return;
        }
        source.ensureArea(dimension, minBlockX, minBlockZ, span);
        logThrottled("[MapPack] prediction ensure dim=" + dimension + " span=" + span
                + " source=" + source.getClass().getSimpleName());
    }

    private static void logThrottled(String message) {
        long now = System.nanoTime();
        if (now - lastLogNanos > 5_000_000_000L) {
            lastLogNanos = now;
            LOGGER.info(message);
        }
    }

    static int colorAt(ResourceLocation dimension, int blockX, int blockZ) {
        if (!MapConfig.showBiomePrediction()
                || !MapRevealClient.isRevealed(dimension, blockX, blockZ)) {
            return 0;
        }
        IMapBiomeSource source = MapBiomeSources.get();
        return source == null ? 0 : source.colorAt(dimension, blockX, blockZ);
    }

    static long version() {
        IMapBiomeSource source = MapBiomeSources.get();
        return source == null ? 0L : source.version();
    }

    /** 预测群系 ID；null = 没有预测数据、功能关闭，或不在服务端探明范围内。 */
    static String biomeIdAt(ResourceLocation dimension, int blockX, int blockZ) {
        if (!MapConfig.showBiomePrediction()
                || !MapRevealClient.isRevealed(dimension, blockX, blockZ)) {
            return null;
        }
        IMapBiomeSource source = MapBiomeSources.get();
        return source == null ? null : source.biomeIdAt(dimension, blockX, blockZ);
    }

    /** 预测群系的本地化名；null = 没有预测数据、功能关闭，或不在探明范围内。 */
    static String biomeNameAt(ResourceLocation dimension, int blockX, int blockZ) {
        if (!MapConfig.showBiomePrediction() || !MapConfig.predictionHover()
                || !MapRevealClient.isRevealed(dimension, blockX, blockZ)) {
            return null;
        }
        IMapBiomeSource source = MapBiomeSources.get();
        if (source == null) {
            return null;
        }
        String id = source.biomeIdAt(dimension, blockX, blockZ);
        if (id == null || id.isBlank()) {
            return null;
        }
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) {
            return id;
        }
        return Component.translatable(
                "biome." + location.getNamespace() + "." + location.getPath()).getString();
    }

}
