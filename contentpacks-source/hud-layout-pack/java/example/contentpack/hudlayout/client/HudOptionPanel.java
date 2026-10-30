package example.contentpack.hudlayout.client;

import com.catoxide.catoxidesbattlerebuild.core.config.PackConfig;
import com.catoxide.catoxidesbattlerebuild.core.config.PackConfigRegistry;
import com.catoxide.catoxidesbattlerebuild.core.hud.HudConfigOption;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 一个元素的选项面板：把 {@link HudConfigOption} 引用渲染成能点、能拖的控件，写回所属包的配置。
 *
 * <p>控件形态由<b>配置声明自己</b>决定，不是这里再声明一遍：
 * <ul>
 *   <li>{@code bool} → 整行是开关</li>
 *   <li>{@code int} / {@code double} 且声明了有限区间 → 拖拽条（轨道两端就是玩家能拖到的边界）</li>
 *   <li>{@code int} / {@code double} 没声明区间 → {@code -} / {@code +} 步进按钮（没有边界就画不出可拖的轨道，硬画会把值甩到天际）</li>
 *   <li>字符串类 → 只显示当前值，要点开内置配置屏改</li>
 * </ul>
 *
 * <p>生效时机看档位：HOT 写完内存值同帧生效，所以条能跟手；WARM/COLD 的值虽然写进去了，
 * {@code get()} 仍返回旧值，这一行标成橙色并在底部说明「需重载」——不是写失败，是还没到生效的时候。
 *
 * <p>落盘防抖：拖动过程中只改内存，松手或关屏才 {@code save()} 一次，免得每个鼠标事件重写一遍 TOML。
 */
public final class HudOptionPanel {

    private static final String TAG = "HudLayout";
    private static final int ROW_HEIGHT = 12;
    private static final int PADDING = 4;
    private static final int LABEL_MIN_WIDTH = 60;
    private static final int LABEL_MAX_WIDTH = 110;
    private static final int TRACK_WIDTH = 46;
    private static final int VALUE_WIDTH = 28;
    private static final int STEP_BUTTON_WIDTH = 9;
    /** 标签列之后的控件区总宽（轨道 + 间距 + 数值）。 */
    private static final int CONTROL_WIDTH = 80;

    private static final int COLOR_BACKGROUND = 0xE0101010;
    private static final int COLOR_OUTLINE = 0xFF66DDFF;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_DIM = 0xFF909090;
    private static final int COLOR_WARN = 0xFFFFB04F;
    private static final int COLOR_TRACK = 0xFF404040;
    private static final int COLOR_FILL = 0xFF22CCFF;

    /** 一行：引用 + 解析到的配置句柄 + 从声明读回来的元数据 + 显示名。 */
    private record Row(HudConfigOption option, PackConfig config, PackConfig.OptionInfo info, Component label) {
    }

    private final List<Row> rows;
    private final Set<PackConfig> touched = new LinkedHashSet<>();
    private final Set<String> reportedFailures = new HashSet<>();
    private final String title;

    private String draggingKey;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    private HudOptionPanel(String title, List<Row> rows) {
        this.title = title;
        this.rows = List.copyOf(rows);
    }

