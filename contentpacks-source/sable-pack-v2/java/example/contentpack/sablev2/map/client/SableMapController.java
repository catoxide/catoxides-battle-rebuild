package example.contentpack.sablev2.map.client;

import com.catoxide.catoxidesbattlerebuild.core.event.BlockChangeHooks;
import com.catoxide.catoxidesbattlerebuild.core.map.MapApi;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 客户端事件接线：采集 tick、按需离屏渲染、方块变化、退出世界清理。
 * （A7：内容包不使用 {@code @EventBusSubscriber}，全部显式挂载。）
 *
 * <p>船体图解图层需要 simulated；不可用时只保留标点/甲板解析，不挂 overlay
 * 与渲染事件 —— 即「shutdown 该功能」而不是 shutdown 整个包。
 */
public final class SableMapController {

    private final SubLevelGpuCapture capture = new SubLevelGpuCapture();
    private final SubLevelMapOverlay overlay = new SubLevelMapOverlay(this.capture);
    private final SubLevelMapPoints points = new SubLevelMapPoints();

    public SableMapController() {
    }

    public void register(MapApi api, boolean shipLayer) {
        api.addPointResolver(this.points);
        if (!shipLayer) {
            return;
        }

        api.addOverlay(this.overlay);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> this.capture.tick());
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent event) -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                this.capture.renderPending(
                        event.getPartialTick().getGameTimeDeltaPartialTick(false));
            }
        });
        BlockChangeHooks.register((level, pos, oldState, newState) -> {
            if (level.isClientSide()) {
                this.capture.markDirty(level, pos);
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> this.capture.reset());
    }
}
