package com.catoxide.catoxidesbattlerebuild.core.container;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * 容器策略：内容包对「能否往某个容器里放东西」的判定。
 *
 * <p>core 只在插入门槛（{@code Slot#mayPlace} 与 {@code Container#canPlaceItem}）处查询，
 * 不持有任何具体规则。空注册表 = 全部放行 = 原版行为（R3）。
 */
public interface IContainerPolicy {

    /**
     * @return {@code true} 表示这次插入必须被拒绝
     */
    default boolean blocksInsertion(Container container, int slot, ItemStack stack) {
        return false;
    }
}
