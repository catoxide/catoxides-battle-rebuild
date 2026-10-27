package example.contentpack.sablebase.map.client;

import com.catoxide.catoxidesbattlerebuild.core.event.BlockChangeHooks;
import com.catoxide.catoxidesbattlerebuild.core.map.MapApi;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 客户端事件接线：采集 tick、方块变化、退出世界清理。
 * （A7：内容包不使用 {@code @EventBusSubscriber}，全部显式挂载。）
 */
public final class SableMapController {

    private final SubLevelMapCapture capture = new SubLevelMapCapture();
    private final SubLevelMapOverlay overlay = new SubLevelMapOverlay(capture);
    private final SubLevelMapPoints points = new SubLevelMapPoints();

    public SableMapController() {
    }

    public void register(MapApi api) {
        api.addOverlay(this.overlay);
        api.addPointResolver(this.points);

        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> this.capture.tick());
        BlockChangeHooks.register((level, pos, oldState, newState) -> {
            if (level.isClientSide()) {
                this.capture.markDirty(level, pos);
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> this.capture.reset());
    }
}
