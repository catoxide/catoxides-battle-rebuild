package com.catoxide.catoxidesbattlerebuild.mixin;

import com.catoxide.catoxidesbattlerebuild.core.ui.IScreenPanelAccess;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 让内容包能写面板位置。原版四个字段都是 {@code protected int} 且没有 setter，
 * 读有公开访问器、写没有。
 *
 * <p>只暴露 {@code leftPos/topPos}：尺寸的写入等「声明变体」真正落地再加，
 * 现在加了也没有使用者（无第二份真相）。
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenAccessMixin implements IScreenPanelAccess {

    @Shadow
    protected int leftPos;

    @Shadow
    protected int topPos;

    @Override
    public int catoxides$panelLeft() {
        return this.leftPos;
    }

    @Override
    public int catoxides$panelTop() {
        return this.topPos;
    }

    @Override
    public void catoxides$setPanelLeft(int left) {
        this.leftPos = left;
    }

    @Override
    public void catoxides$setPanelTop(int top) {
        this.topPos = top;
    }
}
