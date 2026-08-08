package com.catoxide.catoxidesbattlerebuild.mixin;

import com.catoxide.catoxidesbattlerebuild.core.durability.DurabilityDegradationRegistry;
import com.catoxide.catoxidesbattlerebuild.core.durability.IDurabilityDegradationHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 经验修补降级 mixin（主 mod 能力点）
 * <p>拦截 {@code EnchantmentHelper.modifyDurabilityToRepairFromXp}（每物品 Mending 修复完成），
 * 将处理委托给 durability contentpack 注册的 {@link IDurabilityDegradationHandler}。
 * 无处理器时放行（原版行为）。
 */
@Mixin(net.minecraft.world.item.enchantment.EnchantmentHelper.class)
public abstract class EnchantmentHelperMixin {

    @Inject(method = "modifyDurabilityToRepairFromXp(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;I)I",
            at = @At("RETURN"))
    private static void catoxides$onMendingRepair(ServerLevel level, ItemStack stack, int repairAmount,
                                                   CallbackInfoReturnable<Integer> cir) {
        IDurabilityDegradationHandler handler = DurabilityDegradationRegistry.get();
        if (handler != null) {
            handler.processMendingDegradation(stack, level, repairAmount);
        }
    }
}
