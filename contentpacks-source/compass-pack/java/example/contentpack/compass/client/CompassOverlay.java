package example.contentpack.compass.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.DeltaTracker;
import net.minecraft.util.Mth;

/**
 * 顶部罗盘渲染器（端口自 SkyrimCompassHUD 的 CompassOverlay）。
 *
 * <h3>1.21.1 映射（相对 MIT 原版 Forge 1.20.1）</h3>
 * <ul>
 *   <li>IGuiOverlay/ForgeGui 已废止 → 不再实现 Layer，改由全局 {@code RenderGuiEvent$Pre} 事件回调（见
 *       {@link CompassClientEvents}），render 签名 2 参 (GuiGraphics, DeltaTracker)</li>
 *   <li>render 签名 5 参 (ForgeGui, GuiGraphics, partialTick, width, height)
 *       → 2 参 (GuiGraphics, DeltaTracker)；width 改从 {@link Minecraft#getWindow()#getGuiScaledWidth()} 取；
 *       partialTick 改从 {@code DeltaTracker.getGameTimeDeltaPartialTick(false)} 取</li>
 *   <li>渲染逻辑（坐标/色值/刻度/指针）逐行保留，不改画风格</li>
 * </ul>
 *
 * <h3>modify（Hood 允许）</h3>
 * 1.21.1 的 {@code Mth.lerp} 公式与 1.20.1 不同：{@code lerp(a,b,c) = b + a*(c-b)}
 * （a=系数、b=起值、c=止值），而 1.20.1 为经典式 {@code lerp(a,b,c) = a + (b-a)*c}。
 * 两版下 {@code lerp(partialTick, yRotO, getYRot())} 的含义不同；
 * 为彻底规避 1.20.1↔1.21.1 的参数顺序陷阱，把 MIT 的 {@code Mth.lerp} 表达式
 * 显式展开为经典插值 {@code yRotO + partialTick*(getYRot()-yRotO)} ——
 * 这正是原版渲染器（LevelRenderer / ItemInHandRenderer）
 * {@code Mth.lerp(partialTick, yRotO, yRot)} 在 1.21.1 下的取值，平滑效果与实体渲染一致。
 *
 * <h3>渲染顺序（Jade 覆盖问题）</h3>
 * 本类不再作为 {@link net.minecraft.client.gui.LayeredDraw.Layer} 注册（Jade 不走 Layer 体系，
 * 挂在全局 {@code RenderGuiEvent$Post} 绘制，Layer 无法排在其下）。
 * 改由 {@link CompassClientEvents} 挂到全局总线 {@code RenderGuiEvent$Pre}：
 * Pre 先于所有 Layer、也先于 Jade 的 Post，从而结构保证罗盘低于 Jade。
 */
public final class CompassOverlay {

    public static final CompassOverlay INSTANCE = new CompassOverlay();

    private static final String[] LABELS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final float[] ANGLES = {0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f};

    private CompassOverlay() {
    }

