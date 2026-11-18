package com.catoxide.catoxidesbattlerebuild.core.container;

import net.minecraft.world.Container;

/**
 * 双箱（{@code CompoundContainer}）两半的只读视图。
 *
 * <p>原版把双箱两半包在一个 {@code CompoundContainer} 里，字段私有；
 * 内容包要判断「其中一半是不是战利品箱」只能经此接口。
 */
public interface ICompoundContainerAccess {

    Container catoxides$firstHalf();

    Container catoxides$secondHalf();
}
