package example.contentpack.map.client;

import com.catoxide.catoxidesbattlerebuild.core.hud.HudElement;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * 罗盘作为 map-pack 的 HUD 组件接入布局系统（与小地图同组，一起拖动）。
 */
public final class CompassHudElement implements HudElement {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath("map-pack", "compass");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public ResourceLocation group() {
        return MapHudElement.GROUP;
    }

    @Override
    public Bounds bounds() {
        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int barWidth = Math.min(300, screenWidth - 24);
        int left = (screenWidth - barWidth) / 2;
        int top = CompassConfig.thickBar() ? 5 : 8;
        int bottom = CompassConfig.numberOnly() ? top + 18 : (CompassConfig.thickBar() ? 38 : 34);
        return new Bounds(left, top, barWidth, bottom - top);
    }
}
