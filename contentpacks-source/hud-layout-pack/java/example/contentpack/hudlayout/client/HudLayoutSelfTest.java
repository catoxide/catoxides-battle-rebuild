package example.contentpack.hudlayout.client;

import com.catoxide.catoxidesbattlerebuild.core.config.PackConfig;
import com.catoxide.catoxidesbattlerebuild.core.hud.HudConfigOption;
import com.catoxide.catoxidesbattlerebuild.core.hud.HudElement;
import com.catoxide.catoxidesbattlerebuild.core.hud.HudResize;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import example.contentpack.hudlayout.layout.LayoutStore;
import example.contentpack.hudlayout.layout.MoveAxis;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 布局包纯逻辑的启动自检：拖动约束、落盘格式迁移、拉伸手柄几何、拖拽条取值。
 *
 * <p>这些都是「算错就把手感弄坏，但游戏里很难一眼看出来」的部分，所以放在启动时跑一次，
 * 失败用 ERROR 记，由 {@code scripts/validate-game.ps1} 当成 FAIL 抓出来。
 *
 * <p>只碰纯函数，不碰屏幕、Font、文件系统：面板和编辑屏本身仍然要靠人进游戏看。
 */
public final class HudLayoutSelfTest {

    private static final String TAG = "HudLayout";
    private static final int TRACK_X = 10;
    private static final int TRACK_WIDTH = 46;

    private HudLayoutSelfTest() {
    }

    public static void run() {
        int passed = 0;
        int failed = 0;
        for (Map.Entry<String, Runnable> test : tests().entrySet()) {
            try {
                test.getValue().run();
                passed++;
            } catch (Throwable error) {
                failed++;
                LogManager.clientError(TAG, "HUD layout self-test FAIL '" + test.getKey() + "': " + error);
            }
        }

        if (failed > 0) {
            LogManager.clientError(TAG, "HUD layout self-test FAILURES=" + failed + " passed=" + passed);
        } else {
            LogManager.clientInfo(TAG, "HUD layout self-test passed=%d", passed);
        }
    }

    /** 顺序即日志顺序：坏在哪一条，从前往后读得到。 */
    private static Map<String, Runnable> tests() {
        Map<String, Runnable> tests = new LinkedHashMap<>();
        tests.put("move-axis", HudLayoutSelfTest::testMoveAxis);
        tests.put("layout-decode-migration", HudLayoutSelfTest::testLayoutDecode);
        tests.put("resize-grip-geometry", HudLayoutSelfTest::testGripRect);
        tests.put("resize-size-from-anchor", HudLayoutSelfTest::testSizeFrom);
        tests.put("resize-grip-hit", HudLayoutSelfTest::testGripHit);
        tests.put("slider-math", HudLayoutSelfTest::testSliderMath);
        tests.put("option-info-bounds", HudLayoutSelfTest::testOptionInfoBounds);
        return tests;
    }

    /**
     * 拖动约束（{@code LayoutStore.set} 就是用它把锁住的轴折回 0）。
     *
     * <p>方向要说清楚：{@code VERTICAL} = 只能上下移动 = <b>横</b>偏移被锁。
     */
    private static void testMoveAxis() {
        require(MoveAxis.FREE.limitX(7) == 7 && MoveAxis.FREE.limitY(7) == 7, "free must not limit either axis");
        require(MoveAxis.HORIZONTAL.limitX(7) == 7 && MoveAxis.HORIZONTAL.limitY(7) == 0,
                "horizontal means only-x moves: y must lock");
        require(MoveAxis.VERTICAL.limitX(7) == 0 && MoveAxis.VERTICAL.limitY(7) == 7,
                "vertical means only-y moves: x must lock");

        require(MoveAxis.FREE.next() == MoveAxis.HORIZONTAL, "cycle must advance free -> horizontal");
        require(MoveAxis.HORIZONTAL.next() == MoveAxis.VERTICAL, "cycle must advance horizontal -> vertical");
        require(MoveAxis.VERTICAL.next() == MoveAxis.FREE, "cycle must wrap back to free");

        require(!MoveAxis.FREE.isConstrained(), "free is not a constraint");
        require(MoveAxis.HORIZONTAL.isConstrained() && MoveAxis.VERTICAL.isConstrained(),
                "both single-axis modes are constraints");

        for (MoveAxis axis : MoveAxis.values()) {
            require(MoveAxis.fromJson(axis.jsonName()) == axis, "json name must round-trip: " + axis.jsonName());
            require(MoveAxis.fromJson(axis.jsonName().toUpperCase(Locale.ROOT)) == axis,
                    "json name must be read case-insensitively: " + axis.jsonName());
            require(axis.translationKey().endsWith(axis.jsonName()),
                    "translation key must follow the json name: " + axis);
        }
        require(MoveAxis.fromJson(null) == null, "null must parse as unknown, not throw");
        require(MoveAxis.fromJson("diagonal") == null, "unknown axis must parse as unknown");
    }

