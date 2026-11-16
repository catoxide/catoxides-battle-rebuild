package com.catoxide.catoxidesbattlerebuild.mixin;

import com.catoxide.catoxidesbattlerebuild.core.container.ContainerPolicies;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 插入门槛（自动化路径）：漏斗/分配器/能力包装最终都会问 {@code Container#canPlaceItem}。
 *
 * <p>这里给随机战利品容器的基类补一个具体实现（该类原样继承接口默认值），
 * 具体规则仍由内容包策略决定；无策略时恒 {@code true}，与原版一致（R3）。
 */
@Mixin(RandomizableContainerBlockEntity.class)
public abstract class RandomizableContainerCanPlaceMixin {

    public boolean canPlaceItem(int slot, ItemStack stack) {
        return !ContainerPolicies.blocksInsertion((Container) (Object) this, slot, stack);
    }
}
