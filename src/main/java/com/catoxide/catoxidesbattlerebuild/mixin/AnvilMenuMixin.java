package com.catoxide.catoxidesbattlerebuild.mixin;

import com.catoxide.catoxidesbattlerebuild.core.durability.DurabilityDegradationRegistry;
import com.catoxide.catoxidesbattlerebuild.core.durability.IDurabilityDegradationHandler;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 铁砧修复降级 mixin（主 mod 能力点）
 * <p>拦截 {@code AnvilMenu.createResult}（铁砧修复结果生成后），
 * 将处理委托给 durability contentpack 注册的 {@link IDurabilityDegradationHandler}。
 * 无处理器时放行（原版行为）。
 */
@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin {

    @Inject(method = "createResult", at = @At("RETURN"))
    private void catoxides$onAnvilResult(CallbackInfo ci) {
        IDurabilityDegradationHandler handler = DurabilityDegradationRegistry.get();
        if (handler == null) {
            return;
        }
        AnvilMenu menu = (AnvilMenu) (Object) this;
        ItemStack result = menu.getSlot(2).getItem();
        ItemStack left = menu.getSlot(0).getItem();
        if (result.isEmpty() || left.isEmpty()) {
            return;
        }
        // level 由 handler 内部处理（当前实现不依赖 level，传 null）
        handler.processAnvilDegradation(result, left, null);
    }
}
