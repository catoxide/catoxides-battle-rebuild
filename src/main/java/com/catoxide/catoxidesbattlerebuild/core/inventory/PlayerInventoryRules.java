package com.catoxide.catoxidesbattlerebuild.core.inventory;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;

/**
 * 「这一格现在能不能用」的单一判定口。
 *
 * <p>{@code Slot} 的放入/取出/显示与 {@code Inventory} 的拾取扫描都只问这里，
 * 具体解锁数来自 {@link PlayerInventoryPolicies}（由内容包注册）。没有策略时恒为可用。
 */
public final class PlayerInventoryRules {

    private PlayerInventoryRules() {}

    public static boolean isLocked(Container container, int containerSlot) {
        return container instanceof Inventory inventory && isLocked(inventory, containerSlot);
    }

    public static boolean isLocked(Inventory inventory, int containerSlot) {
        if (inventory == null
                || !PlayerInventoryLayout.isItemSlot(containerSlot)
                || PlayerInventoryPolicies.isEmpty()) {
            return false;
        }
        return containerSlot >= PlayerInventoryPolicies.unlockedItemSlots(inventory.player);
    }
}
