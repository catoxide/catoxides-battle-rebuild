package example.contentpack.mapv2.view;

/**
 * 地图界面的有效 UI 缩放。
 *
 * <p>配置值 {@code 0} 表示跟随系统 GUI 缩放；{@code 1..4} 是绝对覆盖。
 * 越界值（旧配置文件手改、负数）一律回落到跟随系统，不抛异常。
 */
public final class UiScale {

    public static final int FOLLOW_SYSTEM = 0;
    public static final int MIN = 1;
    public static final int MAX = 4;

    private UiScale() {
    }

    public static int resolve(int configured, int system) {
        if (configured < MIN || configured > MAX) {
            return Math.max(1, system);
        }
        return configured;
    }
}