    /**
     * 解析引用并建行。
     *
     * <p>解析不到就跳过并告警（目标包没装 / 配置被声明层拒绝 / 键被改名）——<b>不抛异常</b>：
     * 面板少一行，比编辑屏整个打不开好得多。
     */
    public static HudOptionPanel build(String title, List<HudConfigOption> references) {
        List<Row> built = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (HudConfigOption reference : references) {
            String identity = reference.packId() + "/" + reference.configId() + "/" + reference.key();
            if (!seen.add(identity)) {
                continue;
            }
            Handle handle = resolve(reference);
            if (handle == null) {
                LogManager.clientWarn(TAG, "HUD option reference '%s' skipped: that config or key is not registered",
                        identity);
                continue;
            }
            built.add(new Row(handle.option(), handle.config(), handle.info(), handle.label()));
        }
        return new HudOptionPanel(title, built);
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    // ============================================================
    // 绘制
    // ============================================================

    public void render(GuiGraphics graphics, Font font, int screenWidth, int screenHeight,
                       int nearX, int nearY, int mouseX, int mouseY) {
        layout(font, screenWidth, screenHeight, nearX, nearY);

        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, COLOR_BACKGROUND);
        graphics.renderOutline(panelX, panelY, panelWidth, panelHeight, COLOR_OUTLINE);
        graphics.drawString(font, title, panelX + PADDING, panelY + PADDING, COLOR_FILL, true);

        int labelColumn = labelWidth(font);
        int rowTop = panelY + PADDING + ROW_HEIGHT;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int y = rowTop + i * ROW_HEIGHT;
            if (mouseX >= panelX && mouseX < panelX + panelWidth && mouseY >= y && mouseY < y + ROW_HEIGHT) {
                graphics.fill(panelX + 1, y, panelX + panelWidth - 1, y + ROW_HEIGHT, 0x3322CCFF);
            }
            drawRow(graphics, font, row, panelX + PADDING, y, labelColumn);
        }

