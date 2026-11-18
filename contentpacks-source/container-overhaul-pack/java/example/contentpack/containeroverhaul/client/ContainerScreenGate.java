package example.contentpack.containeroverhaul.client;

import example.contentpack.containeroverhaul.ui.SidePanelMounts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;

/**
 * 悬浮物品栏的出场闸门。
 *
 * <p>创造模式物品栏用的是玩家同一份 {@code inventoryMenu}，但它按自己的
 * {@code SlotWrapper} 重排格位、也不该被截底。所以这一屏打开时把 {@link SidePanelMounts#view}
 * 关掉：不挪槽、不画板、不裁底，容量策略回到 45 放行（服务端仍是权威）。
 *
 * <p>已知残留：创造屏会枚举菜单里的全部槽位做包装，追加槽仍会出现在它的物品栏页里
 * （本切片不承诺创造模式物品栏，见 design/INVENTORY_REWRITE_DESIGN.md §6）。
 */
public final class ContainerScreenGate {

    private ContainerScreenGate() {}

    public static void install() {
        SidePanelMounts.setClientGate(menu ->
                !(Minecraft.getInstance().screen instanceof CreativeModeInventoryScreen));
    }
}
