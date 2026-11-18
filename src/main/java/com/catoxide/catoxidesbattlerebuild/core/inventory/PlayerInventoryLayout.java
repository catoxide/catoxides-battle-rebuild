package com.catoxide.catoxidesbattlerebuild.core.inventory;

/**
 * 玩家物品栏的容器下标布局（core 侧常量，包与 mixin 共用）。
 *
 * <p>1.21.1 原版是 36 格物品 + 4 格护甲 + 1 格副手；本仓库把物品格扩到 45
 * （快捷栏 9 + 背包 4 行 × 9），护甲/副手自然顺延到 45–48 / 49。
 * 下标只在这里定义一次，mixin 与内容包都引用它，避免 36/40 之类的数字散落。
 */
public final class PlayerInventoryLayout {

    public static final int HOTBAR_SLOTS = 9;
    public static final int ROW_SLOTS = 9;
    public static final int BACKPACK_ROWS = 4;
    public static final int ITEM_SLOTS = HOTBAR_SLOTS + ROW_SLOTS * BACKPACK_ROWS;
    public static final int ARMOR_SLOTS = 4;
    public static final int ARMOR_START = ITEM_SLOTS;
    public static final int OFFHAND_SLOT = ITEM_SLOTS + ARMOR_SLOTS;

    private PlayerInventoryLayout() {}

    public static boolean isItemSlot(int containerSlot) {
        return containerSlot >= 0 && containerSlot < ITEM_SLOTS;
    }

    public static boolean isHotbarSlot(int containerSlot) {
        return containerSlot >= 0 && containerSlot < HOTBAR_SLOTS;
    }

    /** 0 = 快捷栏；1..4 = 背包行（9–17、18–26、27–35、36–44）。物品格之外返回 -1。 */
    public static int backpackRow(int containerSlot) {
        if (!isItemSlot(containerSlot) || isHotbarSlot(containerSlot)) {
            return -1;
        }
        return (containerSlot - HOTBAR_SLOTS) / ROW_SLOTS;
    }
}
