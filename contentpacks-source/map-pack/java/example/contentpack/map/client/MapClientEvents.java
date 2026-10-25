package example.contentpack.map.client;

import com.catoxide.catoxidesbattlerebuild.core.event.BlockChangeHooks;
import com.mojang.blaze3d.platform.InputConstants;
import example.contentpack.map.MapCapture;
import example.contentpack.map.MapStorage;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端事件接线（A7：内容包不能用 {@code @EventBusSubscriber}，全部显式挂载）。
 *
 * <ul>
 *   <li>按键注册 → mod bus（{@link RegisterKeyMappingsEvent}）</li>
 *   <li>M 键消费 + 采集调度 → 全局总线 {@link ClientTickEvent.Post}</li>
 *   <li>小地图绘制 → 全局总线 {@link RenderGuiEvent.Post}</li>
 * </ul>
 *
 * <p>没有 mixin：采集读客户端已加载区块，渲染走 HUD/Screen，都在公共 API 范围内。
 */
public final class MapClientEvents {

    public static final KeyMapping OPEN_MAP = new KeyMapping(
            "key.catoxidesbattlerebuild.open_map",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            "key.categories.catoxidesbattlerebuild.map");

    private MapClientEvents() {
    }

    public static void init(IEventBus modBus) {
        modBus.addListener(RegisterKeyMappingsEvent.class, event -> event.register(OPEN_MAP));

        // 客户端方块变化 → 只重采受影响区块（放置/破坏/流体等即时反映）
        BlockChangeHooks.register((level, pos, oldState, newState) -> {
            if (level.isClientSide()) {
                MapCapture.markDirty(level, pos);
            }
        });

        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            Minecraft minecraft = Minecraft.getInstance();
            while (OPEN_MAP.consumeClick()) {
                if (minecraft.level != null && minecraft.player != null && minecraft.screen == null) {
                    minecraft.setScreen(new MapScreen());
                }
            }
            MapCapture.tick(minecraft);
            MapWorld.tickScreen();
        });

        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post event) ->
                MapMinimap.render(event.getGuiGraphics()));

        // 正式退出世界时把剩余脏瓦片全部提交（关游戏可能不经过 tick，这里是兜底）
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            MapStorage.flushAll();
            MapStorage.clear();
        });
    }
}