        if (needsRestartNotice()) {
            graphics.drawString(font, Component.translatable("hudlayout.panel.restart_hint"),
                    panelX + PADDING, rowTop + rows.size() * ROW_HEIGHT + 1, COLOR_WARN, false);
        }
    }

    private void drawRow(GuiGraphics graphics, Font font, Row row, int x, int y, int labelColumn) {
        int labelEnd = x + labelColumn;
        PackConfig.OptionInfo.Kind kind = row.info().kind();

        graphics.drawString(font, trim(font, row.label(), labelColumn), x, y + 2, COLOR_TEXT, false);

        switch (kind) {
            case BOOL -> {
                int box = ROW_HEIGHT - 5;
                graphics.renderOutline(labelEnd + 2, y + 2, box, box, COLOR_DIM);
                if (readBoolean(row)) {
                    graphics.fill(labelEnd + 3, y + 3, labelEnd + box + 1, y + box + 1, COLOR_FILL);
                }
            }
            case INT, DOUBLE -> {
                if (row.info().isBounded()) {
                    drawTrack(graphics, font, row, labelEnd, y);
                } else {
                    drawStepper(graphics, font, row, labelEnd, y);
                }
            }
            case STRING, STRING_LIST -> graphics.drawString(font,
                    trim(font, Component.literal(readText(row)), CONTROL_WIDTH - 2),
                    labelEnd + 2, y + 2, COLOR_DIM, false);
        }
    }

    private void drawTrack(GuiGraphics graphics, Font font, Row row, int labelEnd, int y) {
        int trackX = labelEnd + 2;
        int trackY = y + 4;
        PackConfig.OptionInfo info = row.info();
        int knob = knobX(trackX, TRACK_WIDTH, info.min(), info.max(), readNumber(row));

        graphics.fill(trackX, trackY, trackX + TRACK_WIDTH, trackY + 3, COLOR_TRACK);
        graphics.fill(trackX, trackY, knob + 1, trackY + 3, COLOR_FILL);
        graphics.fill(knob, trackY - 1, knob + 1, trackY + 4, COLOR_TEXT);
        drawValue(graphics, font, row, trackX + TRACK_WIDTH + 2, y);
    }

    private void drawStepper(GuiGraphics graphics, Font font, Row row, int labelEnd, int y) {
        int minus = labelEnd + 2;
        int plus = minus + STEP_BUTTON_WIDTH + VALUE_WIDTH + 2;
        graphics.fill(minus, y + 1, minus + STEP_BUTTON_WIDTH, y + ROW_HEIGHT - 1, COLOR_TRACK);
        graphics.fill(plus, y + 1, plus + STEP_BUTTON_WIDTH, y + ROW_HEIGHT - 1, COLOR_TRACK);
        graphics.drawString(font, "-", minus + 3, y + 2, COLOR_TEXT, false);
        graphics.drawString(font, "+", plus + 3, y + 2, COLOR_TEXT, false);
        drawValue(graphics, font, row, minus + STEP_BUTTON_WIDTH + 2, y);
    }

    private void drawValue(GuiGraphics graphics, Font font, Row row, int x, int y) {
        graphics.drawString(font, format(row), x, y + 2,
                row.info().needsRestart() ? COLOR_WARN : COLOR_TEXT, false);
    }

    // ============================================================
    // 输入
    // ============================================================

    private boolean contains(double mouseX, double mouseY) {
        return mouseX >= panelX && mouseX < panelX + panelWidth
                && mouseY >= panelY && mouseY < panelY + panelHeight;
    }

    /** @return 是否消费了这次点击（面板内一律消费，不让它穿透去拖手柄） */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!contains(mouseX, mouseY)) {
            return false;
        }
        Row row = rowAt((int) mouseY);
        if (row != null) {
            switch (row.info().kind()) {
                case BOOL -> {
                    if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                        writeBoolean(row, !readBoolean(row));
                    } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                        resetToDefault(row);
                    }
                }
                case INT, DOUBLE -> {
                    if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                        if (row.info().isBounded()) {
                            draggingKey = row.option().key();
                            applyTrack(row, (int) mouseX);
                        } else {
                            applyStep(row, (int) mouseX);
                        }
                    } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                        resetToDefault(row);
                    }
                }
                case STRING, STRING_LIST -> {
                    // 只读：字符串改不动，别让玩家以为能拖
                }
            }
        }
        return true;
    }

    public boolean mouseDragged(double mouseX, double mouseY) {
        if (draggingKey == null) {
            return false;
        }
        Row row = rowForKey(draggingKey);
        if (row != null && row.info().isBounded()) {
            applyTrack(row, (int) mouseX);
            return true;
        }
        return false;
    }

    public void mouseReleased() {
        draggingKey = null;
    }

    /** 松手或关屏时把改过的配置写盘；没改过就什么都不做。 */
    public void commit() {
        for (PackConfig config : touched) {
            try {
                config.save();
            } catch (RuntimeException e) {
                LogManager.clientWarn(TAG, "Cannot save pack config '%s/%s': %s",
                        config.id(), config.fileName(), e.getMessage());
            }
        }
        touched.clear();
    }

    // ============================================================
    // 读写
    // ============================================================

    private void applyTrack(Row row, int mouseX) {
        PackConfig.OptionInfo info = row.info();
        int trackX = panelX + PADDING + labelWidth(Minecraft.getInstance().font) + 2;
        writeNumber(row, valueAt(trackX, TRACK_WIDTH, info.min(), info.max(), mouseX));
    }

    private void applyStep(Row row, int mouseX) {
        Font font = Minecraft.getInstance().font;
        int minus = panelX + PADDING + labelWidth(font) + 2;
        int plus = minus + STEP_BUTTON_WIDTH + VALUE_WIDTH + 2;
        PackConfig.OptionInfo info = row.info();
        double step = info.kind() == PackConfig.OptionInfo.Kind.INT ? 1 : stepForDouble(info);
        if (mouseX < minus + STEP_BUTTON_WIDTH) {
            writeNumber(row, readNumber(row) - step);
        } else if (mouseX >= plus && mouseX < plus + STEP_BUTTON_WIDTH) {
            writeNumber(row, readNumber(row) + step);
        }
    }

    private void resetToDefault(Row row) {
        writeNumber(row, row.info().defaultValue());
    }

    private void writeBoolean(Row row, boolean value) {
        try {
            row.config().setBoolean(row.option().key(), value);
            touched.add(row.config());
        } catch (RuntimeException e) {
            fail(row, e);
        }
    }

    private void writeNumber(Row row, double value) {
        if (row.info().kind() == PackConfig.OptionInfo.Kind.BOOL) {
            writeBoolean(row, value != 0);
            return;
        }
        if (write(new Handle(row.option(), row.config(), row.info()), value)) {
            touched.add(row.config());
        } else {
            fail(row, new IllegalStateException("the config refused the value " + value));
        }
    }

    private double readNumber(Row row) {
        return read(new Handle(row.option(), row.config(), row.info()));
    }

    private boolean readBoolean(Row row) {
        try {
            return row.config().getBoolean(row.option().key());
        } catch (RuntimeException e) {
            fail(row, e);
            return row.info().defaultValue() != 0;
        }
    }

    private String readText(Row row) {
        try {
            return row.info().kind() == PackConfig.OptionInfo.Kind.STRING_LIST
                    ? String.join(",", row.config().getStringList(row.option().key()))
                    : row.config().getString(row.option().key());
        } catch (RuntimeException e) {
            fail(row, e);
            return "";
        }
    }

    private String format(Row row) {
        if (row.info().kind() == PackConfig.OptionInfo.Kind.INT) {
            return Integer.toString((int) Math.round(readNumber(row)));
        }
        return String.format(Locale.ROOT, "%.2f", readNumber(row));
    }

    private void fail(Row row, RuntimeException e) {
        String identity = row.option().packId() + "/" + row.option().configId() + "/" + row.option().key();
        if (reportedFailures.add(identity)) {
            LogManager.clientWarn(TAG, "HUD option '%s' rejected the panel's access: %s", identity, e);
        }
    }

    /**
     * 解析引用 → （配置句柄 + 声明元数据）。
     *
     * <p>编辑屏的拉伸路径也走这里：面板和拉伸手柄写的是同一个键，
     * 「按声明类型分派写入」这条规则必须只有一份，否则拖出来的值和条上显示的值会走岔。
     *
     * @return {@code null} 表示解析不到（包没装 / 配置被拒 / 键不存在），调用方跳过即可
     */
    @Nullable
    public static Handle resolve(HudConfigOption reference) {
        PackConfig config = PackConfigRegistry.get(reference.packId(), reference.configId()).orElse(null);
        if (config == null) {
            return null;
        }
        try {
            return new Handle(reference, config, config.info(reference.key()));
        } catch (RuntimeException e) {
            LogManager.clientWarn(TAG, "HUD option '%s/%s/%s' cannot be resolved: %s",
                    reference.packId(), reference.configId(), reference.key(), e);
            return null;
        }
    }

    /** 已解析的选项：引用 + 句柄 + 声明元数据。 */
    public record Handle(HudConfigOption option, PackConfig config, PackConfig.OptionInfo info) {

        public Component label() {
            return Component.translatable(info.translationKey() == null ? option.key() : info.translationKey());
        }
    }

    /**
     * 按声明类型写入数值（整数四舍五入、浮点落两位，两端都由 {@link PackConfig.OptionInfo#clamp} 钳在区间内）。
     *
     * <p>只改内存，不写盘；写盘由调用方在动作结束时做一次。
     *
     * @return 是否写成功。失败<b>不记日志</b>——拖一条每帧都写，刷屏的日志会把真正的错误埋掉，
     *         调用方自己做过一次「告警一次」的登记
     */
    public static boolean write(Handle handle, double value) {
        try {
            if (handle.info().kind() == PackConfig.OptionInfo.Kind.INT) {
                handle.config().setInt(handle.option().key(), (int) Math.round(value));
            } else {
                handle.config().setDouble(handle.option().key(), snapDouble(handle.info(), value));
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 读当前值；读不到（配置未装载等）回落到声明默认值，同样不记日志。 */
    public static double read(Handle handle) {
        try {
            return handle.info().kind() == PackConfig.OptionInfo.Kind.INT
                    ? handle.config().getInt(handle.option().key())
                    : handle.config().getDouble(handle.option().key());
        } catch (RuntimeException e) {
            return handle.info().defaultValue();
        }
    }

    // ============================================================
    // 布局与纯计算（纯计算抽成静态：启动检不用造 Font 和屏幕就能验）
    // ============================================================

    private void layout(Font font, int screenWidth, int screenHeight, int nearX, int nearY) {
        int noticeHeight = needsRestartNotice() ? ROW_HEIGHT : 0;
        panelWidth = PADDING * 2 + labelWidth(font) + CONTROL_WIDTH;
        panelHeight = PADDING * 2 + ROW_HEIGHT * (rows.size() + 1) + noticeHeight;
        panelX = Math.max(2, Math.min(nearX, screenWidth - panelWidth - 2));
        panelY = Math.max(2, Math.min(nearY, screenHeight - panelHeight - 2));
    }

    /** 标签列宽：本面板最宽的标签，夹在下限和「面板别占半屏」之间。 */
    private int labelWidth(Font font) {
        int widest = LABEL_MIN_WIDTH;
        for (Row row : rows) {
            widest = Math.max(widest, font.width(row.label()));
        }
        return Math.min(widest, LABEL_MAX_WIDTH);
    }

    private Row rowAt(int mouseY) {
        int index = (mouseY - panelY - PADDING - ROW_HEIGHT) / ROW_HEIGHT;
        return index >= 0 && index < rows.size() ? rows.get(index) : null;
    }

    private Row rowForKey(String key) {
        for (Row row : rows) {
            if (row.option().key().equals(key)) {
                return row;
            }
        }
        return null;
    }

    /**
     * 拖拽条取值：轨道两端就是声明区间。
     *
     * <p>抽成静态纯函数是为了能被启动自检直接算——面板本身要 Font 和屏幕，测不动。
     *
     * <p>没声明区间的选项走不到这里（画的是步进按钮），但真被调用时<b>宁可不给值也不给 NaN</b>：
     * 一个 NaN 写进配置就是一份坏文件，而 {@code max - min} 在无穷区间上是 {@code Infinity}，
     * {@code -Infinity + f * Infinity} 正好是 NaN。
     */
    static double valueAt(int trackX, int trackWidth, double min, double max, int mouseX) {
        if (!Double.isFinite(min) || !Double.isFinite(max)) {
            return 0.0;
        }
        if (trackWidth <= 1 || max <= min) {
            return min;
        }
        double fraction = (mouseX - trackX) / (double) (trackWidth - 1);
        return min + Math.max(0.0, Math.min(1.0, fraction)) * (max - min);
    }

    /** {@link #valueAt} 的逆运算：值 → 滑块像素位置。 */
    static int knobX(int trackX, int trackWidth, double min, double max, double value) {
        if (!Double.isFinite(min) || !Double.isFinite(max) || !Double.isFinite(value)
                || trackWidth <= 1 || max <= min) {
            return trackX;
        }
        double fraction = (value - min) / (max - min);
        return trackX + (int) Math.round(Math.max(0.0, Math.min(1.0, fraction)) * (trackWidth - 1));
    }

    /** 浮点步进按区间宽度摊开：从一端点到另一端约 20 下，既点得动也点得到边界。 */
    static double stepForDouble(PackConfig.OptionInfo info) {
        double span = info.max() - info.min();
        if (!Double.isFinite(span) || span <= 0) {
            return 0.1;
        }
        return snapDouble(info, span / 20.0);
    }

    /** 浮点值落到两位小数：显示是两位，写回去也必须是两位，否则读数与真值不一致。 */
    static double snapDouble(PackConfig.OptionInfo info, double value) {
        return Math.round(info.clamp(value) * 100.0) / 100.0;
    }

    private boolean needsRestartNotice() {
        for (Row row : rows) {
            if (row.info().needsRestart()) {
                return true;
            }
        }
        return false;
    }

    private static Component trim(Font font, Component text, int maxWidth) {
        String plain = text.getString();
        if (font.width(plain) <= maxWidth) {
            return text;
        }
        int end = plain.length();
        while (end > 1 && font.width(plain.substring(0, end) + "...") > maxWidth) {
            end--;
        }
        return Component.literal(plain.substring(0, end) + "...");
    }
}
