package com.catoxide.catoxidesbattlerebuild.mixin;

import com.catoxide.catoxidesbattlerebuild.core.ui.ScreenLayoutHooks;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 效果图标列的纵向重新锚定。
 *
 * <p>原版 {@code renderEffects} 从 {@code topPos} 开始往下摞，NeoForge 的
 * {@code RenderInventoryMobEffects} 只能改水平位置。要让这一列「贴底、往上磊」，
 * 只能在整段绘制外面平移 Pose；同时把悬停用的 {@code mouseY} 反着减回去，
 * 否则 tooltip 会错开一个位移量。
 *
 * <p>mixin 只做「问偏移 + 平移 + 归零」；偏移由布局包算（{@link ScreenLayoutHooks}）。
 * 没有布局包或没有声明 → 0 → 原版行为。
 */
@Mixin(EffectRenderingInventoryScreen.class)
public abstract class EffectStackShiftMixin {

    @Unique
    private int catoxides$effectShift;

    @Inject(method = "renderEffects", at = @At("HEAD"))
    private void catoxides$beginEffectShift(GuiGraphics graphics, int mouseX, int mouseY, CallbackInfo info) {
        int shift = ScreenLayoutHooks.effectsShiftY((AbstractContainerScreen<?>) (Object) this);
        this.catoxides$effectShift = shift;
        if (shift != 0) {
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, (float) shift, 0.0F);
        }
    }

    @Inject(method = "renderEffects", at = @At("RETURN"))
    private void catoxides$endEffectShift(GuiGraphics graphics, int mouseX, int mouseY, CallbackInfo info) {
        if (this.catoxides$effectShift != 0) {
            graphics.pose().popPose();
            this.catoxides$effectShift = 0;
        }
    }

    /** 悬停判定在同一个方法里：视觉下移了多少，鼠标就得同比减回去。 */
    @ModifyVariable(method = "renderEffects", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int catoxides$shiftEffectHover(int mouseY) {
        return mouseY - ScreenLayoutHooks.effectsShiftY((AbstractContainerScreen<?>) (Object) this);
    }
}
