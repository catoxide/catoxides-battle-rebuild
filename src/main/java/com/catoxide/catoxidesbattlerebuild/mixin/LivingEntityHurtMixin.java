package com.catoxide.catoxidesbattlerebuild.mixin;

import com.catoxide.catoxidesbattlerebuild.core.combat.HurtInterceptors;
import com.catoxide.catoxidesbattlerebuild.core.combat.IHurtInterceptor;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 受击门（R1：查询 + 委托 + 回退，无任何机制逻辑）。
 * <p>部位伤害/致命部位等战斗逻辑全部在 {@link IHurtInterceptor} 实现里
 * （M5 为 core 的 {@code MeleeHurtInterceptor}，M6 起由战斗内容包提供）。
 * <p>R3：{@link HurtInterceptors} 为空（未装战斗内容）时不 cancel —— 行为 = 原版。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityHurtMixin {

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void onHurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity target = (LivingEntity) (Object) this;
        if (target.level().isClientSide()) {
            return;
        }
        for (IHurtInterceptor interceptor : HurtInterceptors.getAll()) {
            if (interceptor.intercept(target, source, amount)) {
                cir.setReturnValue(true);
                cir.cancel();
                return;
            }
        }
    }
}
