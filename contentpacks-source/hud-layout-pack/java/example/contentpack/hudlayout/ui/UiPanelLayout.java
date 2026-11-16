package example.contentpack.hudlayout.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

/**
 * 一个容器面板的布局：相对原版位置的偏移 + 可选的药水效果列锚定。
 *
 * <p>偏移是「原版基准 + 偏移」里的那一半（契约 §4），所以这里存的是<b>增量</b>，
 * 不是绝对坐标：窗口缩放、配方书重算都不需要改写玩家存下的值。
 */
public record UiPanelLayout(int x, int y, @Nullable UiEffectsAnchor effects) {

    public static final UiPanelLayout DEFAULT = new UiPanelLayout(0, 0, null);

    /** 等于原版：这样的条目写进文件只是噪音，编辑模式也当作「这一组没动过」。 */
    public boolean isDefault() {
        return x == 0 && y == 0 && effects == null;
    }

    public static UiPanelLayout fromJson(JsonObject json) {
        JsonElement effects = json.get("effects");
        return new UiPanelLayout(
                intOr(json, "x", 0),
                intOr(json, "y", 0),
                effects != null && effects.isJsonObject() ? UiEffectsAnchor.fromJson(effects.getAsJsonObject()) : null);
    }

    /** 屏内元素只有偏移：{@code elements} 段里的 effects 不是给它们用的，读到也不认。 */
    public static UiPanelLayout offsetFromJson(JsonObject json) {
        return new UiPanelLayout(intOr(json, "x", 0), intOr(json, "y", 0), null);
    }

    private static int intOr(JsonObject json, String key, int fallback) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return value.getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
