package com.catoxide.catoxidesbattlerebuild.mixin;

import com.catoxide.catoxidesbattlerebuild.core.durability.DurabilityCapabilities;
import com.catoxide.catoxidesbattlerebuild.core.durability.IVariableDurability;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 可变耐久 mixin（原版物品兜底）
 * <p>拦截 {@code ItemStack.getMaxDamage()}：若物品注册了可变耐久能力
 * （通过 durability contentpack 注册），返回自定义最大耐久；
 * 无能力时走原版（不改原版行为）。这就是「没有自定义定义时改变原版行为」的机制。
 *
 * <p>未来自定义武器：直接实现 {@link IVariableDurability} 注册到能力即可，
 * 无需依赖本 mixin（mixin 是原版物品的兜底通道）。
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

    @Inject(method = "getMaxDamage()I", at = @At("HEAD"), cancellable = true)
    private void catoxides$onGetMaxDamage(CallbackInfoReturnable<Integer> cir) {
        ItemStack self = (ItemStack) (Object) this;
        IVariableDurability cap = self.getCapability(DurabilityCapabilities.VAR_DURABILITY_CAP, null);
        if (cap != null) {
            int customMax = cap.getMaxDamage(self);
            if (customMax > 0) {
                cir.setReturnValue(customMax);
            }
        }
    }
}
