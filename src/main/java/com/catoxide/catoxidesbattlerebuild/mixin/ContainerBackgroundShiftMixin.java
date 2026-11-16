package com.catoxide.catoxidesbattlerebuild.mixin;

import com.catoxide.catoxidesbattlerebuild.core.ui.ScissorShift;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 让面板背景跟着 {@code topPos} 走——原版大多数容器屏没这么做。
 *
 * <p>原版 {@code renderBg} 有两派：一派按 {@code this.topPos} 画（如
 * {@code AbstractFurnaceScreen.java:81}），另一派写死垂直居中
 * {@code int j = (this.height - this.imageHeight) / 2}（如 {@code CraftingScreen.java:66}、
 * {@code ContainerScreen.java:34}，按屏类数共 13 个）。后者在竖直方向完全不看 {@code topPos}，
 * 于是布局包把 {@code topPos} 挪走之后，槽位/文字/命中区跟着动了，背景图却停在屏幕中央。
 *
 * <p>修法是「整段背景平移 + 临时把 {@code topPos} 归到居中值」：
 * <ul>
 *   <li>写死居中的屏：背景实际落在 {@code 居中 + delta}，正好是我们要的 {@code topPos}；</li>
 *   <li>按 {@code topPos} 画的屏：字段被临时归零、Pose 补上同样的 delta，净位移不变，等于空操作。</li>
 * </ul>
 * 临时改字段而不是按屏类分支，是因为两派屏的 {@code renderBg} 内部还会混用 {@code topPos}
 * 画装饰（如 {@code MerchantScreen} 的交易图标），全部跟着同一个 delta 才不会错位。
 *
 * <p>Pose 平移不影响 {@code enableScissor}（原版按屏幕坐标直接下发）。{@code InventoryScreen}
 * 的玩家模型就是「enableScissor 后按 topPos 定位」，不补位移的话模型会跟着 Pose 挪、裁剪框
 * 留在居中位置——竖向拖出原位置就整块消失。所以平移期间同时登记 {@link ScissorShift}。
 *
 * <p>没有布局包时 {@code topPos} 就是原版居中值，delta = 0，什么都不做（R3）。
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerBackgroundShiftMixin {

    @Shadow
    protected int topPos;

    @Shadow
    protected int imageHeight;

    @WrapOperation(
            method = "renderBackground",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;renderBg(Lnet/minecraft/client/gui/GuiGraphics;FII)V"))
    private void catoxides$shiftBackground(AbstractContainerScreen<?> screen, GuiGraphics graphics,
                                           float partialTick, int mouseX, int mouseY,
                                           Operation<Void> original) {
        int centered = (((Screen) (Object) this).height - this.imageHeight) / 2;
        int delta = this.topPos - centered;
        if (delta == 0) {
            original.call(screen, graphics, partialTick, mouseX, mouseY);
            return;
        }

        int savedTop = this.topPos;
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, (float) delta, 0.0F);
        this.topPos = centered;
        ScissorShift.begin(delta);
        try {
            original.call(screen, graphics, partialTick, mouseX, mouseY);
        } finally {
            ScissorShift.end(delta);
            this.topPos = savedTop;
            graphics.pose().popPose();
        }
    }
}
