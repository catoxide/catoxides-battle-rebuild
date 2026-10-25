package example.contentpack.hudlayout.layout;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 原版具名图层 → 分组表（首版只接管两组）。
 *
 * <p>NeoForge 21.1 把 HUD 拆成了单独可寻址的图层（{@link VanillaGuiLayers}），
 * 但原版靠 {@code Gui.leftHeight/rightHeight} 让血量/盔甲/饥饿/空气/载具互相堆叠——
 * 所以它们必须与 hotbar 同组整体平移，单独挪一条会破坏堆叠。
 *
 * <p>未列出的图层（准星、聊天、Boss 条等）不接管：没有编辑手柄，也不做平移。
 */
public final class HudGroups {

    public static final String BOTTOM = "hud_bottom";
    public static final String EFFECTS = "effects";

    /** 编辑屏画手柄的顺序。 */
    public static final List<String> EDITABLE = List.of(BOTTOM, EFFECTS);

    public static final Map<ResourceLocation, String> LAYER_GROUPS;

    static {
        Map<ResourceLocation, String> groups = new LinkedHashMap<>();
        group(groups, BOTTOM,
                VanillaGuiLayers.HOTBAR,
                VanillaGuiLayers.PLAYER_HEALTH,
                VanillaGuiLayers.ARMOR_LEVEL,
                VanillaGuiLayers.FOOD_LEVEL,
                VanillaGuiLayers.AIR_LEVEL,
                VanillaGuiLayers.VEHICLE_HEALTH,
                VanillaGuiLayers.JUMP_METER,
                VanillaGuiLayers.EXPERIENCE_BAR,
                VanillaGuiLayers.EXPERIENCE_LEVEL,
                VanillaGuiLayers.SELECTED_ITEM_NAME,
                VanillaGuiLayers.SPECTATOR_TOOLTIP);
        group(groups, EFFECTS, VanillaGuiLayers.EFFECTS);
        LAYER_GROUPS = Collections.unmodifiableMap(groups);
    }

    private HudGroups() {
    }

    public static String labelKey(String groupId) {
        return "hudlayout.group." + groupId;
    }

    private static void group(Map<ResourceLocation, String> target, String groupId, ResourceLocation... layers) {
        for (ResourceLocation layer : layers) {
            target.put(layer, groupId);
        }
    }
}
