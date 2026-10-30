package example.contentpack.mapv2;

/**
 * 当前查看的图层（跨界面共享）。
 *
 * <p>全屏地图里选的层，小地图也跟着显示；换世界时清空回默认层。
 */
public final class MapLayerSelection {

    private static String selected;

    private MapLayerSelection() {
    }

    /** 显式选择过的层；{@code null} = 用默认层（{@link MapLayer#TOP}）。 */
    public static String selected() {
        return selected;
    }

    public static void select(String layer) {
        selected = layer;
    }

    public static void reset() {
        selected = null;
    }
}