    /**
     * 落盘解码：schema 1（没有 axis 键）必须读成 {@link MoveAxis#FREE}，
     * 玩家已经摆好的偏移不能因为升版而丢；不认识的版本整份忽略。
     */
    private static void testLayoutDecode() {
        Map<String, LayoutStore.State> v1 = LayoutStore.decode(parse(
                """
                {"schemaVersion":1,"groups":{"hotbar":{"x":10,"y":-4},"effects":{"x":0,"y":2}}}"""), null);
        require(v1 != null, "schema 1 must still be readable");
        require(v1.size() == 2, "both schema-1 groups must survive: " + v1.size());
        require(v1.get("hotbar").x() == 10 && v1.get("hotbar").y() == -4, "schema-1 offset must be read verbatim");
        require(v1.get("hotbar").axis() == MoveAxis.FREE, "a schema-1 group has no axis, so it must be free");

        Map<String, LayoutStore.State> v2 = LayoutStore.decode(parse(
                """
                {"schemaVersion":2,"groups":{"map":{"x":3,"y":4,"axis":"vertical"}}}"""), null);
        require(v2 != null && v2.get("map").axis() == MoveAxis.VERTICAL, "schema-2 axis must be read");
        require(v2 != null && v2.get("map").x() == 3 && v2.get("map").y() == 4, "schema-2 offset must be read");

        // 手改配置文件写错一个词：这一组退回自由，别把整份布局丢掉
        Map<String, LayoutStore.State> typo = LayoutStore.decode(parse(
                """
                {"schemaVersion":2,"groups":{"map":{"x":3,"y":4,"axis":"diagonal"}}}"""), null);
        require(typo != null && typo.get("map").axis() == MoveAxis.FREE, "unknown axis falls back to free");
        require(typo != null && typo.get("map").x() == 3, "an unknown axis must not discard the offset");

        Map<String, LayoutStore.State> junk = LayoutStore.decode(parse(
                """
                {"schemaVersion":2,"groups":{"broken":12,"half":{"x":1},"ok":{"x":1,"y":1}}}"""), null);
        require(junk != null && junk.size() == 1 && junk.get("ok") != null,
                "only well-formed group entries are kept: " + (junk == null ? "null" : junk.keySet()));

        require(LayoutStore.decode(parse("{\"schemaVersion\":0,\"groups\":{}}"), null) == null,
                "a version below the readable floor must be ignored");
        require(LayoutStore.decode(parse("{\"schemaVersion\":99,\"groups\":{}}"), null) == null,
                "a version from the future must be ignored");
        require(LayoutStore.decode(new JsonObject(), null) == null, "a missing schemaVersion must be ignored");
    }

