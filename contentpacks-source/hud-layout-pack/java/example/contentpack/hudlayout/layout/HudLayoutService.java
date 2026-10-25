package example.contentpack.hudlayout.layout;

import com.catoxide.catoxidesbattlerebuild.core.hud.HudLayoutApi;
import net.minecraft.resources.ResourceLocation;

/**
 * 把 {@link LayoutStore} 接到 core 的 {@link HudLayoutApi} 上，供任何自绘 HUD 的内容包查询。
 * 分组键统一取 {@link ResourceLocation#getPath()}（见 {@link HudGroups} 与各包的 group 常量）。
 */
public final class HudLayoutService implements HudLayoutApi.Provider {

    public static final HudLayoutService INSTANCE = new HudLayoutService();

    private HudLayoutService() {
    }

    @Override
    public int offsetX(ResourceLocation group) {
        return LayoutStore.x(group.getPath());
    }

    @Override
    public int offsetY(ResourceLocation group) {
        return LayoutStore.y(group.getPath());
    }
}
