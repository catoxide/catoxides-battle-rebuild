package com.catoxide.catoxidesbattlerebuild.mixin;

import com.catoxide.catoxidesbattlerebuild.core.container.ContainerPolicies;
import com.catoxide.catoxidesbattlerebuild.core.inventory.PlayerInventoryRules;
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
 * <p>同时补玩家物品栏的锁定门槛（{@link PlayerInventoryRules}）：
 * 锁住的物品格不能放入、不能取出、也不显示（{@code isActive}）。
 *
 * <p>R1：只查询 + 回退；R3：策略表为空时原样返回。
 */
@Mixin(Slot.class)
public abstract class SlotInsertGuardMixin {

    @Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
    private void catoxides$guardInsertion(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        Slot self = (Slot) (Object) this;
        if (ContainerPolicies.blocksInsertion(self.container, self.getContainerSlot(), stack)
                || PlayerInventoryRules.isLocked(self.container, self.getContainerSlot())) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void catoxides$guardPickup(net.minecraft.world.entity.player.Player player,
                                       CallbackInfoReturnable<Boolean> cir) {
        Slot self = (Slot) (Object) this;
        if (PlayerInventoryRules.isLocked(self.container, self.getContainerSlot())) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "isActive", at = @At("HEAD"), cancellable = true)
    private void catoxides$hideLocked(CallbackInfoReturnable<Boolean> cir) {
        Slot self = (Slot) (Object) this;
        if (PlayerInventoryRules.isLocked(self.container, self.getContainerSlot())) {
            cir.setReturnValue(false);
        }
    }
}
