package example.contentpack.mapv2.client;

import com.mojang.blaze3d.systems.RenderSystem;
import example.contentpack.mapv2.MapHooks;
import example.contentpack.mapv2.view.MapProjection;
import example.contentpack.mapv2.MapView;
import example.contentpack.mapv2.marker.MarkerData;
import example.contentpack.mapv2.marker.MarkerDto;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

/** 标记在小地图 / 全屏地图 / 屏幕指示 / 罗盘上的绘制。 */
final class MarkerHud {

    private static final double FADE_MILLIS = 2_000.0;
    /** 超过这个距离只显示在罗盘上，不再占屏幕。 */
    private static final double MAX_SCREEN_DISTANCE = 64.0;
    /**
     * 「聚焦」锥半角（度）：准星离标记中心这么近才显示名称/距离。
     * 与图标的世界屏幕角半径（0.25 / 6 rad ≈ 2.4°）对齐，看着像准星压在图标上。
     */
    private static final float FOCUS_CONE_DEGREES = 2.5f;

    private MarkerHud() {
    }

    static void renderMinimap(GuiGraphics graphics, Minecraft minecraft,
                              int x, int y, int size, int bpp,
                              int centerX, int centerZ, ResourceLocation dimension,
                              float partialTick) {
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        List<MarkerDto> markers = MarkerClient.visibleMarkers(dimension);
        for (MarkerDto marker : markers) {
            Vec3 pos = MapHooks.INSTANCE.resolveMarker(level, marker, partialTick);
            if (pos == null) {
                continue;
            }
            int px = x + size / 2 + (int) Math.round((pos.x - centerX) / bpp);
            int py = y + size / 2 + (int) Math.round((pos.z - centerZ) / bpp);
            boolean outside = px < x || px >= x + size || py < y || py >= y + size;
            if (outside) {
                int edgeX = Mth.clamp(px, x + 2, x + size - 3);
                int edgeY = Mth.clamp(py, y + 2, y + size - 3);
                graphics.fill(edgeX - 1, edgeY - 1, edgeX + 2, edgeY + 2, 0xFFFFC040);
                continue;
            }
            float alpha = alphaOf(marker);
            if (marker.type().temporaryWaypoint()) {
                drawTriangle(graphics, px, py, 4, withAlpha(0xFFFFFFFF, alpha));
            } else {
                drawIcon(graphics, marker, px, py, 0.5f, alpha);
            }
        }
    }

    /**
     * 全屏地图：临时地点标记画三角形；永久地点标记按编辑屏选的「名字 / 图标」二选一当位置标志；
     * 其它类型画自己的图标/头像。
     */
    static void renderFullmap(GuiGraphics graphics, Minecraft minecraft,
                              int left, int top, int viewWidth, int viewHeight, double bpp,
                              double minWorldX, double minWorldZ, ResourceLocation dimension,
                              float partialTick) {
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        // 画布坐标是帧缓冲像素（pose 已被缩放 1/guiScale）：标记位置用画布像素，
        // 但文字/图标要保持 GUI 尺寸，所以每个标记在世界坐标处把 pose 乘回 guiScale。
        float guiScale = (float) minecraft.getWindow().getGuiScale();
        for (MarkerDto marker : MarkerClient.visibleMarkers(dimension)) {
            Vec3 pos = MapHooks.INSTANCE.resolveMarker(level, marker, partialTick);
            if (pos == null) {
                continue;
            }
            int px = left + MapProjection.pixel(pos.x, minWorldX, bpp);
            int py = top + MapProjection.pixel(pos.z, minWorldZ, bpp);
            if (px < left || px >= left + viewWidth || py < top || py >= top + viewHeight) {
                continue;
            }
            float alpha = alphaOf(marker);
            graphics.pose().pushPose();
            graphics.pose().translate(px, py, 0f);
            graphics.pose().scale(guiScale, guiScale, 1f);
            if (marker.type().temporaryWaypoint()) {
                drawTriangle(graphics, 0, 0, 5, withAlpha(0xFFFFFFFF, alpha));
            } else if (marker.type().waypoint() && MarkerData.showsName(marker)) {
                // 名字本身就是位置标志（永久标记的「名字 / 图标」二选一）
                String label = marker.data().getString(MarkerData.KEY_LABEL);
                graphics.drawString(minecraft.font, label,
                        -minecraft.font.width(label) / 2, -4,
                        withAlpha(0xFFFFFFFF, alpha), true);
            } else {
                drawIcon(graphics, marker, 0, 0, 1.0f, alpha);
            }
            graphics.pose().popPose();
        }
    }

