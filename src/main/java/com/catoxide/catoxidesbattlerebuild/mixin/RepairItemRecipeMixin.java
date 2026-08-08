package com.catoxide.catoxidesbattlerebuild.mixin;

import com.catoxide.catoxidesbattlerebuild.core.durability.DurabilityDegradationRegistry;
import com.catoxide.catoxidesbattlerebuild.core.durability.IDurabilityDegradationHandler;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RepairItemRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 合成台修复降级 mixin（主 mod 能力点）
 * <p>拦截 {@code RepairItemRecipe.assemble}（合成台物品修复结果生成后），
 * 将处理委托给 durability contentpack 注册的 {@link IDurabilityDegradationHandler}。
 * 无处理器时放行（原版行为）。
 */
@Mixin(RepairItemRecipe.class)
public abstract class RepairItemRecipeMixin {

    @Inject(method = "assemble(Lnet/minecraft/world/item/crafting/CraftingInput;Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("RETURN"))
    private void catoxides$onCraftingRepair(CraftingInput input, HolderLookup.Provider provider,
                                             CallbackInfoReturnable<ItemStack> cir) {
        IDurabilityDegradationHandler handler = DurabilityDegradationRegistry.get();
        if (handler == null) {
            return;
        }
        ItemStack result = cir.getReturnValue();
        if (result == null || result.isEmpty()) {
            return;
        }
        // 查找受损输入物品（修复 = 两个受损物品合并）
        ItemStack damagedInput = ItemStack.EMPTY;
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (!stack.isEmpty() && stack.getDamageValue() > 0) {
                damagedInput = stack;
                break;
            }
        }
        if (damagedInput.isEmpty()) {
            return;
        }
        handler.processCraftingDegradation(result, damagedInput);
    }
}
