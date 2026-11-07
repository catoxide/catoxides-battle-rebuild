package example.contentpack.mapv2;

import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * 切片显示的叠层顺序。
 *
 * <p>切片留空像素（采集端不烘焙下层方块）⇒「从选中层往下看」= 把选中层及以下所有
 * 已加载切片从低到高叠起来，空处自然露出更低的层，逐像素递归。
 *
 * <p>{@code top} 是全高俯视图，不能垫在切片下面（会把洞穴填满），所以非切片层只画自己。
 */
public final class MapLayerStack {

    private MapLayerStack() {
    }

    /**
     * 绘制顺序：选中层及以下有数据的切片，按 y 从低到高（后者覆盖前者）。
     *
     * <p>选中的层即使还没有数据也会出现在栈里 —— 它是「当前层」的占位，不能因为
     * 下层有图就悄悄换成显示下层。
     */
    public static List<String> build(Collection<String> available, String selected) {
        int selectedY = MapLayer.absoluteY(selected);
        if (selectedY == Integer.MIN_VALUE) {
            return List.of(selected);
        }
        TreeSet<String> stack = new TreeSet<>(MapLayer.ORDER);
        for (String layer : available) {
            int y = MapLayer.absoluteY(layer);
            if (y != Integer.MIN_VALUE && y <= selectedY) {
                stack.add(layer);
            }
        }
        stack.add(selected);
        return List.copyOf(stack);
    }

    /** 逐像素采样顺序：从高到低，第一个已知像素就是该位置看到的方块。 */
    public static List<String> sampleOrder(Collection<String> available, String selected) {
        return build(available, selected).reversed();
    }
}