    /**
     * 世界内标记的屏幕指示：视野内投影到实际方向，视野外贴屏幕边缘。
     * 临时标记画小三角形，其余画图标；名称/距离只在准星聚焦（{@link #FOCUS_CONE_DEGREES}）时显示，
     * 临时标记没有名称，聚焦也只显示距离。
     */
    static void renderWorldIndicators(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.options.hideGui) {
            return;
        }
        ResourceLocation dimension = minecraft.level.dimension().location();
        float fov = currentFov(minecraft);
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        // 用相机而不是玩家 tick 朝向：包含视角晃动/第三人称，标记才会「钉」在空间里
        var camera = minecraft.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        float camYaw = camera.getYRot();
        float camPitch = camera.getXRot();
        double aspect = width / (double) height;
        double halfTanV = Math.tan(Math.toRadians(fov / 2.0));
        double hFov = 2.0 * Math.atan(halfTanV * aspect);
        double hFovDegrees = Math.toDegrees(hFov);
        double halfTanH = Math.tan(hFov / 2.0);
        ClientLevel level = minecraft.level;

        for (MarkerDto marker : MarkerClient.visibleMarkers(dimension)) {
            Vec3 pos = MapHooks.INSTANCE.resolveMarker(level, marker, partialTick);
            if (pos == null) {
                continue;
            }
            double dx = pos.x - camPos.x;
            double dy = pos.y - camPos.y;
            double dz = pos.z - camPos.z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance < 1.0 || distance > MAX_SCREEN_DISTANCE) {
                continue; // 太远：只在罗盘上显示
            }
            // 近大远小
            float scale = (float) Mth.clamp(24.0 / distance, 0.3, 1.0);
            float alpha = alphaOf(marker);
            float markerYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float markerPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            float deltaYaw = Mth.wrapDegrees(markerYaw - camYaw);
            float deltaPitch = markerPitch - camPitch;

