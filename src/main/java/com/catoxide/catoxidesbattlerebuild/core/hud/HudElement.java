package com.catoxide.catoxidesbattlerebuild.core.hud;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 一个可被 HUD 布局系统拖动的内容包元素（客户端）。
 *
 * <p>桥的两半：{@link #group()} 决定它跟随哪份偏移（同组元素一起移动），
 * {@link #bounds()} 让编辑屏画出可拖动手柄。渲染仍然由登记方自己做——
 * 布局系统只提供偏移量，不去改别人的绘制代码。
 *
 * <p>示例（地图包）：
 * <pre>{@code
 * HudElements.register(new HudElement() {
 *     public ResourceLocation id() { return ID; }
 *     public ResourceLocation group() { return GROUP; }   // 例：map-pack:map
 *     public Bounds bounds() { return new Bounds(x, y, size, size); }
 * });
 * // 渲染时：
 * int dx = HudLayoutApi.offsetX(ID);
 * int dy = HudLayoutApi.offsetY(ID);
 * }</pre>
 */
public interface HudElement {

    ResourceLocation id();

    /**
     * 分组键。同一组的元素共享同一份偏移。
     *
     * <p>原版的血量/盔甲/饥饿/空气靠 {@code Gui.leftHeight/rightHeight} 互相堆叠，
     * 必须与 hotbar 同组整体移动，拆开会让堆叠错位。
     */
    ResourceLocation group();

    /** 未应用偏移时的矩形（GUI 缩放单位）。每帧调用，可依赖屏幕尺寸与玩家状态。 */
    Bounds bounds();

    /**
     * 右键菜单条目。框架只负责显示与派发，内容完全由元素决定；默认空。
     *
     * @see HudAction
     */
    default List<HudAction> actions() {
        return List.of();
    }

    /**
     * 可以在布局包的面板里调的配置选项，按面板中的显示顺序排列；默认空。
     *
     * <p>这里是声明，不是实现：选项的类型、区间、默认值、生效档位和显示名全部来自
     * 元素所属包自己的 {@code config-declarations.json}，布局包只是把它们渲染成控件并写回。
     * 同一份声明因此同时服务内置配置屏和 HUD 面板，不会有两份真相。
     *
     * @see HudConfigOption
     */
    default List<HudConfigOption> options() {
        return List.of();
    }

    /**
     * 尺寸可由拖动边角改变的声明；返回 {@code null}（默认）表示这个元素<b>不可拉伸</b>，
     * 布局包不给它画拉伸手柄。
     *
     * @see HudResize
     */
    default @Nullable HudResize resize() {
        return null;
    }

    record Bounds(int x, int y, int width, int height) {

        public Bounds shifted(int dx, int dy) {
            return new Bounds(x + dx, y + dy, width, height);
        }

        public boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }

        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }
    }
}
