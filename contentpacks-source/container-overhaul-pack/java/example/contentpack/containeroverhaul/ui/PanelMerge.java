package example.contentpack.containeroverhaul.ui;

import com.catoxide.catoxidesbattlerebuild.core.ui.UiRect;

import java.util.ArrayList;
import java.util.List;

/**
 * 面板合并：层级与画法都相同、并且贴边或重叠的面板并成一块。
 *
 * <p>界面不固定画几张板，而是每块板交一个 {@link Panel}（矩形 + 层级 + 画法 + 格底）；
 * 同层的板贴在一起时只画一圈外框，接缝处的两条边框自然消失。层级不同永不合并
 * （原版容器屏外框与悬浮板即使压在一起也各画各的）；画法不同也不合并 —— 只画外框的板
 * 与整块现画的板并成一块，外包矩形里会多出一圈没人要的板身。格底随合并取并集。
 *
 * <p>合并是传递的：A 贴着 B、B 贴着 C，三块并成一块。只在一个角上相碰不算贴边 ——
 * 那种情况下外包矩形会多出一块谁也没占的面积。
 */
public final class PanelMerge {

    private PanelMerge() {}

    /** 合并结果按连通块首次出现的顺序返回，每块是成员的外包矩形 + 成员格底的并集。 */
    public static List<Panel> merge(List<Panel> panels) {
        int count = panels.size();
        int[] parent = new int[count];
        for (int i = 0; i < count; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < count; i++) {
            for (int j = i + 1; j < count; j++) {
                Panel a = panels.get(i);
                Panel b = panels.get(j);
                if (a.level() == b.level() && a.mode() == b.mode() && touches(a.rect(), b.rect())) {
                    parent[find(parent, i)] = find(parent, j);
                }
            }
        }
        List<Integer> roots = new ArrayList<>();
        List<Panel> merged = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int root = find(parent, i);
            int at = roots.indexOf(root);
            if (at < 0) {
                roots.add(root);
                merged.add(panels.get(i));
            } else {
                merged.set(at, union(merged.get(at), panels.get(i)));
            }
        }
        return merged;
    }

    /** 边贴边或面积相交；只有一个角相碰不算。 */
    static boolean touches(UiRect a, UiRect b) {
        int overlapX = Math.min(a.x() + a.width(), b.x() + b.width()) - Math.max(a.x(), b.x());
        int overlapY = Math.min(a.y() + a.height(), b.y() + b.height()) - Math.max(a.y(), b.y());
        return overlapX >= 0 && overlapY >= 0 && (overlapX > 0 || overlapY > 0);
    }

    private static Panel union(Panel a, Panel b) {
        UiRect first = a.rect();
        UiRect second = b.rect();
        int x = Math.min(first.x(), second.x());
        int y = Math.min(first.y(), second.y());
        int right = Math.max(first.x() + first.width(), second.x() + second.width());
        int bottom = Math.max(first.y() + first.height(), second.y() + second.height());
        List<UiRect> cells = new ArrayList<>(a.cells());
        cells.addAll(b.cells());
        return new Panel(new UiRect(x, y, right - x, bottom - y), a.level(), a.mode(), cells);
    }

    private static int find(int[] parent, int index) {
        int root = index;
        while (parent[root] != root) {
            root = parent[root];
        }
        while (parent[index] != root) {
            int next = parent[index];
            parent[index] = root;
            index = next;
        }
        return root;
    }
}
