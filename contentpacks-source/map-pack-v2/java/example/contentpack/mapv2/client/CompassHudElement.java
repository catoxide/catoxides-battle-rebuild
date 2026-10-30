package example.contentpack.mapv2.client;

import com.catoxide.catoxidesbattlerebuild.core.hud.HudConfigOption;
import com.catoxide.catoxidesbattlerebuild.core.hud.HudElement;
import com.catoxide.catoxidesbattlerebuild.core.hud.HudResize;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 顶部罗盘接入 HUD 布局系统，分组独立于小地图。
 *
 * <p>旧包里罗盘和小地图同组（一起拖动）；拆分后各自有手柄，互不影响。
 *
 * <p>还向布局包声明了罗盘的配置选项与宽度拉伸（见 {@link #options()} / {@link #resize()}）：
 * 面板里的每一行都直接读写 {@code map-pack-v2} 的 compass 分区，改完同帧生效。
 */
public final class CompassHudElement implements HudElement {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
            "map-pack-v2", "compass");
    public static final ResourceLocation GROUP = ID;

    private static final String PACK_ID = "map-pack-v2";
    private static final String CONFIG_ID = "map";
    private static final HudConfigOption WIDTH = HudConfigOption.of(PACK_ID, CONFIG_ID, "width");

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
        int barWidth = CompassConfig.barWidth(screenWidth);
        int left = (screenWidth - barWidth) / 2;
        int top = CompassConfig.thickBar() ? 5 : 8;
        int bottom = CompassConfig.numberOnly() ? top + 18 : (CompassConfig.thickBar() ? 38 : 34);
        return new Bounds(left, top, barWidth, bottom - top);
    }

    @Override
    public List<HudConfigOption> options() {
        return List.of(
                HudConfigOption.of(PACK_ID, CONFIG_ID, "numberOnly"),
                HudConfigOption.of(PACK_ID, CONFIG_ID, "thickBar"),
                WIDTH,
                HudConfigOption.of(PACK_ID, CONFIG_ID, "frostedBackground"),
                HudConfigOption.of(PACK_ID, CONFIG_ID, "fovTicks"),
                HudConfigOption.of(PACK_ID, CONFIG_ID, "alignMode"),
                HudConfigOption.of(PACK_ID, CONFIG_ID, "referenceFov"),
                HudConfigOption.of(PACK_ID, CONFIG_ID, "compassScale"),
                HudConfigOption.of(PACK_ID, CONFIG_ID, "markerScale"),
                HudConfigOption.of(PACK_ID, CONFIG_ID, "textStyle"));
    }

    /**
     * 罗盘横向居中，严格说没有「留在原地」的角——但手柄在右端、写回的是条宽，
     * 每帧按宽度重新居中后，右端正好停在鼠标处（左端同步往左让），拖起来就是「从中心向两侧展开」。
     */
    @Override
    public @Nullable HudResize resize() {
        return new HudResize(WIDTH, HudResize.Mode.WIDTH, HudResize.Anchor.TOP_LEFT);
    }
}
