package com.catoxide.catoxidesbattlerebuild.mixin;

import com.catoxide.catoxidesbattlerebuild.core.ui.ScissorShift;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 给 Pose 平移期间的 scissor 补位移。
 *
 * <p>原版把 scissor 矩形当屏幕坐标直接用（{@code GuiGraphics#applyScissor}，不进 Pose）。
 * 布局层平移 Pose 之后裁剪框不跟着走，模型/文本在裁剪区外就整块消失——见 {@link ScissorShift}。
 *
 * <p>改在 {@code applyScissor}（唯一出口）而不是 {@code enableScissor}：rect 在
 * {@code ScissorStack} 里取交集，平移与交集可交换，所以只补下发 GL 的那一步就够；
 * 栈里的矩形保持屏幕坐标，{@code disableScissor} 弹栈时再补一次，状态仍然一致。
 */
@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsScissorMixin {

    @ModifyVariable(method = "applyScissor", at = @At("HEAD"), argsOnly = true)
    private @Nullable ScreenRectangle catoxides$shiftScissor(@Nullable ScreenRectangle rectangle) {
        int dy = ScissorShift.offsetY();
        if (rectangle == null || dy == 0) {
            return rectangle;
        }
        return new ScreenRectangle(rectangle.left(), rectangle.top() + dy, rectangle.width(), rectangle.height());
    }
}
