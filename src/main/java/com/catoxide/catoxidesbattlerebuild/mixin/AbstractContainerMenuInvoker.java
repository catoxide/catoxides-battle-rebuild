package com.catoxide.catoxidesbattlerebuild.mixin;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code AbstractContainerMenu#addSlot} 是 protected，而附属面板由内容包在菜单构造完之后追加槽位。
 * 必须走它而不是直接 {@code slots.add(...)}：它还会登记 {@code lastSlots}/{@code remoteSlots}，
 * 少这两份影子列表的槽位不参与同步。
 */
@Mixin(AbstractContainerMenu.class)
public interface AbstractContainerMenuInvoker {

    @Invoker("addSlot")
    Slot catoxides$addSlot(Slot slot);
}
