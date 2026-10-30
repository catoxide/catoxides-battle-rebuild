package example.contentpack.mapv2.client;

import com.catoxide.catoxidesbattlerebuild.core.hud.HudConfigOption;
import com.catoxide.catoxidesbattlerebuild.core.hud.HudElement;
import com.catoxide.catoxidesbattlerebuild.core.hud.HudResize;
import example.contentpack.mapv2.MapConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 小地图本体（地形、边框、玩家箭头）接入 HUD 布局系统。
 *
 * <p>与小地图信息条、罗盘<b>各自独立分组</b>：拖动任何一个都只移动它自己。
 * 分组路径（{@link #group()} 的 path）是持久化键，改动等于丢玩家已摆好的位置。
 *
 * <p>本元素还向布局包声明了两件事（{@link #options()} / {@link #resize()}）：
 * 面板里能调的选项，以及用边角拉伸直接改 {@code hud.size}。两处都是声明，
 * 布局包不装或玩家不打开面板时，行为与没有这些声明完全一致。
 */
public final class MinimapHudElement implements HudElement {

    private static final ResourceLocation NAMESPACE = ResourceLocation.fromNamespaceAndPath(
            "map-pack-v2", "minimap");

    public static final ResourceLocation ID = NAMESPACE;
    public static final ResourceLocation GROUP = NAMESPACE;

    private static final String PACK_ID = "map-pack-v2";
    private static final String CONFIG_ID = "map";
    private static final HudConfigOption SIZE = HudConfigOption.of(PACK_ID, CONFIG_ID, "size");

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

    @Override
    public List<HudConfigOption> options() {
        return List.of(
                HudConfigOption.of(PACK_ID, CONFIG_ID, "enabled"),
                SIZE,
                HudConfigOption.of(PACK_ID, CONFIG_ID, "blocksPerPixel"));
    }

    /**
     * 拉伸改的是 {@code hud.size}（64..256 的整数边长），不是给渲染叠一层缩放——
     * 所以没有次像素采样，玩家也拖不出配置没允许的取值。
     *
     * <p>固定角是 {@code TOP_RIGHT}：小地图贴着屏幕右上角，边长变大时<b>左边</b>在动、顶边不动。
     * 这一条只能由元素自己声明，布局包猜不出来；猜反了就是「往右拖反而变小」。
     */
    @Override
    public @Nullable HudResize resize() {
        return HudResize.square(SIZE, HudResize.Anchor.TOP_RIGHT);
    }
}
