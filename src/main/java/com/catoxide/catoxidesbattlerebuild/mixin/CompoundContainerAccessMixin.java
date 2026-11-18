package com.catoxide.catoxidesbattlerebuild.mixin;

import com.catoxide.catoxidesbattlerebuild.core.container.ICompoundContainerAccess;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 暴露双箱两半，供内容包判断「其中一半是战利品箱」。
 * 只读两个字段，无行为改动。
 */
@Mixin(CompoundContainer.class)
public abstract class CompoundContainerAccessMixin implements ICompoundContainerAccess {

    @Shadow @Final private Container container1;
    @Shadow @Final private Container container2;

    @Override
    public Container catoxides$firstHalf() {
        return this.container1;
    }

    @Override
    public Container catoxides$secondHalf() {
        return this.container2;
    }
}
