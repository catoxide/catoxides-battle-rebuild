package example.contentpack.containeroverhaul.ui;

import com.catoxide.catoxidesbattlerebuild.core.ui.UiRect;

import java.util.ArrayList;
import java.util.List;

/**
 * 浮板合并：层级相同、并且贴边或重叠的矩形并成一块。
 *
 * <p>界面不再固定「物品栏板 + 升级板」两张贴图，而是每一块板交出一个矩形和它的层级；
 * 同层级的板贴在一起时只画一圈外框，接缝处的两条边框自然消失。层级不同的板永远不合并
 * （例如升级板就是物品栏板的从属件，即使贴上去了也各画各的框）。
 *
 * <p>合并是传递的：A 贴着 B、B 贴着 C，三块并成一块。只在一个角上相碰不算贴边 ——
 * 那种情况下外包矩形会多出一块谁也没占的面积。
 */
public final class PanelMerge {

    /** 一块待画的板：位置尺寸 + 层级（小的在上层 / 从属层，但仍按贴边规则合并）。 */
    public record Section(UiRect rect, int level) {}

    private PanelMerge() {}

    /** 合并结果按连通块首次出现的顺序返回，每块是成员的外包矩形。 */
    public static List<UiRect> merge(List<Section> sections) {
        int count = sections.size();
        int[] parent = new int[count];
        for (int i = 0; i < count; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < count; i++) {
            for (int j = i + 1; j < count; j++) {
                Section a = sections.get(i);
                Section b = sections.get(j);
                if (a.level() == b.level() && touches(a.rect(), b.rect())) {
                    parent[find(parent, i)] = find(parent, j);
                }
            }
        }
        List<Integer> roots = new ArrayList<>();
        List<UiRect> merged = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int root = find(parent, i);
            int at = roots.indexOf(root);
            if (at < 0) {
                roots.add(root);
                merged.add(sections.get(i).rect());
            } else {
                merged.set(at, union(merged.get(at), sections.get(i).rect()));
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

    private static UiRect union(UiRect a, UiRect b) {
        int x = Math.min(a.x(), b.x());
        int y = Math.min(a.y(), b.y());
        int right = Math.max(a.x() + a.width(), b.x() + b.width());
        int bottom = Math.max(a.y() + a.height(), b.y() + b.height());
        return new UiRect(x, y, right - x, bottom - y);
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