            boolean onScreen = Math.abs(deltaYaw) < hFovDegrees / 2.0
                    && Math.abs(deltaPitch) < fov / 2.0f;
            int px;
            int py;
            if (onScreen) {
                px = (int) Math.round(width / 2.0
                        + Math.tan(Math.toRadians(deltaYaw)) / halfTanH * width / 2.0);
                py = (int) Math.round(height / 2.0
                        + Math.tan(Math.toRadians(deltaPitch)) / halfTanV * height / 2.0);
            } else {
                // 视野外：贴屏幕左右边缘，垂直位置跟着俯仰角走
                px = deltaYaw < 0 ? 18 : width - 18;
                py = Mth.clamp((int) Math.round(height / 2.0 - deltaPitch * 6.0), 24, height - 24);
            }
            if (marker.type().temporaryWaypoint()) {
                drawTriangle(graphics, px, py, 4, withAlpha(0xFFFFFFFF, alpha));
            } else {
                drawIcon(graphics, marker, px, py, scale, alpha);
            }
            // 名称 / 距离只在「聚焦」（准星附近）时显示，临时标记没有名字
            if (!onScreen || Math.abs(deltaYaw) >= FOCUS_CONE_DEGREES
                    || Math.abs(deltaPitch) >= FOCUS_CONE_DEGREES) {
                continue;
            }
            String label = marker.type().temporaryWaypoint()
                    ? "" : marker.data().getString(MarkerData.KEY_LABEL);
            String distanceText = Math.round(distance) + "m";
            String text = label.isBlank() ? distanceText : label + "  " + distanceText;
            int color = withAlpha(0xFFFFFFFF, alpha);
            graphics.drawString(minecraft.font, text,
                    px - minecraft.font.width(text) / 2,
                    py + (int) (10 * scale) + 2, color, true);
        }
    }

    /**
     * 罗盘上的标记方位；超出条窗口时贴在条的两端（箭头位置）。
     */
    static void renderCompass(GuiGraphics graphics, Font font, int centerX, int barWidth,
                              int left, int right, int top, int bottom,
                              float bearing, float fov, DoubleUnaryOperator project,
                              float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        ResourceLocation dimension = minecraft.level.dimension().location();
        ClientLevel level = minecraft.level;
        double eyeX = minecraft.player.getX();
        double eyeZ = minecraft.player.getZ();
        int barY = top + (bottom - top) / 2;
        float markerScale = (float) CompassConfig.markerScale();
        int markerHalf = Math.max(2, Math.round(4.0f * markerScale));
        List<CompassOut> leftOut = new ArrayList<>();
        List<CompassOut> rightOut = new ArrayList<>();

        for (MarkerDto marker : MarkerClient.visibleMarkers(dimension)) {
            if (!MarkerData.showsInCompass(marker)) {
                continue;
            }
            Vec3 pos = MapHooks.INSTANCE.resolveMarker(level, marker, partialTick);
            if (pos == null) {
                continue;
            }
            double dx = pos.x - eyeX;
            double dz = pos.z - eyeZ;
            if (dx * dx + dz * dz < 1.0) {
                continue;
            }
            // 世界 yaw → 罗盘 bearing（N=0）。不 +180 的话标记会在罗盘上反向 180°。
            float markerBearing = Mth.positiveModulo(
                    (float) Math.toDegrees(Math.atan2(-dx, dz)) + 180.0f, 360.0f);
            float delta = Mth.wrapDegrees(markerBearing - bearing);
            if (Math.abs(delta) >= fov / 2.0f) {
                // 超出条窗口：先收集，最后在两端按顺序排开
                (delta < 0 ? leftOut : rightOut).add(new CompassOut(marker, delta));
                continue;
            }
            int x = centerX + (int) Math.round(project.applyAsDouble(delta));
            x = Mth.clamp(x, left + 2, right - 2);
            int y = barY - markerHalf;
            float alpha = alphaOf(marker);
            if (marker.type().temporaryWaypoint()) {
                drawTriangle(graphics, x, barY, markerHalf, withAlpha(0xFFFFFFFF, alpha));
            } else {
                drawIcon(graphics, marker, x, y, 0.4f * markerScale, alpha);
            }
        }
        drawOverflow(graphics, leftOut, true, left, right, barY, markerHalf, markerScale);
        drawOverflow(graphics, rightOut, false, left, right, barY, markerHalf, markerScale);
    }

    private record CompassOut(MarkerDto marker, float delta) {
    }

    /**
     * 超出罗盘窗口的标记：贴边按「离窗口由近到远」依次往下排，
     * 地点标记画横向三角（指向溢出方向），其它标记画小图标；越往后越淡。
     */
    private static void drawOverflow(GuiGraphics graphics, List<CompassOut> out, boolean left,
                                     int leftX, int rightX, int barY,
                                     int markerHalf, float markerScale) {
        out.sort((a, b) -> Float.compare(Math.abs(a.delta()), Math.abs(b.delta())));
        // 横向依次排开：符号宽 2*half，步长 = 半宽 → 三个正好占两个原宽
        int step = Math.max(3, markerHalf);
        for (int i = 0; i < out.size(); i++) {
            CompassOut entry = out.get(i);
            int x = left ? leftX + 5 + i * step : rightX - 5 - i * step;
            float fade = Math.max(0.35f, 1.0f - i * 0.14f);
            float alpha = alphaOf(entry.marker()) * fade;
            if (entry.marker().type().temporaryWaypoint()) {
                drawSideTriangle(graphics, x, barY, markerHalf, left ? -1 : 1,
                        withAlpha(0xFFFFC040, alpha));
            } else {
                drawIcon(graphics, entry.marker(), x, barY, 0.35f * markerScale, alpha);
            }
        }
    }

    /**
     * 悬停提示：物品标记给原版物品 tooltip（跟随「高级提示框」设置），其余给类型/坐标。
     */
    static void drawTooltip(GuiGraphics graphics, Minecraft minecraft, MarkerDto marker,
                            int mouseX, int mouseY) {
        if (MarkerData.hasItem(marker)) {
            ItemStack stack = MarkerClient.itemStack(marker);
            if (!stack.isEmpty()) {
                graphics.renderTooltip(minecraft.font, stack, mouseX, mouseY);
                return;
            }
        }
        List<Component> lines = new ArrayList<>();
        String label = marker.data().getString(MarkerData.KEY_LABEL);
        if (!label.isBlank()) {
            lines.add(Component.literal(label));
        }
        lines.add(Component.translatable("catoxidesbattlerebuild.map.marker.type."
                + marker.type().name().toLowerCase(Locale.ROOT)));
        lines.add(Component.literal((int) Math.floor(marker.x()) + ", "
                + (int) Math.floor(marker.y()) + ", " + (int) Math.floor(marker.z())));
        graphics.renderComponentTooltip(minecraft.font, lines, mouseX, mouseY);
    }

    /**
     * 向下等腰直角三角形，尖端在 {@code (cx, tipY)}；宽 {@code 2*half}、高 {@code half}。
     * <p>和世界里的三角形同一形状：尖端就是标记点，越往上越宽。
     */
    static void drawTriangle(GuiGraphics graphics, int cx, int tipY, int half, int argb) {
        for (int row = 0; row < half; row++) {
            int w = half - row;
            int y0 = tipY - half + row;
            graphics.fill(cx - w, y0, cx + w, y0 + 1, argb);
        }
    }

    /** 横向等腰直角三角形：尖端在 {@code (tipX, cy)}；{@code dir=-1} 朝左、{@code +1} 朝右。 */
    static void drawSideTriangle(GuiGraphics graphics, int tipX, int cy, int half, int dir, int argb) {
        for (int d = 0; d < half; d++) {
            int x = tipX - dir * d;
            int h = d;
            graphics.fill(x, cy - h, x + 1, cy + h + 1, argb);
        }
    }

    /**
     * 生物默认画脸（玩家读皮肤、其它生物裁贴图里头部正面那块）；只有非生物/手动换过图标的标记才用图标。
     */
    static void drawIcon(GuiGraphics graphics, MarkerDto marker, int px, int py,
                         float scale, float alpha) {
        if (alpha <= 0.02f) {
            return;
        }
        if (MarkerData.isPlayerPortrait(marker)) {
            drawFace(graphics, marker, px, py, Math.max(4, Math.round(8 * scale)), alpha);
        } else if (MarkerClient.portrait(marker) instanceof LivingEntity living) {
            MarkerFaces.Face face = MarkerFaces.of(living);
            if (face != null) {
                drawMobFace(graphics, face, px, py, Math.max(4, Math.round(8 * scale)), alpha);
            } else {
                drawPortrait(graphics, living, px, py, scale);
            }
        } else {
            ItemStack stack = MarkerClient.iconStack(marker);
            RenderSystem.enableBlend();
            RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
            var pose = graphics.pose();
            pose.pushPose();
            pose.translate(px - 8 * scale, py - 8 * scale, 0);
            pose.scale(scale, scale, 1f);
            graphics.renderItem(stack, 0, 0);
            pose.popPose();
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        }

        if (MarkerClient.isFlashing(marker.id())) {
            int half = (int) (9 * scale);
            int color = withAlpha(0xCCFFFFFF, alpha);
            graphics.fill(px - half, py - half, px + half, py - half + 1, color);
            graphics.fill(px - half, py + half - 1, px + half, py + half, color);
            graphics.fill(px - half, py - half, px - half + 1, py + half, color);
            graphics.fill(px + half - 1, py - half, px + half, py + half, color);
        }
    }

    /**
     * 生物头像：只露脸。按大约 0.35×体高估头高，把头部放大到图标框里，
     * 身体被 helper 的 scissor 裁掉，所以不会出现全身。
     */
    private static void drawPortrait(GuiGraphics graphics, LivingEntity living,
                                     int px, int py, float scale) {
        // 和物品图标一样是 16×16（scale=1 时 half=8），保证图标槽对齐
        int half = Math.max(4, Math.round(8.0f * scale));
        float height = Math.max(0.5f, living.getBbHeight());
        float headHeight = Math.max(0.25f, Math.min(0.65f, height * 0.35f));
        int pixelsPerBlock = Math.max(2, Math.round((half * 2.0f) / headHeight));
        // helper 把 vec.y = bbHeight/2 + yOffset 的点放在框中心；这里改成头部中心
        float yOffset = height * 0.5f - headHeight * 0.5f;
        InventoryScreen.renderEntityInInventoryFollowsAngle(graphics,
                px - half, py - half, px + half, py + half,
                pixelsPerBlock, yOffset, 0.0f, 0.0f, living);
    }

    /**
     * 生物脸：贴图里头部正面那块（一般 8×8），按原比例塞进 16×16 的图标框（scale=1 时），
     * 和物品图标同一尺寸、同一对齐方式。
     */
    private static void drawMobFace(GuiGraphics graphics, MarkerFaces.Face face,
                                    int px, int py, int half, float alpha) {
        int box = half * 2;
        float width = face.width();
        float height = face.height();
        int drawWidth = width >= height ? box : Math.max(2, Math.round(box * width / height));
        int drawHeight = width >= height ? Math.max(2, Math.round(box * height / width)) : box;
        int space = MarkerFaces.TEXEL_SPACE;
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
        graphics.blit(face.texture(), px - drawWidth / 2, py - drawHeight / 2, drawWidth, drawHeight,
                face.u0() * space, face.v0() * space,
                Math.max(1, Math.round(width * space)), Math.max(1, Math.round(height * space)),
                space, space);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    /** 玩家脸：64×64 皮肤里的正面 8×8（加帽子层），铺满 16×16 图标框。 */
    private static void drawFace(GuiGraphics graphics, MarkerDto marker,
                                 int px, int py, int half, float alpha) {
        if (alpha <= 0.02f) {
            return;
        }
        ResourceLocation skin = MarkerClient.playerSkin(marker);
        int size = half * 2;
        int x = px - half;
        int y = py - half;
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
        graphics.blit(skin, x, y, size, size, 8.0f, 8.0f, 8, 8, 64, 64);
        graphics.blit(skin, x, y, size, size, 40.0f, 8.0f, 8, 8, 64, 64);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    /** TTL 最后 2 秒淡出；永久标记保持 1。 */
    private static float alphaOf(MarkerDto marker) {
        long remaining = MarkerClient.remainingMillis(marker.id());
        if (remaining <= 0L) {
            return 1f;
        }
        return (float) Math.min(1.0, remaining / FADE_MILLIS);
    }

    private static int withAlpha(int argb, float alpha) {
        int a = Math.max(0, Math.min(255, (int) (((argb >>> 24) & 0xFF) * alpha)));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    /** 实体标记跟随实体当前位置；不是实体则用记录坐标。 */
    static double[] positionOf(MarkerDto marker, Minecraft minecraft) {
        if (marker.entityId() >= 0 && minecraft.level != null) {
            var entity = minecraft.level.getEntity(marker.entityId());
            if (entity != null) {
                return new double[]{entity.getX(),
                        entity.getY() + entity.getBbHeight() * 0.5,
                        entity.getZ()};
            }
        }
        return new double[]{marker.x(), marker.y(), marker.z()};
    }

    private static float currentFov(Minecraft minecraft) {
        Integer fov = minecraft.options.fov().get();
        return (fov == null || fov <= 0 || fov > 200) ? 75f : (float) fov;
    }
}
