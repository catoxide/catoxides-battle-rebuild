package example.contentpack.hudlayout.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * 药水效果列的锚定规则：贴面板的哪个角、离边多远、最多让这一列长多高。
 *
 * <p>原版把效果列画在面板右侧、从 {@code topPos} 往下摞；这里只决定「整列落在哪」。
 * 间距仍归原版（&gt;5 个时它自己压到 132px），所以「一边的最大长度」实际是原版 132
 * 加上这里的 {@code maxLength} 闸门：装不下就退回原版位置，不做半吊子压缩。
 *
 * <p>纯计算，不碰屏幕：{@code scripts/validate-game.ps1} 之外的自检直接跑它。
 */
public record UiEffectsAnchor(Anchor anchor, int gapX, int gapY, int maxLength) {

    /** 只支持面板右侧的两个角——左侧要绕开原版的 32px 空间检查，见契约 §8。 */
    public enum Anchor {
        TOP_RIGHT("top-right"),
        BOTTOM_RIGHT("bottom-right");

        private final String jsonName;

        Anchor(String jsonName) {
            this.jsonName = jsonName;
        }

        public String jsonName() {
            return jsonName;
        }

        public static @Nullable Anchor fromJson(@Nullable String name) {
            if (name == null) {
                return null;
            }
            String normalized = name.trim().toLowerCase(Locale.ROOT);
            for (Anchor value : values()) {
                if (value.jsonName.equals(normalized)) {
                    return value;
                }
            }
            return null;
        }
    }

    /** 原版行为：右边缘 +2px、从面板顶开始往下摞、不限长度。 */
    public static final UiEffectsAnchor VANILLA = new UiEffectsAnchor(Anchor.TOP_RIGHT, 2, 0, 0);

    public int horizontalX(int panelLeft, int panelWidth) {
        return panelLeft + panelWidth + gapX;
    }

    /**
     * 整列要下移多少像素（负数 = 上移）。
     *
     * @param effectCount 原版用的 {@code getActiveEffects().size()}，与它算间距的口径一致
     * @return {@code 0} 表示不动（原版位置）
     */
    public int shiftY(int panelTop, int panelHeight, int effectCount) {
        int block = blockHeight(effectCount);
        if (maxLength > 0 && block > maxLength) {
            return 0;
        }
        int desiredTop = anchor == Anchor.BOTTOM_RIGHT
                ? panelTop + panelHeight - gapY - block
                : panelTop + gapY;
        return desiredTop - panelTop;
    }

    /** 与原版 {@code renderEffects} 同一公式：每格 33，超过 5 个压到 132 的跨度。 */
    public static int blockHeight(int effectCount) {
        int count = Math.max(1, effectCount);
        int stride = count > 5 ? 132 / (count - 1) : 33;
        return (count - 1) * stride + 33;
    }

    public static UiEffectsAnchor fromJson(JsonObject json) {
        Anchor anchor = anchor(json);
        return new UiEffectsAnchor(
                anchor == null ? Anchor.TOP_RIGHT : anchor,
                intOr(json, "gapX", 2),
                intOr(json, "gapY", 0),
                intOr(json, "maxLength", 0));
    }

    public static @Nullable Anchor anchor(JsonObject json) {
        JsonElement value = json.get("anchor");
        return value != null && value.isJsonPrimitive() ? Anchor.fromJson(value.getAsString()) : null;
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
