package example.contentpack.hudlayout.client;

import com.catoxide.catoxidesbattlerebuild.core.hud.HudElement;
import example.contentpack.hudlayout.layout.HudGroups;
import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * 可拖动组的基准矩形（未加偏移），坐标取自原版绘制代码。
 *
 * <p>返回 {@code null} 表示该组没有可靠位置、编辑屏不给手柄。
 */
public final class HudGroupBounds {

    private HudGroupBounds() {
    }

    public static HudElement.Bounds bounds(String group, int screenWidth, int screenHeight) {
        return switch (group) {
            // Gui#renderItemHotbar（h-22 hotbar）+ 上方经验条/血量/盔甲/物品名（h-29 … h-49）
            case HudGroups.BOTTOM -> new HudElement.Bounds(screenWidth / 2 - 91, screenHeight - 62, 182, 62);
            case HudGroups.EFFECTS -> effectsBounds(screenWidth);
            default -> null;
        };
    }

    private static HudElement.Bounds effectsBounds(int screenWidth) {
        Minecraft minecraft = Minecraft.getInstance();
        int beneficial = 0;
        int harmful = 0;
        if (minecraft.player != null) {
            for (MobEffectInstance effect : minecraft.player.getActiveEffects()) {
                if (!effect.showIcon()) {
                    continue;
                }
                if (effect.getEffect().value().isBeneficial()) {
                    beneficial++;
                } else {
                    harmful++;
                }
            }
        }

        // 原版按 25px 一列从右往左排；有害效果在第二行（+26）。没有效果时给一列占位，便于预先摆位。
        int columns = Math.max(1, Math.max(beneficial, harmful));
        int y = minecraft.isDemo() ? 16 : 1;
        int height = beneficial > 0 && harmful > 0 ? 50 : 24;
        return new HudElement.Bounds(screenWidth - 25 * columns + 1, y, 25 * columns - 1, height);
    }
}
