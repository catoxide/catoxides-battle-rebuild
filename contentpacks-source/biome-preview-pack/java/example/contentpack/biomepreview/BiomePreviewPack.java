package example.contentpack.biomepreview;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPack;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.core.map.MapBiomeSources;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import example.contentpack.biomepreview.client.MapBiomeClientSource;
import example.contentpack.biomepreview.command.BiomePreviewCommand;
import example.contentpack.biomepreview.network.BiomePreviewNetwork;
import example.contentpack.biomepreview.preview.PreviewScheduler;
import example.contentpack.biomepreview.preview.PreviewSettings;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 地表群系预览包：depth=0 气候采样 + 后台调度 + 吞吐基准。
 * <p>分辨率档位（4/8/16/关）由 {@code config-declarations.json} 声明，进内置配置屏；
 * 采样全部走 {@link PreviewScheduler}，不占服务端 tick。
 * <p>包不能用 {@code @EventBusSubscriber}（FML 只扫主 mod jar），命令显式挂
 * {@link RegisterCommandsEvent}；不装包时 /biomepreview 不存在（R3）。
 */
public final class BiomePreviewPack implements ContentPack {

    private static final String TAG = "BiomePreviewPack";

    @Override
    public String getId() {
        return "biome-preview-pack";
    }

    @Override
    public String getDisplayName() {
        return "Biome Preview";
    }

    @Override
    public String getVersion() {
        return "0.1.0";
    }

    @Override
    public void register(ContentPackContext context) {
        PreviewSettings.bind(context.config("preview"));
        PreviewScheduler.register();
        BiomePreviewNetwork.register(context.getModEventBus());
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                BiomePreviewCommand.register(event.getDispatcher()));
        PreviewAutoTest.register();

        if (CatoxidesBattleRebuildConstants.CLIENT) {
            // 地图（map-pack）通过 core 的注册表拿到这个数据源
            MapBiomeSources.register(MapBiomeClientSource.INSTANCE);
            com.mojang.logging.LogUtils.getLogger()
                    .info("[BiomePreview] map overlay source registered");
        }

        LogManager.serverInfo(TAG, "BiomePreviewPack registered (/biomepreview, map overlay source)");
    }
}
