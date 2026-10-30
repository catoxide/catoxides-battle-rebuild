package example.contentpack.mapv2.client;

import com.catoxide.catoxidesbattlerebuild.core.hud.HudElement;
import example.contentpack.mapv2.MapConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * 小地图本体（地形、边框、玩家箭头）接入 HUD 布局系统。
 *
 * <p>与小地图信息条、罗盘<b>各自独立分组</b>：拖动任何一个都只移动它自己。
 * 分组路径（{@link #group()} 的 path）是持久化键，改动等于丢玩家已摆好的位置。
 */
public final class MinimapHudElement implements HudElement {

    private static final ResourceLocation NAMESPACE = ResourceLocation.fromNamespaceAndPath(
            "map-pack-v2", "minimap");

    public static final ResourceLocation ID = NAMESPACE;
    public static final ResourceLocation GROUP = NAMESPACE;

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
        // 与 MapMinimap 一致：右上角 6px 边距，外扩 1px 边框
        return new Bounds(screenWidth - size - 7, 5, size + 2, size + 2);
    }
}
