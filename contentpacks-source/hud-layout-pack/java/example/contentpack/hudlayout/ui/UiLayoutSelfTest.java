package example.contentpack.hudlayout.ui;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 容器面板布局的启动自检：只跑纯逻辑（锚定几何、配置容错、夹边、拖动取值、落盘往返）。
 *
 * <p>这些算错的表现是「界面歪几像素」「效果图标压到标题上」「拖完松手跳一下」——游戏里
 * 很难一眼看出，但会被玩家当成 bug。失败记 ERROR，由 {@code scripts/validate-game.ps1} 当 FAIL 抓。
 */
public final class UiLayoutSelfTest {

    private static final String TAG = "UiLayout";

    /** 自检没有文件：日志里标成这个，别让人以为是玩家的配置读不到（Windows 的 Path 不收尖括号）。 */
    private static final Path SELF_TEST = Path.of("self-test");

    private UiLayoutSelfTest() {
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
                LogManager.clientError(TAG, "UI layout self-test FAIL '" + test.getKey() + "': " + error);
            }
        }

        if (failed > 0) {
            LogManager.clientError(TAG, "UI layout self-test FAILURES=" + failed + " passed=" + passed);
        } else {
            LogManager.clientInfo(TAG, "UI layout self-test passed=%d", passed);
        }
    }

    private static Map<String, Runnable> tests() {
        Map<String, Runnable> tests = new LinkedHashMap<>();
        tests.put("effects-anchor-json", UiLayoutSelfTest::testAnchorJson);
        tests.put("effects-block-height", UiLayoutSelfTest::testBlockHeight);
        tests.put("effects-shift-top", UiLayoutSelfTest::testShiftTop);
        tests.put("effects-shift-bottom", UiLayoutSelfTest::testShiftBottom);
        tests.put("effects-max-length", UiLayoutSelfTest::testMaxLength);
        tests.put("effects-horizontal", UiLayoutSelfTest::testHorizontal);
        tests.put("layout-decode", UiLayoutSelfTest::testDecode);
        tests.put("layout-roundtrip", UiLayoutSelfTest::testRoundtrip);
        tests.put("panel-clamp", UiLayoutSelfTest::testClamp);
        tests.put("drag-snap", UiLayoutSelfTest::testDragSnap);
        tests.put("drag-clamp", UiLayoutSelfTest::testDragClamp);
        tests.put("box-snap", UiLayoutSelfTest::testBoxSnap);
        return tests;
    }

    private static void testAnchorJson() {
        for (UiEffectsAnchor.Anchor anchor : UiEffectsAnchor.Anchor.values()) {
            require(UiEffectsAnchor.Anchor.fromJson(anchor.jsonName()) == anchor,
                    "anchor json name must round-trip: " + anchor.jsonName());
            require(UiEffectsAnchor.Anchor.fromJson(anchor.jsonName().toUpperCase(java.util.Locale.ROOT)) == anchor,
                    "anchor json name must be case-insensitive: " + anchor.jsonName());
        }
        require(UiEffectsAnchor.Anchor.fromJson(null) == null, "null must parse as unknown");
        require(UiEffectsAnchor.Anchor.fromJson("left") == null, "an unknown anchor must parse as unknown");

        UiEffectsAnchor defaults = UiEffectsAnchor.fromJson(parse("{}"));
        require(defaults.anchor() == UiEffectsAnchor.Anchor.TOP_RIGHT, "missing anchor means top-right");
        require(defaults.gapX() == 2 && defaults.gapY() == 0 && defaults.maxLength() == 0,
                "missing gaps default to vanilla: " + defaults);
    }

    /** 间距公式必须与原版 {@code renderEffects} 一路：每格 33，超过 5 个压到 132 的跨度。 */
    private static void testBlockHeight() {
        require(UiEffectsAnchor.blockHeight(0) == 33, "an empty column still occupies one cell");
        require(UiEffectsAnchor.blockHeight(1) == 33, "one effect is one cell");
        require(UiEffectsAnchor.blockHeight(5) == 165, "five effects keep the 33px stride");
        require(UiEffectsAnchor.blockHeight(6) == 163, "six effects compress to a 132px span");
        require(UiEffectsAnchor.blockHeight(10) == 159, "ten effects use 132/9 strides");
        require(UiEffectsAnchor.blockHeight(6) < UiEffectsAnchor.blockHeight(5),
                "the compressed column must not keep growing at the old stride");
    }

    private static void testShiftTop() {
        UiEffectsAnchor anchor = new UiEffectsAnchor(UiEffectsAnchor.Anchor.TOP_RIGHT, 2, 0, 0);
        require(anchor.shiftY(20, 166, 3) == 0, "the default top anchor is vanilla: no shift");
        require(new UiEffectsAnchor(UiEffectsAnchor.Anchor.TOP_RIGHT, 2, 5, 0).shiftY(20, 166, 3) == 5,
                "gapY moves the top anchor down by exactly that many pixels");
    }

    private static void testShiftBottom() {
        UiEffectsAnchor anchor = new UiEffectsAnchor(UiEffectsAnchor.Anchor.BOTTOM_RIGHT, 2, 0, 0);
        int panelTop = 20;
        int panelHeight = 166;
        int block = UiEffectsAnchor.blockHeight(3);
        require(anchor.shiftY(panelTop, panelHeight, 3) == panelHeight - block,
                "the shift is measured from the vanilla top: " + block);
        require(new UiEffectsAnchor(UiEffectsAnchor.Anchor.BOTTOM_RIGHT, 2, 7, 0)
                        .shiftY(panelTop, panelHeight, 3) == panelHeight - block - 7,
                "gapY lifts the bottom anchor by that many pixels");
    }

    private static void testMaxLength() {
        UiEffectsAnchor capped = new UiEffectsAnchor(UiEffectsAnchor.Anchor.BOTTOM_RIGHT, 2, 0, 132);
        require(capped.shiftY(20, 166, 3) != 0, "a column that fits is still anchored");
        require(capped.shiftY(20, 166, 6) == 0,
                "a column taller than maxLength falls back to vanilla instead of overflowing");
        require(UiEffectsAnchor.blockHeight(6) > 132, "the fallback case must really be the taller one");
    }

    private static void testHorizontal() {
        UiEffectsAnchor anchor = new UiEffectsAnchor(UiEffectsAnchor.Anchor.TOP_RIGHT, 2, 0, 0);
        require(anchor.horizontalX(100, 176) == 278, "the column starts two pixels right of the panel");
        require(new UiEffectsAnchor(UiEffectsAnchor.Anchor.TOP_RIGHT, 10, 0, 0).horizontalX(100, 176) == 286,
                "gapX is measured from the panel's right edge");
    }

    private static void testDecode() {
        UiLayoutStore.Layout ok = UiLayoutStore.decode(parse("""
                {"schemaVersion":1,"panels":{
                  "minecraft:inventory":{"x":12,"y":-8,"effects":{"anchor":"bottom-right","gapX":4}},
                  "minecraft:chest":{"x":0,"y":0}
                }}"""), SELF_TEST);
        require(ok != null && ok.panels().size() == 2, "both panels must be read: " + ok);
        UiPanelLayout inventory = ok.panels().get(ResourceLocation.withDefaultNamespace("inventory"));
        require(inventory != null && inventory.x() == 12 && inventory.y() == -8,
                "offsets must be read verbatim");
        require(inventory.effects() != null
                        && inventory.effects().anchor() == UiEffectsAnchor.Anchor.BOTTOM_RIGHT
                        && inventory.effects().gapX() == 4,
                "the effects block must be read: " + inventory.effects());
        require(ok.panels().get(ResourceLocation.withDefaultNamespace("chest")).effects() == null,
                "a panel without an effects block keeps vanilla effect placement");

        UiLayoutStore.Layout withElements = UiLayoutStore.decode(parse("""
                {"schemaVersion":1,"panels":{},"elements":{
                  "container-overhaul-pack:side_panel":{"x":6,"y":-4,"effects":{"anchor":"bottom-right"}}
                }}"""), SELF_TEST);
        ResourceLocation sidePanel = ResourceLocation.fromNamespaceAndPath("container-overhaul-pack", "side_panel");
        require(withElements != null && withElements.elements().size() == 1
                        && withElements.elements().get(sidePanel).x() == 6
                        && withElements.elements().get(sidePanel).y() == -4,
                "element offsets must be read from their own section: " + withElements);
        require(withElements.elements().get(sidePanel).effects() == null,
                "the elements section never carries an effects block");

        UiLayoutStore.Layout junk = UiLayoutStore.decode(parse("""
                {"schemaVersion":1,"panels":{"bad id":{"x":1},"broken":7,"ok":{"x":1}}}"""), SELF_TEST);
        require(junk != null && junk.panels().size() == 1
                        && junk.panels().containsKey(ResourceLocation.withDefaultNamespace("ok")),
                "only well-formed panel entries are kept: " + junk);

        UiLayoutStore.Layout typo = UiLayoutStore.decode(parse("""
                {"schemaVersion":1,"panels":{"minecraft:chest":{"effects":{"anchor":"left"}}}}"""), SELF_TEST);
        require(typo != null && typo.panels().get(ResourceLocation.withDefaultNamespace("chest")).effects()
                        .anchor() == UiEffectsAnchor.Anchor.TOP_RIGHT,
                "an unknown anchor falls back to top-right");

        require(UiLayoutStore.decode(parse("{\"schemaVersion\":0,\"panels\":{}}"), SELF_TEST) == null,
                "a version below the floor must be ignored");
        require(UiLayoutStore.decode(parse("{\"schemaVersion\":2,\"panels\":{}}"), SELF_TEST) == null,
                "a version from the future must be ignored");
        require(UiLayoutStore.decode(new JsonObject(), SELF_TEST) == null,
                "a missing schemaVersion must be ignored");
    }

    /** 「可以收纳一半，但不能整个消失」。 */
    private static void testClamp() {
        require(UiGeometry.clampPanel(0, 320, 176) == 0, "an on-screen panel is untouched");
        require(UiGeometry.clampPanel(-500, 320, 176) == UiGeometry.MIN_VISIBLE - 176,
                "a panel dragged far left keeps MIN_VISIBLE pixels on screen");
        require(UiGeometry.clampPanel(500, 320, 176) == 320 - UiGeometry.MIN_VISIBLE,
                "a panel dragged far right keeps MIN_VISIBLE pixels on screen");
        require(UiGeometry.clampPanel(0, 320, 400) == 0, "a panel wider than the screen is not clamped for no reason");
    }

    /**
     * 落盘往返：写出再读回必须一模一样，且默认布局不该出现在文件里
     * （否则每拖一次都会给没动过的面板留一行噪音，和「删文件 = 回原版」的语义也不符）。
     */
    private static void testRoundtrip() {
        ResourceLocation inventory = ResourceLocation.withDefaultNamespace("inventory");
        ResourceLocation chest = ResourceLocation.withDefaultNamespace("chest");
        ResourceLocation anvil = ResourceLocation.withDefaultNamespace("anvil");

        Map<ResourceLocation, UiPanelLayout> panels = new LinkedHashMap<>();
        panels.put(inventory, new UiPanelLayout(12, -8,
                new UiEffectsAnchor(UiEffectsAnchor.Anchor.BOTTOM_RIGHT, 4, 1, 132)));
        panels.put(chest, UiPanelLayout.DEFAULT);
        panels.put(anvil, new UiPanelLayout(-3, 5, null));

        ResourceLocation sidePanel = ResourceLocation.fromNamespaceAndPath("container-overhaul-pack", "side_panel");
        Map<ResourceLocation, UiPanelLayout> elements = new LinkedHashMap<>();
        elements.put(sidePanel, new UiPanelLayout(6, -4, null));
        elements.put(ResourceLocation.withDefaultNamespace("unchanged"), UiPanelLayout.DEFAULT);

        JsonObject encoded = UiLayoutStore.encode(new UiLayoutStore.Layout(panels, elements));
        require(encoded.getAsJsonObject("panels").size() == 2,
                "the default panel must not be written: " + encoded);
        require(encoded.getAsJsonObject("elements").size() == 1,
                "the default element must not be written either: " + encoded);

        Iterator<String> keys = encoded.getAsJsonObject("panels").keySet().iterator();
        require("minecraft:anvil".equals(keys.next()) && "minecraft:inventory".equals(keys.next()),
                "panels must be written in a stable (sorted) order");
        require(encoded.getAsJsonObject("elements").has(sidePanel.toString()),
                "elements keep their own ids: " + encoded);

        UiLayoutStore.Layout back = UiLayoutStore.decode(encoded, SELF_TEST);
        require(back != null && back.panels().size() == 2 && back.elements().size() == 1,
                "everything written must read back: " + back);
        require(panels.get(inventory).equals(back.panels().get(inventory)),
                "offset + effects must round-trip exactly: " + back.panels().get(inventory));
        require(panels.get(anvil).equals(back.panels().get(anvil)), "a plain offset must round-trip exactly");
        require(elements.get(sidePanel).equals(back.elements().get(sidePanel)),
                "an element offset must round-trip exactly: " + back.elements().get(sidePanel));
    }

    /** 吸附作用在绝对位置上；没动鼠标时偏移必须一点不差（否则点一下面板就跳一格）。 */
    private static void testDragSnap() {
        UiDrag drag = new UiDrag(100, 40, 100, 40, 50, 50);
        UiPanelLayout still = drag.apply(50, 50, 2, 320, 240, 176, 166, UiPanelLayout.DEFAULT);
        require(still.x() == 0 && still.y() == 0, "a click without movement must not change the offset: " + still);

        // 100+3=103 → 吸附到 104（偏移 4）；40+2=42 → 保持 42（偏移 2）
        UiPanelLayout moved = drag.apply(53, 52, 2, 320, 240, 176, 166, UiPanelLayout.DEFAULT);
        require(moved.x() == 4 && moved.y() == 2, "snapping works on the absolute position: " + moved);

        UiPanelLayout single = drag.apply(51, 50, 1, 320, 240, 176, 166, UiPanelLayout.DEFAULT);
        require(single.x() == 1 && single.y() == 0, "grid 1 keeps single pixels: " + single);

        UiPanelLayout oddBase = new UiDrag(73, 41, 73, 41, 0, 0)
                .apply(1, 1, 2, 320, 240, 176, 166, UiPanelLayout.DEFAULT);
        require(73 + oddBase.x() == 74 && 41 + oddBase.y() == 42,
                "an odd base still lands on an even absolute position: " + oddBase);

        UiPanelLayout withEffects = drag.apply(60, 50, 1, 400, 400, 176, 166,
                new UiPanelLayout(7, -3, UiEffectsAnchor.VANILLA));
        require(withEffects.effects() == UiEffectsAnchor.VANILLA,
                "dragging must carry the effects anchor through untouched: " + withEffects);
    }

    /**
     * 夹边按面板位置算、偏移最后减出来：拖到屏幕外时结果必须可逆，
     * 否则下一次拖动会拿被夹过的值继续推，越拖越偏。
     */
    private static void testDragClamp() {
        UiDrag drag = new UiDrag(72, 37, 72, 37, 0, 0);

        UiPanelLayout left = drag.apply(-1000, -1000, 2, 320, 240, 176, 166, UiPanelLayout.DEFAULT);
        require(72 + left.x() == UiGeometry.MIN_VISIBLE - 176,
                "dragging far left stops at MIN_VISIBLE on the right edge: " + left);
        require(37 + left.y() == UiGeometry.MIN_VISIBLE - 166,
                "dragging far up stops at MIN_VISIBLE on the bottom edge: " + left);

        UiPanelLayout right = drag.apply(1000, 1000, 2, 320, 240, 176, 166, UiPanelLayout.DEFAULT);
        require(72 + right.x() == 320 - UiGeometry.MIN_VISIBLE, "dragging far right stops at the screen edge: " + right);
        require(37 + right.y() == 240 - UiGeometry.MIN_VISIBLE, "dragging far down stops at the screen edge: " + right);

        for (UiPanelLayout state : new UiPanelLayout[]{left, right}) {
            require(UiGeometry.clampPanel(72 + state.x(), 320, 176) == 72 + state.x(),
                    "a clamped result must already be inside the clamp: " + state);
        }
    }

    /** 框对框吸附：左/中/右三条线都参与；同距离保先来的候选线（优先级）。 */
    private static void testBoxSnap() {
        int[] left = UiSnap.axis(100, 20, List.of(102), 2);
        require(left != null && left[0] == 102 && left[1] == 102,
                "the left edge snaps onto the line: " + describe(left));

        int[] center = UiSnap.axis(100, 20, List.of(112), 2);
        require(center != null && center[0] == 102 && center[1] == 112,
                "the center snaps with a half-width shift: " + describe(center));

        int[] right = UiSnap.axis(100, 20, List.of(121), 2);
        require(right != null && right[0] == 101 && right[1] == 121,
                "the right edge snaps too: " + describe(right));

        require(UiSnap.axis(100, 20, List.of(103), 2) == null,
                "a line beyond the threshold must not pull the box");
        require(UiSnap.axis(100, 20, List.of(), 2) == null, "no candidates means no snap");

        int[] tie = UiSnap.axis(100, 20, List.of(101, 99), 2);
        require(tie != null && tie[1] == 101, "ties keep the earlier (higher priority) line: " + describe(tie));
    }

    private static String describe(int[] snap) {
        return snap == null ? "null" : snap[0] + "@" + snap[1];
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