    /**
     * 由 {@link CompassClientEvents#init} 挂到全局总线 {@code RenderGuiEvent$Pre} 调用。
     * <p>读取 {@link CompassConfig}（run\client\config\compass-pack-client.toml）决定三种风格：
     * ①刻度（{@code fovTicks}）②简化数字版（{@code numberOnly}）③条厚 + 磨砂背景
     * （{@code thickBar} / {@code frostedBackground}）。默认值与当前（无配置）外观一致。
     */
    public void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!CompassClientEvents.visible || minecraft.options.hideGui || minecraft.player == null) {
            return;
        }

        Font font = minecraft.font;
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        // 头转向插值 = yRotO + partialTick*(getYRot()-yRotO)，
        // 即原版 Mth.lerp(partialTick, yRotO, yRot) 在 1.21.1 下的取值；
        // 显式展开，避开 1.21.1 Mth.lerp 的参数顺序陷阱。
        float yaw = minecraft.player.yRotO + (minecraft.player.getYRot() - minecraft.player.yRotO) * partialTick;
        float bearing = Mth.positiveModulo(yaw + 180.0f, 360.0f);

        // 宽度取 GUI 缩放坐标系 getGuiScaledWidth（= 0..framebufferWidth/guiSpace）。
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int barWidth = Math.min(300, screenWidth - 24);
        int left = (screenWidth - barWidth) / 2;
        int right = left + barWidth;
        int centerX = screenWidth / 2;

        String degrees = Integer.toString(Math.round(bearing) % 360) + "°";

        // ===== 简化版：只画当前方位数字（背景随 frostedBackground 开/关） =====
        if (CompassConfig.numberOnly()) {
            int top = 8;
            // 1.21.1 的 Font 没有 height()；固定高度做背景，文本垂直居中用固定偏移
            int pillH = 18;
            int textW = font.width(degrees);
            if (CompassConfig.frostedBackground()) {
                graphics.fill(left, top, right, top + pillH, 0x78000000);
                graphics.fill(left, top + pillH - 1, right, top + pillH, 0x66FFFFFF);
            }
            // drawString 的 y 是文本顶端；18px 高的胶囊里放 ~12px 文本，偏移 4 近似居中
            graphics.drawString(font, degrees, centerX - textW / 2, top + 4, 0xCCFFFFFF, true);
            return;
        }

        // ===== 完整罗盘条 =====
        // 条厚（配置 thickBar）
        int top = 8, bottom = 34;
        if (CompassConfig.thickBar()) {
            top = 5;
            bottom = 38;
        }

        // 磨砂黑背景（配置 frostedBackground）
        if (CompassConfig.frostedBackground()) {
            graphics.fill(left, top, right, bottom, 0x78000000);
            graphics.fill(left, bottom - 1, right, bottom, 0x66FFFFFF);
        }

        // 刻度窗口：均匀 110°（当前） 或 按 FOV 模拟实际刻度
        // 1.21.1：选项类是 net.minecraft.client.Options（从 GameOptions 改名），
        // FOV 是 OptionInstance<Integer>，值通过 fov().get() 取。null/退化值回退默认 75。
        float visibleDegrees;
        if (CompassConfig.fovTicks()) {
            Integer fov = minecraft.options.fov().get();
            visibleDegrees = (fov == null || fov <= 0 || fov > 200) ? 75f : (float) fov;
        } else {
            visibleDegrees = 110f;
        }
        float pixelsPerDegree = barWidth / visibleDegrees;

        // 每 15° 一个刻度（major 每 45°）
        for (int angle = 0; angle < 360; angle += 15) {
            float delta = Mth.wrapDegrees(angle - bearing);
            int x = Math.round(centerX + delta * pixelsPerDegree);
            if (x < left + 4 || x > right - 4) {
                continue;
            }
            boolean major = angle % 45 == 0;
            int markTop = major ? top + 5 : top + 9;
            int markBottom = major ? top + 12 : top + 13;
            int color = major ? 0xFFE7E1D2 : 0x99C8C2B5;
            graphics.fill(x, markTop, x + 1, markBottom, color);
        }

        // 正/斜向方位标签
        for (int i = 0; i < ANGLES.length; i++) {
            float delta = Mth.wrapDegrees(ANGLES[i] - bearing);
            int x = Math.round(centerX + delta * pixelsPerDegree);
            if (x < left + 12 || x > right - 12) {
                continue;
            }
            String label = LABELS[i];
            int color = Math.abs(delta) < 4.0f ? 0xFFFFF1C7 : 0xFFE7E1D2;
            graphics.drawString(font, label, x - font.width(label) / 2, top + 16, color, true);
        }

        // 固定中指针（Skyrim 风格，不复制其素材）
        graphics.fill(centerX, top + 2, centerX + 1, bottom - 3, 0xFFFFD980);
        graphics.fill(centerX - 3, top + 2, centerX + 4, top + 3, 0xFFFFD980);
        graphics.fill(centerX - 2, top + 3, centerX + 3, top + 4, 0xFFFFD980);

        // 当前方位数字（条下方）
        graphics.drawString(font, degrees, centerX - font.width(degrees) / 2, bottom + 2, 0xCCFFFFFF, true);
    }
}
