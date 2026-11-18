package com.catoxide.catoxidesbattlerebuild.core.inventory;

import net.minecraft.world.entity.player.Player;

/**
 * 玩家物品栏的解锁策略：内容包决定这名玩家能用多少个物品格。
 *
 * <p>core 只把数字拿去当门槛（拾取扫描、槽位放入/取出/显示），不关心解锁来源。
 * 返回值会被夹到 {@code 0..PlayerInventoryLayout.ITEM_SLOTS}；注册表为空时按全解锁处理（R3）。
 */
public interface IPlayerInventoryPolicy {

    /** 可用的物品格数（含快捷栏）。 */
    int unlockedItemSlots(Player player);
}