    /**
     * 手柄位置 = 固定角的<b>对角</b>，而且必须落在矩形里（画在框外就没人点得到）。
     *
     * <p>{@code WIDTH} 只动横边、所以纵向居中；{@code HEIGHT} 反之。
     */
    private static void testGripRect() {
        HudElement.Bounds rect = new HudElement.Bounds(100, 50, 40, 30);
        int right = rect.right() - HudResizeHandle.GRIP_SIZE;
        int bottom = rect.bottom() - HudResizeHandle.GRIP_SIZE;

        requireGrip(rect, HudResize.Mode.SQUARE, HudResize.Anchor.TOP_LEFT, right, bottom,
                "a top-left anchor grips at the bottom-right corner");
        requireGrip(rect, HudResize.Mode.SQUARE, HudResize.Anchor.TOP_RIGHT, rect.x(), bottom,
                "a top-right anchor grips at the bottom-left corner");
        requireGrip(rect, HudResize.Mode.SQUARE, HudResize.Anchor.BOTTOM_LEFT, right, rect.y(),
                "a bottom-left anchor grips at the top-right corner");
        requireGrip(rect, HudResize.Mode.SQUARE, HudResize.Anchor.BOTTOM_RIGHT, rect.x(), rect.y(),
                "a bottom-right anchor grips at the top-left corner");

        requireGrip(rect, HudResize.Mode.WIDTH, HudResize.Anchor.TOP_LEFT, right,
                rect.y() + (rect.height() - HudResizeHandle.GRIP_SIZE) / 2,
                "a width grip sits on the side, centred vertically");
        requireGrip(rect, HudResize.Mode.HEIGHT, HudResize.Anchor.BOTTOM_RIGHT,
                rect.x() + (rect.width() - HudResizeHandle.GRIP_SIZE) / 2, rect.y(),
                "a height grip pinned at the bottom sits on the top edge, centred horizontally");

        for (HudResize.Mode mode : HudResize.Mode.values()) {
            for (HudResize.Anchor anchor : HudResize.Anchor.values()) {
                int[] grip = HudResizeHandle.gripRect(rect, resize(mode, anchor));
                require(grip[0] >= rect.x() && grip[0] + grip[2] <= rect.right()
                        && grip[1] >= rect.y() && grip[1] + grip[3] <= rect.bottom(),
                        "the grip must stay inside the element rect: " + mode + "/" + anchor);
            }
        }
    }

    /**
     * 取值 = 鼠标到<b>固定角</b>的距离，不是增量。
     *
     * <p>关键不变量：只有「属于尺寸的那条边」上的鼠标位置改变值。这一条决定拖动跟不跟手，
     * 也决定元素改完尺寸后自己平移时会不会越拖越偏。
     */
    private static void testSizeFrom() {
        HudElement.Bounds rect = new HudElement.Bounds(100, 50, 40, 40);
        HudResize.Anchor anchor = HudResize.Anchor.TOP_RIGHT;

        // 固定角在 (140, 50)：鼠标拖到 (90, 80) 时横向 50、纵向 30
        require(size(rect, HudResize.Mode.SQUARE, anchor, 90, 80) == 50.0, "a square takes the larger span");
        require(size(rect, HudResize.Mode.WIDTH, anchor, 90, 80) == 50.0,
                "width takes the horizontal span measured from the pinned edge");
        require(size(rect, HudResize.Mode.HEIGHT, anchor, 90, 80) == 30.0,
                "height takes the vertical span measured from the pinned edge");

        // 越过固定角也按距离算，不能变成负尺寸
        require(size(rect, HudResize.Mode.SQUARE, anchor, 190, 80) == 50.0,
                "distance is absolute: crossing the pinned edge must not flip the sign");

        for (HudResize.Anchor each : HudResize.Anchor.values()) {
            double atPin = size(rect, HudResize.Mode.SQUARE, each,
                    HudResizeHandle.anchorX(rect, each), HudResizeHandle.anchorY(rect, each));
            require(atPin == 0.0, "the grip collapses to zero at its own anchor: " + each);
        }

        // 换到对角去固定，量的就是另一条边；这一步搞反就是「往右拖反而变小」
        require(size(rect, HudResize.Mode.WIDTH, HudResize.Anchor.TOP_LEFT, 90, 80) == 10.0,
                "a top-left anchor measures the width from the left edge instead");
        require(size(rect, HudResize.Mode.HEIGHT, HudResize.Anchor.BOTTOM_LEFT, 90, 60) == 30.0,
                "a bottom-left anchor measures the height from the bottom edge instead");
    }

