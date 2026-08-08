package com.catoxide.catoxidesbattlerebuild.core.durability;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * 武器耐久能力端口（面向未来武器系统的 API）
 * <p>未来的自定义武器通过本端口获得「可配置的有限耐久」：
 * <ol>
 *   <li>{@link #registerVariableDurability}：声明物品为可变耐久武器（注册能力）</li>
 *   <li>{@link #setMaxDurability} / {@link #getMaxDurability}：配置/读取耐久上限</li>
 * </ol>
 * 注册后：耐久上限可在配置中初始化（武器包 config），并被耐久降级系统动态调整。
 *
 * <p>武器 contentpack 用法（register 阶段）：
 * <pre>{@code
 * context.getModEventBus().addListener((RegisterCapabilitiesEvent e) ->
 *     DurabilitySupport.registerVariableDurability(e, MyWeaponItem.get()));
 * // 配置初始耐久
 * DurabilitySupport.setMaxDurability(stack, myConfig.maxDurability);
 * }</pre>
 */
public final class DurabilitySupport {

    private DurabilitySupport() {}

    /**
     * 声明物品为可变耐久武器（为其注册 {@link IVariableDurability} 能力）。
     * 调用后该物品的 {@code getMaxDamage()} 走能力查询（ItemStackMixin 兜底），
     * 且可通过 {@link #setMaxDurability} 动态调整上限（降级系统也走此通道）。
     */
    public static void registerVariableDurability(RegisterCapabilitiesEvent event, Item... items) {
        event.registerItem(DurabilityCapabilities.VAR_DURABILITY_CAP,
                (stack, ctx) -> new VariableDurabilityCapability(), items);
    }

    /** 设置物品最大耐久（可配置的有限耐久；下限 1） */
    public static void setMaxDurability(ItemStack stack, int newMax) {
        stack.set(DurabilityCapabilities.CUSTOM_MAX_DURABILITY, Math.max(1, newMax));
    }

    /** 读取物品当前最大耐久（无自定义时回退原版） */
    public static int getMaxDurability(ItemStack stack) {
        IVariableDurability cap = stack.getCapability(DurabilityCapabilities.VAR_DURABILITY_CAP, null);
        if (cap != null) {
            return cap.getMaxDamage(stack);
        }
        return stack.getMaxDamage();
    }

    /** 该物品是否已注册可变耐久能力 */
    public static boolean isVariableDurability(ItemStack stack) {
        return stack.getCapability(DurabilityCapabilities.VAR_DURABILITY_CAP, null) != null;
    }
}
