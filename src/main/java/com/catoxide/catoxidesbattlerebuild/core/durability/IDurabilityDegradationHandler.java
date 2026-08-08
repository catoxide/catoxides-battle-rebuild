package com.catoxide.catoxidesbattlerebuild.core.durability;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * 耐久降级处理接口（主 mod 稳定抽象）
 * <p>mixin（主 mod 能力点）在修复完成后调用本接口；durability contentpack 实现
 * 三机制降级逻辑（Mending 概率降 / 铁砧按比例 / 合成台按比例）。
 * 无处理器时（插件未加载），mixin 直接放行（原版行为）。
 */
public interface IDurabilityDegradationHandler {

    /** 经验修补修复后处理（每修复 1 点按概率降 1 最大耐久） */
    void processMendingDegradation(ItemStack stack, ServerLevel level, int repairAmount);

    /** 铁砧修复后处理（按修复量比例降最大耐久） */
    void processAnvilDegradation(ItemStack result, ItemStack left, ServerLevel level);

    /** 合成台修复后处理（按修复量比例降最大耐久） */
    void processCraftingDegradation(ItemStack result, ItemStack damagedInput);
}
