package com.catoxide.catoxidesbattlerebuild.core.durability;

import net.minecraft.world.item.ItemStack;

/**
 * 可变耐久能力接口（主 mod 框架）
 * <p>物品实现/注册本能力后，可获得自定义最大耐久（突破原版固定耐久）。
 * 未来自定义武器实现本接口即获得能力；原版物品通过 ItemStackMixin 兜底（无定义时改变原版行为）。
 *
 * <p>NeoForge 1.21.1：capability 是纯接口，通过 {@code ItemCapability} 暴露，
 * 数据存储用 DataComponent（{@code CUSTOM_MAX_DURABILITY}）。
 */
public interface IVariableDurability {

    /** 查询物品自定义最大耐久（无自定义时回退原版） */
    int getMaxDamage(ItemStack stack);

    /** 设置物品自定义最大耐久（下限 1，不会销毁物品） */
    void setMaxDamage(ItemStack stack, int newMax);
}
