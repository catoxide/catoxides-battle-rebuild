package example.contentpack.mapv2.client;

import com.catoxide.catoxidesbattlerebuild.core.hud.HudConfigOption;
import com.catoxide.catoxidesbattlerebuild.core.hud.HudElement;
import example.contentpack.mapv2.MapConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 小地图下方那三行文字（坐标 / 群系 / 时间）单独登记成 HUD 元素。
 *
 * <p>默认位置贴着 {@link MinimapHudElement}，但分组独立：把小地图挪走或改大小时，
 * 文字不会跟着跑，玩家可以自己摆到别处。
 *
 * <p>群系行和时间行各由一个选项决定开关，所以面板开在本元素上时列的是这两个——
 * 与 {@link MinimapHudElement} 各自声明自己那一块的事，没有一份「地图包总选项表」。
 */
public final class MinimapInfoHudElement implements HudElement {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
            "map-pack-v2", "minimap_info");
    public static final ResourceLocation GROUP = ID;

    private static final int LINE_HEIGHT = 10;

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
        int size = MapConfig.minimapSize();
        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int lines = 1 + (MapConfig.showBiome() ? 1 : 0) + (MapConfig.showTime() ? 1 : 0);
        return new Bounds(screenWidth - size - 6, 6 + size + 3, size, lines * LINE_HEIGHT);
    }

    @Override
    public List<HudConfigOption> options() {
        return List.of(
                HudConfigOption.of("map-pack-v2", "map", "showBiome"),
                HudConfigOption.of("map-pack-v2", "map", "showTime"));
    }
}
