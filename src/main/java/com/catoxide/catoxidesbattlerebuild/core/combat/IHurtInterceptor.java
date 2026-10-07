package com.catoxide.catoxidesbattlerebuild.core.combat;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * 受击拦截器契约（R2：契约住 core，实现住包）。
 * <p>由战斗内容包（M6 起）或 core 实现在注册阶段登记到 {@link HurtInterceptors}；
 * 受击 mixin（{@code LivingEntityHurtMixin}）是纯门（R1）：只查询本注册表并委托，
 * 任一拦截器接管（返回 true）则切断原版伤害路径。
 * <p>R3：未注册任何拦截器时门全部放行 —— 主 mod 单独运行 = 原版行为。
 * <p>规则 A：本签名只引用原版/core 类型，不得出现包类型。
 */
public interface IHurtInterceptor {

    /**
     * 判定是否接管本次受击。
     *
     * @param target 被击中的实体（服务端）
     * @param source 伤害源
     * @param amount 原版路径将施加的伤害量
     * @return true = 已接管（门将 cancel 原版伤害路径）；false = 放行原版路径
     */
    boolean intercept(LivingEntity target, DamageSource source, float amount);
}
