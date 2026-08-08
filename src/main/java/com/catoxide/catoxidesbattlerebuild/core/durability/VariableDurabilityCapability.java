package com.catoxide.catoxidesbattlerebuild.core.durability;

import net.minecraft.world.item.ItemStack;

/**
 * 可变耐久能力实现（DataComponent 存储）
 * <p>使用 {@code CUSTOM_MAX_DURABILITY} 数据组件持久化每物品栈的自定义最大耐久，
 * 无需自研 Capability 存储（NeoForge 1.21.1 原生数据组件）。
 */
public class VariableDurabilityCapability implements IVariableDurability {

    @Override
    public int getMaxDamage(ItemStack stack) {
        Integer customMax = stack.get(DurabilityCapabilities.CUSTOM_MAX_DURABILITY);
        if (customMax != null && customMax > 0) {
            return customMax;
        }
        return stack.getItem().getMaxDamage(stack);
    }

    @Override
    public void setMaxDamage(ItemStack stack, int newMax) {
        stack.set(DurabilityCapabilities.CUSTOM_MAX_DURABILITY, Math.max(1, newMax));
    }
}
