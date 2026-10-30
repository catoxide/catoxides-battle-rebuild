package example.contentpack.mapv2.client;

import com.catoxide.catoxidesbattlerebuild.core.hud.HudLayoutApi;
import example.contentpack.mapv2.MapView;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * 顶部罗盘渲染器（原 compass-pack，作为 map-pack 的 HUD 组件）。
 *
 * <h3>方位对齐</h3>
 * <ul>
 *   <li>{@code perspective}（默认）：屏幕横向是透视投影，按
 *       {@code tan(Δ)/tan(FOV/2)} 映射，标记点和屏幕上看到的方向一致；</li>
 *   <li>{@code centerOnly}：用参考 FOV（默认 70°）投影，再叠一个用户缩放，
 *       保证中心对齐、两侧密度可调；</li>
 *   <li>{@code linear}：原版 110° 线性刻度。</li>
 * </ul>
 */
public final class CompassOverlay {

    public static final CompassOverlay INSTANCE = new CompassOverlay();

    private static final String[] LABELS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final float[] ANGLES = {0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f};
    private static final int OUTLINE_COLOR = 0xB0101010;

    private CompassOverlay() {
    }

    public void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!MapV2ClientEvents.compassVisible || minecraft.options.hideGui || minecraft.player == null) {
            return;
        }

        Font font = minecraft.font;
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        float yaw = MapView.yaw(minecraft.player, partialTick);
        float bearing = Mth.positiveModulo(yaw + 180.0f, 360.0f);

        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int dx = HudLayoutApi.offsetX(CompassHudElement.ID);
        int dy = HudLayoutApi.offsetY(CompassHudElement.ID);
        int barWidth = Math.min(300, screenWidth - 24);
        int left = (screenWidth - barWidth) / 2 + dx;
        int right = left + barWidth;
        int centerX = screenWidth / 2 + dx;

        String degrees = Integer.toString(Math.round(bearing) % 360) + "°";

        if (CompassConfig.numberOnly()) {
            int top = 8 + dy;
            int pillH = 18;
            int textW = font.width(degrees);
            if (CompassConfig.frostedBackground()) {
                graphics.fill(left, top, right, top + pillH, 0x78000000);
                graphics.fill(left, top + pillH - 1, right, top + pillH, 0x66FFFFFF);
            }
            drawText(graphics, font, degrees, centerX - textW / 2, top + 4, 0xCCFFFFFF);
            return;
        }

        int top = 8 + dy;
        int bottom = 34 + dy;
        if (CompassConfig.thickBar()) {
            top = 5 + dy;
            bottom = 38 + dy;
        }

        if (CompassConfig.frostedBackground()) {
            graphics.fill(left, top, right, bottom, 0x78000000);
            graphics.fill(left, bottom - 1, right, bottom, 0x66FFFFFF);
        }

        int mode = CompassConfig.alignMode();
        float fov = resolveFov(minecraft, mode);
        double scale = mode == 1 ? CompassConfig.compassScale() : 1.0;

        for (int angle = 0; angle < 360; angle += 15) {
            float delta = Mth.wrapDegrees(angle - bearing);
            if (Math.abs(delta) >= fov / 2.0f && mode != 2) {
                continue;
            }
            int x = centerX + (int) Math.round(project(delta, mode, fov, barWidth, scale));
            if (x < left + 4 || x > right - 4) {
                continue;
            }
            boolean major = angle % 45 == 0;
            int markTop = major ? top + 5 : top + 9;
            int markBottom = major ? top + 12 : top + 13;
            int color = major ? 0xFFE7E1D2 : 0x99C8C2B5;
            graphics.fill(x, markTop, x + 1, markBottom, color);
        }

        for (int i = 0; i < ANGLES.length; i++) {
            float delta = Mth.wrapDegrees(ANGLES[i] - bearing);
            if (Math.abs(delta) >= fov / 2.0f && mode != 2) {
                continue;
            }
            int x = centerX + (int) Math.round(project(delta, mode, fov, barWidth, scale));
            if (x < left + 12 || x > right - 12) {
                continue;
            }
            String label = LABELS[i];
            int color = Math.abs(delta) < 4.0f ? 0xFFFFF1C7 : 0xFFE7E1D2;
            drawText(graphics, font, label, x - font.width(label) / 2, top + 16, color);
        }

        graphics.fill(centerX, top + 2, centerX + 1, bottom - 3, 0xFFFFD980);
        graphics.fill(centerX - 3, top + 2, centerX + 4, top + 3, 0xFFFFD980);
        graphics.fill(centerX - 2, top + 3, centerX + 3, top + 4, 0xFFFFD980);

        drawText(graphics, font, degrees, centerX - font.width(degrees) / 2, bottom + 2, 0xCCFFFFFF);

        MarkerHud.renderCompass(graphics, font, centerX, barWidth, left, right, top, bottom,
                bearing, fov, delta -> project((float) delta, mode, fov, barWidth, scale),
                partialTick);
    }

    private static float resolveFov(Minecraft minecraft, int mode) {
        if (mode == 1) {
            return CompassConfig.referenceFov();
        }
        if (mode == 2) {
            if (!CompassConfig.fovTicks()) {
                return 110f;
            }
        }
        Integer fov = minecraft.options.fov().get();
        return (fov == null || fov <= 0 || fov > 200) ? 75f : (float) fov;
    }

    /** 相对罗盘中心的像素偏移（标记点也走同一投影）。 */
    public static double project(float deltaDegrees, int mode, float fov,
                                  int barWidth, double scale) {
        if (mode == 2) {
            return (double) deltaDegrees / fov * barWidth;
        }
        double half = Math.toRadians(fov) / 2.0;
        double ndc = Math.tan(Math.toRadians(deltaDegrees)) / Math.tan(half);
        return ndc * (barWidth / 2.0) * scale;
    }

    /** 文字样式：0 无、1 阴影、2 四向描边（半透明深灰，不是纯黑框）。 */
    private static void drawText(GuiGraphics graphics, Font font, String text,
                                 int x, int y, int color) {
        int style = CompassConfig.textStyle();
        if (style == 2) {
            for (int oy = -1; oy <= 1; oy++) {
                for (int ox = -1; ox <= 1; ox++) {
                    if (ox == 0 && oy == 0) {
                        continue;
                    }
                    graphics.drawString(font, text, x + ox, y + oy, OUTLINE_COLOR, false);
                }
            }
            graphics.drawString(font, text, x, y, color, false);
            return;
        }
        graphics.drawString(font, text, x, y, color, style == 1);
    }
}