    /** 命中区比可见手柄每边多 2 像素，但也不能宽到把整块矩形都吃掉。 */
    private static void testGripHit() {
        int[] grip = new int[]{100, 100, HudResizeHandle.GRIP_SIZE, HudResizeHandle.GRIP_SIZE};
        require(HudResizeHandle.hit(grip, 102, 102), "the grip itself is a hit");
        require(HudResizeHandle.hit(grip, 98, 100), "2 px of slack outside the grip is a hit");
        require(!HudResizeHandle.hit(grip, 97, 100), "and one px further is not");
        require(HudResizeHandle.hit(grip, 106, 104), "the far slack edge is still a hit");
        require(!HudResizeHandle.hit(grip, 107, 104), "and stops there");
    }

    /**
     * 拖拽条：轨道两端就是声明区间，而且<b>显示位置与真值必须能往返</b>——
     * 否则松手后滑块跳一下，玩家以为自己拖错了。
     */
    private static void testSliderMath() {
        double min = 64;
        double max = 256;

        require(HudOptionPanel.valueAt(TRACK_X, TRACK_WIDTH, min, max, TRACK_X) == min, "the left end is min");
        require(HudOptionPanel.valueAt(TRACK_X, TRACK_WIDTH, min, max, TRACK_X + TRACK_WIDTH - 1) == max,
                "the right end is max");
        require(HudOptionPanel.valueAt(TRACK_X, TRACK_WIDTH, min, max, TRACK_X - 50) == min, "left overshoot clamps");
        require(HudOptionPanel.valueAt(TRACK_X, TRACK_WIDTH, min, max, TRACK_X + 500) == max, "right overshoot clamps");
        require(HudOptionPanel.knobX(TRACK_X, TRACK_WIDTH, min, max, min) == TRACK_X, "min sits at the left end");
        require(HudOptionPanel.knobX(TRACK_X, TRACK_WIDTH, min, max, max) == TRACK_X + TRACK_WIDTH - 1,
                "max sits at the right end");
        require(HudOptionPanel.knobX(TRACK_X, TRACK_WIDTH, min, max, max + 1000) == TRACK_X + TRACK_WIDTH - 1,
                "an out-of-range value must not draw the knob off the track");

        double quantum = (max - min) / (TRACK_WIDTH - 1);
        for (int mouse = TRACK_X; mouse < TRACK_X + TRACK_WIDTH; mouse++) {
            double value = HudOptionPanel.valueAt(TRACK_X, TRACK_WIDTH, min, max, mouse);
            double knob = HudOptionPanel.knobX(TRACK_X, TRACK_WIDTH, min, max, value);
            double back = HudOptionPanel.valueAt(TRACK_X, TRACK_WIDTH, min, max, (int) knob);
            require(Math.abs(knob - mouse) <= 1, "the knob must stay under the cursor: mouse=" + mouse);
            require(Math.abs(back - value) <= quantum / 2 + 1e-9,
                    "value -> knob -> value must stay inside half a pixel: mouse=" + mouse);
        }

        // 没有区间 / 退化区间：不许出现 NaN —— 一个 NaN 写进配置就是坏文件
        require(HudOptionPanel.valueAt(TRACK_X, TRACK_WIDTH, 5, 5, 20) == 5, "an empty range yields its own bound");
        require(HudOptionPanel.valueAt(TRACK_X, 1, min, max, 20) == min, "a 1-pixel track cannot be dragged");
        double unbounded = HudOptionPanel.valueAt(TRACK_X, TRACK_WIDTH,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 20);
        require(!Double.isNaN(unbounded), "an unbounded range must not yield NaN");
        require(HudOptionPanel.knobX(TRACK_X, TRACK_WIDTH, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 7)
                == TRACK_X, "an unbounded range parks the knob instead of dividing by infinity");

        PackConfig.OptionInfo doubleInfo = info(PackConfig.OptionInfo.Kind.DOUBLE, 0, 10, 1, false);
        require(HudOptionPanel.stepForDouble(doubleInfo) == 0.5, "a 0..10 double steps 20 clicks end to end");
        require(HudOptionPanel.stepForDouble(info(PackConfig.OptionInfo.Kind.DOUBLE,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 1, false)) == 0.1,
                "an unbounded double still has a usable step");
        require(HudOptionPanel.snapDouble(doubleInfo, 12) == 10.0, "snapping clamps above");
        require(HudOptionPanel.snapDouble(doubleInfo, -3) == 0.0, "snapping clamps below");
        require(HudOptionPanel.snapDouble(info(PackConfig.OptionInfo.Kind.DOUBLE, 0, 1, 0, false), 0.128) == 0.13,
                "snapping keeps the two decimals that are displayed");
    }

