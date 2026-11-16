package example.contentpack.hudlayout.ui;

import com.catoxide.catoxidesbattlerebuild.core.ui.IScreenPanelAccess;
import com.catoxide.catoxidesbattlerebuild.core.ui.ScreenLayoutHooks;
import com.catoxide.catoxidesbattlerebuild.core.ui.ScreenPanels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * 把配置里的偏移/锚定交给 core 的 mixin：mixin 只问「移多少」，算法住这里。
 *
 * <p>同时是效果列纵向锚定的实现方（{@link ScreenLayoutHooks.Effects}）。
 */
public final class UiLayoutService implements ScreenLayoutHooks.Effects {

    public static final UiLayoutService INSTANCE = new UiLayoutService();

    private UiLayoutService() {
    }

    @Override
    public int shiftY(AbstractContainerScreen<?> screen) {
        UiEffectsAnchor effects = UiLayoutStore.get(ScreenPanels.idFor(screen)).effects();
        if (effects == null) {
            return 0;
        }

        Minecraft minecraft = Minecraft.getInstance();
        int count = minecraft.player == null ? 0 : minecraft.player.getActiveEffects().size();
        return effects.shiftY(((IScreenPanelAccess) screen).catoxides$panelTop(), screen.getYSize(), count);
    }
}
