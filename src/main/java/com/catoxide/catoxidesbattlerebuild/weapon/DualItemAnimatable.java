package com.catoxide.catoxidesbattlerebuild.weapon;

import cn.solarmoon.spark_core.animation.ItemAnimatable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 双视角 ItemAnimatable 包装器
 * 自身持有第一人称的 animController/modelController，
 * 同时持有第三人称的 ItemAnimatable 实例。
 *
 * AnimApplier 通过 capability 获取此对象并 tick：
 * - physicsTick() 同时 tick 第一人称和第三人称的骨骼
 * - inventoryTick() 同时 tick 第一人称和第三人称的状态机
 *
 * getRenderInstance 按 context 返回对应实例：
 * - 第一人称 → 返回此对象（自身就是第一人称实例）
 * - 第三人称 → 返回 tpInstance
 */
public class DualItemAnimatable extends ItemAnimatable {
    private ItemAnimatable tpInstance;

    public DualItemAnimatable(ItemStack stack, Level level) {
        super(stack, level);
    }

    public void setTpInstance(ItemAnimatable tpInstance) {
        this.tpInstance = tpInstance;
    }

    public ItemAnimatable getTpInstance() {
        return tpInstance;
    }

    @Override
    public void physicsTick() {
        super.physicsTick();
        if (tpInstance != null) {
            tpInstance.physicsTick();
        }
    }

    @Override
    public void inventoryTick(Entity owner) {
        super.inventoryTick(owner);
        if (tpInstance != null) {
            tpInstance.inventoryTick(owner);
            tpInstance.getAnimController().tick();
        }
    }
}