    /**
     * {@code PackConfig.OptionInfo} 的区间判定（面板画不画轨道、写回去钳不钳都靠它）。
     *
     * <p>{@code min == max} 必须判成「不可拖」：轨道是按 {@code max - min} 除的，
     * 判错了就是一条除以零的 NaN，会被写进配置。
     */
    private static void testOptionInfoBounds() {
        PackConfig.OptionInfo bounded = info(PackConfig.OptionInfo.Kind.INT, 64, 256, 128, false);
        require(bounded.isBounded(), "a declared range is draggable");
        require(bounded.clamp(10) == 64 && bounded.clamp(999) == 256 && bounded.clamp(100) == 100,
                "clamp must pin to the declared range without touching in-range values");

        PackConfig.OptionInfo unbounded = info(PackConfig.OptionInfo.Kind.INT,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 5, false);
        require(!unbounded.isBounded(), "no declared range means no track");
        require(unbounded.clamp(1e9) == 1e9, "an unbounded option must not be clamped by the panel");

        require(!info(PackConfig.OptionInfo.Kind.DOUBLE, 7, 7, 7, false).isBounded(),
                "a zero-width range must not be treated as draggable");
        require(!info(PackConfig.OptionInfo.Kind.DOUBLE, 8, 7, 7, false).isBounded(),
                "an inverted range must not be treated as draggable");

        HudConfigOption reference = HudConfigOption.of("map-pack-v2", "map", "size");
        require("map-pack-v2".equals(reference.packId()) && "map".equals(reference.configId())
                && "size".equals(reference.key()),
                "the reference is only an address: it must carry the three parts verbatim");
    }

    private static void requireGrip(HudElement.Bounds rect, HudResize.Mode mode, HudResize.Anchor anchor,
                                    int x, int y, String message) {
        int[] expected = new int[]{x, y, HudResizeHandle.GRIP_SIZE, HudResizeHandle.GRIP_SIZE};
        require(Arrays.equals(HudResizeHandle.gripRect(rect, resize(mode, anchor)), expected),
                message + " (expected " + Arrays.toString(expected) + ")");
    }

    private static double size(HudElement.Bounds rect, HudResize.Mode mode, HudResize.Anchor anchor,
                               double mouseX, double mouseY) {
        return HudResizeHandle.sizeFrom(rect, resize(mode, anchor), mouseX, mouseY);
    }

    private static HudResize resize(HudResize.Mode mode, HudResize.Anchor anchor) {
        return new HudResize(HudConfigOption.of("hud-layout-pack", "editor", "snapGrid"), mode, anchor);
    }

    private static PackConfig.OptionInfo info(PackConfig.OptionInfo.Kind kind, double min, double max,
                                              double defaultValue, boolean needsRestart) {
        return new PackConfig.OptionInfo(kind, min, max, defaultValue, needsRestart, null);
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
