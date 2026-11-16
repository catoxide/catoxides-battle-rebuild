package com.catoxide.catoxidesbattlerebuild.mixin;

import com.catoxide.catoxidesbattlerebuild.core.container.ContainerPolicies;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 插入门槛（GUI 路径）：玩家点击 / Shift 点击落进槽位前查询内容包策略。
 *
 * <p>原版 {@code Slot#mayPlace} 默认恒为 {@code true}，不询问所属容器，
 * 所以只拦 {@code Container#canPlaceItem} 挡不住界面内的放入，必须在这里也拦一次。
 *
 * <p>R1：只查询 + 回退；R3：策略表为空时原样返回 {@code true}。
 */
@Mixin(Slot.class)
public abstract class SlotInsertGuardMixin {

    @Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
    private void catoxides$guardInsertion(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        Slot self = (Slot) (Object) this;
        if (ContainerPolicies.blocksInsertion(self.container, self.getContainerSlot(), stack)) {
            cir.setReturnValue(false);
        }
    }
}
