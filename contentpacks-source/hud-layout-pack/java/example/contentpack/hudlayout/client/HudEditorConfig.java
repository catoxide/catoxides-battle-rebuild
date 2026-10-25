package example.contentpack.hudlayout.client;

import com.catoxide.catoxidesbattlerebuild.core.config.PackConfig;

/** 编辑屏自身的偏好（吸附网格、标签开关）。每次都从 {@link PackConfig} 现读，保持 HOT。 */
public final class HudEditorConfig {

    private static volatile PackConfig config;

    private HudEditorConfig() {
    }

    public static void bind(PackConfig packConfig) {
        config = packConfig;
    }

    public static int snapGrid() {
        PackConfig current = config;
        if (current == null) {
            return 1;
        }
        return Math.max(1, Math.min(32, current.getInt("snapGrid")));
    }

    public static boolean showGroupLabels() {
        PackConfig current = config;
        return current == null || current.getBoolean("showGroupLabels");
    }
}
