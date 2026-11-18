package com.catoxide.catoxidesbattlerebuild.mixin;

import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 槽位坐标在 1.21.1 是 {@code public final int}，没有 setter。
 *
 * <p>附属面板要能整体拖动，而拖动只能靠改槽位坐标实现（原版 {@code findSlot}/{@code renderSlot}
 * 每帧读它们）。{@code @Mutable} 去掉 final 标志后才有写入口；服务端从不读这两个值，
 * 所以只改客户端也不会造成两侧状态不一致。
 */
@Mixin(Slot.class)
public interface SlotPositionAccess {

    @Mutable
    @Accessor("x")
    void catoxides$setX(int x);

    @Mutable
    @Accessor("y")
    void catoxides$setY(int y);
}
