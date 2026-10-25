package example.contentpack.hudlayout.client;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.mojang.blaze3d.platform.InputConstants;
import example.contentpack.hudlayout.layout.HudGroups;
import example.contentpack.hudlayout.layout.LayoutStore;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.LayeredDraw;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端接线：把每个原版具名图层包一层「按组平移」，并提供编辑屏入口。
 *
 * <p>平移用官方 {@link RegisterGuiLayersEvent#wrapLayer}（NeoForge 21.1 起可用），
 * 不需要 mixin；偏移每帧从 {@link LayoutStore} 现读，所以拖动时实时生效。
 */
public final class HudLayoutClient {

    private static final String TAG = "HudLayout";

    public static final KeyMapping OPEN_EDITOR = new KeyMapping(
            "key.hudlayoutpack.open_editor",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            "key.categories.hudlayoutpack");

    private HudLayoutClient() {
    }

    public static void init(IEventBus modBus) {
        modBus.addListener(RegisterKeyMappingsEvent.class, event -> event.register(OPEN_EDITOR));
        modBus.addListener(RegisterGuiLayersEvent.class, HudLayoutClient::wrapLayers);

        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            while (OPEN_EDITOR.consumeClick()) {
                openEditor();
            }
        });
    }

    private static void openEditor() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        if (minecraft.options.hideGui) {
            LogManager.clientWarn(TAG, "HUD is hidden (F1); showing it before opening the layout editor");
            minecraft.options.hideGui = false;
        }
        minecraft.setScreen(new HudEditorScreen());
    }

    private static void wrapLayers(RegisterGuiLayersEvent event) {
        HudGroups.LAYER_GROUPS.forEach((layer, group) -> {
            try {
                event.wrapLayer(layer, original -> (graphics, partialTick) ->
                        renderShifted(graphics, partialTick, original, group));
            } catch (IllegalArgumentException e) {
                LogManager.clientWarn(TAG, "HUD layer '%s' is not registered; left in place (%s)",
                        layer, e.getMessage());
            }
        });
    }

    private static void renderShifted(GuiGraphics graphics, DeltaTracker partialTick,
                                      LayeredDraw.Layer original, String group) {
        int dx = LayoutStore.x(group);
        int dy = LayoutStore.y(group);
        if (dx == 0 && dy == 0) {
            original.render(graphics, partialTick);
            return;
        }

        graphics.pose().pushPose();
        graphics.pose().translate(dx, dy, 0.0F);
        original.render(graphics, partialTick);
        graphics.pose().popPose();
    }
}
