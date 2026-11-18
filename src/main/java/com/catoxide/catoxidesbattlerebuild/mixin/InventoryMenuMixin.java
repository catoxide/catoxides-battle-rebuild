package com.catoxide.catoxidesbattlerebuild.mixin;

import com.catoxide.catoxidesbattlerebuild.core.inventory.PlayerInventoryLayout;
import com.catoxide.catoxidesbattlerebuild.core.ui.AuxPanels;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 玩家物品栏菜单不走 {@code MenuType#create}：客户端在 {@code LocalPlayer} 构造器里直接 new，
 * 服务端在 {@code ServerPlayer} 构造器里直接 new。这里补上同一个挂载点。
 *
 * <p>同时把护甲/副手槽的容器下标从原版的 36–39 / 40 重映射到 45–48 / 49。
 * 只改这个构造器里的两处常量，不做全局重映射 —— 新背包格 36–44 本身也是物品格，
 * 按范围重映射会误伤。
 */
@Mixin(InventoryMenu.class)
public abstract class InventoryMenuMixin {

    @Inject(method = "<init>", at = @At("TAIL"))
    private void catoxides$attachPanels(Inventory playerInventory, boolean active, Player owner, CallbackInfo ci) {
        AuxPanels.attachAll((AbstractContainerMenu) (Object) this, playerInventory);
    }

    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 39))
    private int catoxides$armorContainerSlot(int original) {
        return PlayerInventoryLayout.ARMOR_START + 3;
    }

    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 40))
    private int catoxides$offhandContainerSlot(int original) {
        return PlayerInventoryLayout.OFFHAND_SLOT;
    }
}
