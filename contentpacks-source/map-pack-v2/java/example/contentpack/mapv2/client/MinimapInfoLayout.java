package example.contentpack.mapv2.client;

/**
 * 小地图信息条的纯排版逻辑：可见行数 + 按对齐方式求起点。
 *
 * <p>抽出来是为了让 {@code MapV2SelfTest} 能在不启动游戏的情况下验证，
 * 渲染侧只负责把结果画到屏幕上。
 */
public final class MinimapInfoLayout {

    /** 0 = 左对齐（小地图左缘），1 = 居中，2 = 右对齐（小地图右缘，默认）。 */
    public static final int LEFT = 0;
    public static final int CENTER = 1;
    public static final int RIGHT = 2;

    private MinimapInfoLayout() {
    }

    public static int lineCount(boolean coords, boolean biome, boolean time) {
        return (coords ? 1 : 0) + (biome ? 1 : 0) + (time ? 1 : 0);
    }

    /**
     * 文本起点。{@code width} 是文本宽度，锚点单位与调用方一致（缩放前的坐标）。
     * 未知对齐值按右对齐处理，避免手改配置把文字画到屏幕外。
     */
    public static int alignX(int align, int width, int left, int center, int right) {
        return switch (align) {
            case LEFT -> left;
            case CENTER -> center - width / 2;
            default -> right - width;
        };
    }
}
