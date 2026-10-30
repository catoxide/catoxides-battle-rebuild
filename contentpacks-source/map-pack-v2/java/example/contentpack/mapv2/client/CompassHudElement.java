package example.contentpack.mapv2.client;

import com.catoxide.catoxidesbattlerebuild.core.hud.HudElement;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * 顶部罗盘接入 HUD 布局系统，分组独立于小地图。
 *
 * <p>旧包里罗盘和小地图同组（一起拖动）；拆分后各自有手柄，互不影响。
 */
public final class CompassHudElement implements HudElement {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
            "map-pack-v2", "compass");
    public static final ResourceLocation GROUP = ID;

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public ResourceLocation group() {
        return GROUP;
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
