package com.catoxide.catoxidesbattlerebuild.mixin;

import com.catoxide.catoxidesbattlerebuild.core.ui.AuxPanels;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 原版容器菜单在两侧都经 {@code MenuType#create} 构造（客户端
 * {@code MenuScreens.create:127}，服务端 {@code MenuProvider#createMenu}），所以这里是
 * 一个覆盖「所有经类型创建的容器」的公共挂载点，不必按每种容器各写一个钩子。
 *
 * <p>两侧必须走同一路径，否则槽位数不一致会让首包内容同步错位——具体挂哪些菜单、
 * 加几个槽位由 {@link AuxPanels.Attacher} 自己判定，mixin 只负责调用。
 */
@Mixin(MenuType.class)
public abstract class MenuTypeCreateMixin {

    @ModifyReturnValue(
            method = "create(ILnet/minecraft/world/entity/player/Inventory;)Lnet/minecraft/world/inventory/AbstractContainerMenu;",
            at = @At("RETURN"))
    private AbstractContainerMenu catoxides$attachPanels(AbstractContainerMenu menu, int containerId,
                                                         Inventory playerInventory) {
        AuxPanels.attachAll(menu, playerInventory);
        return menu;
    }

    @ModifyReturnValue(
            method = "create(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/network/RegistryFriendlyByteBuf;)Lnet/minecraft/world/inventory/AbstractContainerMenu;",
            at = @At("RETURN"))
    private AbstractContainerMenu catoxides$attachPanelsWithData(AbstractContainerMenu menu, int containerId,
                                                                Inventory playerInventory,
                                                                RegistryFriendlyByteBuf extraData) {
        AuxPanels.attachAll(menu, playerInventory);
        return menu;
    }
}
