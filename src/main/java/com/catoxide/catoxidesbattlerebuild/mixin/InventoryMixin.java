package com.catoxide.catoxidesbattlerebuild.mixin;

import com.catoxide.catoxidesbattlerebuild.core.inventory.PlayerInventoryLayout;
import com.catoxide.catoxidesbattlerebuild.core.inventory.PlayerInventoryPolicies;
import com.catoxide.catoxidesbattlerebuild.core.inventory.PlayerInventoryRules;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 玩家物品栏扩容与解锁门槛。
 *
 * <p>扩容只改字段初始化的那一次 {@code NonNullList.withSize(36, …)}：armor/offhand 仍接在后面，
 * 所以容器下标变成 items 0–44、armor 45–48、offhand 49。副手的唯一常量读取点
 * （{@code getSlotWithRemainingSpace} 里的 {@code getItem(40)}）一起改掉；
 * {@code InventoryMenu} 的护甲/副手槽下标由 {@link InventoryMenuMixin} 重映射。
 *
 * <p>门槛：解锁数来自 {@link PlayerInventoryPolicies}，空注册表时所有注入直接返回，
 * 行为与不加 mixin 一致（R3）。mixin 只做查询与回退，解锁规则住内容包。
 */
@Mixin(Inventory.class)
public abstract class InventoryMixin {

    @ModifyArg(
            method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/core/NonNullList;withSize(ILjava/lang/Object;)Lnet/minecraft/core/NonNullList;",
                    ordinal = 0),
            index = 0)
    private static int catoxides$itemSlotCount(int original) {
        return PlayerInventoryLayout.ITEM_SLOTS;
    }

    @ModifyConstant(
            method = "getSlotWithRemainingSpace",
            constant = @Constant(intValue = 40))
    private static int catoxides$offhandContainerSlot(int original) {
        return PlayerInventoryLayout.OFFHAND_SLOT;
    }

    /** 锁定格即使为空也不能被拾取/放置选中。 */
    @Inject(method = "getFreeSlot", at = @At("HEAD"), cancellable = true)
    private void catoxides$firstUnlockedEmptySlot(CallbackInfoReturnable<Integer> cir) {
        if (PlayerInventoryPolicies.isEmpty()) {
            return;
        }
        Inventory self = (Inventory) (Object) this;
        int unlocked = PlayerInventoryPolicies.unlockedItemSlots(self.player);
        for (int i = 0; i < unlocked; i++) {
            if (self.items.get(i).isEmpty()) {
                cir.setReturnValue(i);
                return;
            }
        }
        cir.setReturnValue(-1);
    }

    /** 合并/放入同样只扫解锁格；副手是固定下标，不受行解锁影响。 */
    @Inject(method = "getSlotWithRemainingSpace", at = @At("HEAD"), cancellable = true)
    private void catoxides$unlockedRemainingSpace(ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        if (PlayerInventoryPolicies.isEmpty()) {
            return;
        }
        Inventory self = (Inventory) (Object) this;
        int unlocked = PlayerInventoryPolicies.unlockedItemSlots(self.player);
        if (self.selected >= 0 && self.selected < unlocked
                && hasRemainingSpace(self.items.get(self.selected), stack)) {
            cir.setReturnValue(self.selected);
            return;
        }
        if (hasRemainingSpace(self.getItem(PlayerInventoryLayout.OFFHAND_SLOT), stack)) {
            cir.setReturnValue(PlayerInventoryLayout.OFFHAND_SLOT);
            return;
        }
        for (int i = 0; i < unlocked; i++) {
            if (hasRemainingSpace(self.items.get(i), stack)) {
                cir.setReturnValue(i);
                return;
            }
        }
        cir.setReturnValue(-1);
    }

    /** 明确指定格号的写入（例如 placeItemBackInInventory）也拒绝锁格。 */
    @Inject(method = "add(ILnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
    private void catoxides$rejectLockedSlot(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        Inventory self = (Inventory) (Object) this;
        if (slot >= 0 && PlayerInventoryRules.isLocked(self, slot)) {
            cir.setReturnValue(false);
        }
    }

    private static boolean hasRemainingSpace(ItemStack destination, ItemStack origin) {
        return !destination.isEmpty()
                && ItemStack.isSameItemSameComponents(destination, origin)
                && destination.isStackable()
                && destination.getCount() < destination.getMaxStackSize();
    }
}
