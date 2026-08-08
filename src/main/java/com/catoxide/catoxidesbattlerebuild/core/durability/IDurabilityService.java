package com.catoxide.catoxidesbattlerebuild.core.durability;

import net.minecraft.world.item.ItemStack;

/**
 * 耐久服务（主 mod 稳定接口）
 * <p>可变耐久系统由 contentpack 插件实现，主 mod 的 mixin（AnvilMenuMixin 等）
 * 通过本接口查询/操作。1.21.1 用原版 DataComponent（{@code DataComponents.MAX_DAMAGE}）
 * 实现可变耐久，无需自研 Capability。
 *
 * <p>未注册服务时（插件未加载），主 mod 行为回退原版（mixin 直接放行）。
 */
public interface IDurabilityService {

    /**
     * 查询物品的自定义最大耐久。
     *
     * @return 自定义最大耐久；&lt;0 表示不干预（用原版）
     */
    int getMaxDamage(ItemStack stack);

    /**
     * 设置物品的自定义最大耐久（写入 DataComponents.MAX_DAMAGE）。
     */
    void setMaxDamage(ItemStack stack, int newMax);

    /**
     * 该物品是否应禁用原版铁砧修复（自定义耐久物品用自定义修复方式）。
     */
    boolean shouldDisableAnvilRepair(ItemStack stack);
}
