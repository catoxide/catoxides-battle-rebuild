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
 * <p>三行各有独立开关（坐标 / 群系 / 时间），另有对齐与字号选项：
 * 对齐决定文字落在小地图左缘、中线还是右缘，字号是系统 GUI 缩放之上的百分比。
 * 位置偏移仍由 HUD 布局系统提供，本元素只声明自己那块尺寸。
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
        int lines = MinimapInfoLayout.lineCount(
                MapConfig.showCoords(), MapConfig.showBiome(), MapConfig.showTime());
        float textScale = MapConfig.infoTextScale() / 100f;
        int height = Math.round(lines * LINE_HEIGHT * textScale);
        return new Bounds(screenWidth - size - 6, 6 + size + 3, size, height);
    }

    @Override
    public List<HudConfigOption> options() {
        return List.of(
                HudConfigOption.of("map-pack-v2", "map", "showCoords"),
                HudConfigOption.of("map-pack-v2", "map", "showBiome"),
                HudConfigOption.of("map-pack-v2", "map", "showTime"),
                HudConfigOption.of("map-pack-v2", "map", "infoAlign"),
                HudConfigOption.of("map-pack-v2", "map", "infoTextScale"));
    }
}
