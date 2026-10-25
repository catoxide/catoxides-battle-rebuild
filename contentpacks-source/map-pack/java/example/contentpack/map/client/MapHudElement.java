package example.contentpack.map.client;

import com.catoxide.catoxidesbattlerebuild.core.hud.HudElement;
import example.contentpack.map.MapConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * 把小地图接进 HUD 布局系统（core 静态注册表 + 偏移查询，不依赖 hud-layout-pack）。
 *
 * <p>登记后编辑屏会出现「小地图」手柄；渲染方 {@link MapMinimap} 查询同一份偏移。
 * 未安装 hud-layout-pack 时偏移恒为 0，位置与之前一致（R3）。
 */
public final class MapHudElement implements HudElement {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("map-pack", "minimap");
    public static final ResourceLocation GROUP = ResourceLocation.fromNamespaceAndPath("map-pack", "map");

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
        // 与小地图一致：右上角 6px 边距，外扩 1px 边框
        return new Bounds(screenWidth - size - 7, 5, size + 2, size + 2);
    }
}
