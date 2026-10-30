package example.contentpack.map.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import example.contentpack.map.MapHooks;
import example.contentpack.map.marker.MarkerData;
import example.contentpack.map.marker.MarkerDto;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 标记在世界空间渲染。
 *
 * <p><b>渲染阶段是硬约束</b>：必须挂 {@code AFTER_LEVEL}（{@code renderLevel} 完全结束之后、
 * GUI 之前，画进主画布）。半透明方块在 {@code AFTER_ENTITIES} 之后才画，之后还有 tripwire、
 * 粒子、云、天气，Fabulous 画质下还要整层合成一次；{@code AFTER_TRANSLUCENT_BLOCKS} 又是在
 * 区块层渲染中途触发的 —— 挂早了都会被后画的东西盖住，表现就是「方块挡住了标记」。
 * 这个阶段 entry 时相机旋转已经从 modelview 栈里弹掉了，要自己补回去（见 {@link #render}）。
 *
 * <p><b>标记永远盖在方块上</b>：这个阶段先清一次深度缓冲（原版第一人称手也这么做），
 * 所以三角形、物品图标、生物头像都不会被不透明或半透明方块挡住，标记之间仍按深度排序。
 *
 * <p><b>临时地点标记（PING）</b>画成<b>向下的白色等腰直角三角形</b>，尖端钉在标记点上。
 *
 * <p><b>永久地点标记（STICKY）</b>画绑定的图标本体当位置标志（图标可能是物品、生物头像或玩家头），
 * 不再用三角形。实体标记画 {@link MarkerClient#portrait} 造出的临时生物模型，物品标记画物品本体。
 * 被标记的实体由服务端续原版 Glowing 效果（光灵箭描边），持续到标记结束。
 *
 * <p><b>名称 / 距离只在聚焦时显示</b>：准星（相机视线）落在标记的
 * {@value #FOCUS_CONE_DEGREES}° 锥内才画；临时标记没有名称，聚焦也只显示距离。
 *
 * <p>固定在世界坐标，视角/移动天然正确，不依赖屏幕投影；超出
 * {@value #MAX_RENDER_DISTANCE} 格不画（只留罗盘）。
 *
 * <p><b>屏幕恒定大小</b>：世界尺寸按距离成比例放大（{@code scale ∝ distance}），
 * 所以远近看起来一样大，不会走到跟前变成一大块。{@value #REFERENCE_DISTANCE}
 * 格处的大小 = 原来的大小；想整体调大/调小改 {@link #BASE_SCALE} 或参考距离。
 *
 * <p><b>文字/头像必须在同一个 billboard 空间里偏移</b>：先转到面向相机，再沿本地 +Y 抬高；
 * 否则从侧面看，文字会偏离图标、不再垂直。
 */
public final class MarkerWorldRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double MAX_RENDER_DISTANCE = 256.0;
    /** 「聚焦」锥半角（度）：相机视线离标记这么近才显示名称/距离。 */
    private static final float FOCUS_CONE_DEGREES = 2.5f;
    /** 参考距离处的大小 = 边长一半（面积约 1/4），其余距离按比例补偿。 */
    private static final double REFERENCE_DISTANCE = 6.0;
    private static final float BASE_SCALE = 0.25f;
    private static final float BASE_TEXT_SCALE = 0.015f;
    private static final int FULL_BRIGHT = 0xF000F0;

    /** 三角形半宽（字体单位）：宽 9 = 1.5 个字符；高 = 半宽（等腰直角，直角朝下）。 */
    private static final float TRIANGLE_HALF_WIDTH = 4.5f;
    private static final float TRIANGLE_TEXT_GAP = 2.0f;
    private static final float FONT_LINE_HEIGHT = 9.0f;

    /** 纯色三角形，无深度测试、不写深度：永远在方块之上。 */
    private static final RenderType TRIANGLE = RenderType.create(
            "map_pack_marker_triangle",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.TRIANGLES,
            1536,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));

    private MarkerWorldRenderer() {
    }

    public static void render(RenderLevelStageEvent event) {
        // 最后一个阶段：它之后只有手和 GUI。半透明方块、tripwire、粒子、云、天气、
        // Fabulous 的整层合成都已经结束，之后再没有任何东西能盖住标记。
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        List<MarkerDto> markers = MarkerClient.visibleMarkers(minecraft.level.dimension().location());
        if (markers.isEmpty()) {
            return;
        }

        // AFTER_LEVEL 在 LevelRenderer 弹掉相机旋转之后，modelview 里没有视图旋转了，这里补回去。
        // 顶点仍然用相机相对坐标（和实体渲染一致），event 给的 PoseStack 根矩阵也是单位阵。
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.mul(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        // 标记是位置指示：清掉深度后图标/头像也不会被方块挡住（原版第一人称手用同样的办法）。
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        Recorder buffer = new Recorder(minecraft.renderBuffers().bufferSource());
        try {
            renderMarkers(event, minecraft, markers, buffer);
        } finally {
            // 这个阶段之后没有原版的全局 endBatch 兜底了，而且 BufferSource 只会自动刷
            // 「最后一个共享类型」+ 固定缓冲，所以用到的类型要逐个刷掉（否则图标会被漏掉）。
            buffer.flush();
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static void renderMarkers(RenderLevelStageEvent event, Minecraft minecraft,
                                      List<MarkerDto> markers, MultiBufferSource buffer) {
        PoseStack pose = event.getPoseStack();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();
        Font font = minecraft.font;
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        float time = (System.currentTimeMillis() % 100_000L) / 1000.0f;

        for (MarkerDto marker : markers) {
            Vec3 pos = MapHooks.INSTANCE.resolveMarker(minecraft.level, marker, partialTick);
            if (pos == null) {
                continue;
            }
            double distance = pos.distanceTo(cameraPos);
            if (distance > MAX_RENDER_DISTANCE) {
                continue;
            }
            // 距离补偿：世界尺寸 ∝ 距离 → 屏幕上像素大小恒定
            float factor = (float) (Math.max(distance, 0.5) / REFERENCE_DISTANCE);
            float pulse = MarkerClient.isFlashing(marker.id())
                    ? 1.0f + 0.15f * Mth.sin(time * 8.0f) : 1.0f;
            float scale = BASE_SCALE * factor * pulse;
            float glyphScale = BASE_TEXT_SCALE * factor;
            float textY;

            if (marker.type().temporaryWaypoint()) {
                // 临时标记：向下三角形，尖端钉在标记点，宽度 1.5 个字符
                drawTriangle(pose, buffer, camera, pos, cameraPos, glyphScale * pulse);
                textY = (TRIANGLE_HALF_WIDTH + TRIANGLE_TEXT_GAP + FONT_LINE_HEIGHT) * glyphScale;
            } else if (MarkerClient.portrait(marker) instanceof LivingEntity living) {
                // 生物头像：完整模型摆到标记点上方，固定正脸（yaw 由 MarkerClient 设好）
                float height = Math.max(0.5f, living.getBbHeight());
                float gap = 0.2f * factor;
                pose.pushPose();
                pose.translate(pos.x - cameraPos.x, pos.y - cameraPos.y, pos.z - cameraPos.z);
                // 先转向相机，再在屏幕平面内向上抬：文字/头像始终在正上方
                pose.mulPose(camera.rotation());
                pose.translate(0.0f, gap, 0.0f);
                pose.scale(scale, scale, scale);
                dispatcher.setRenderShadow(false);
                dispatcher.render(living, 0.0, 0.0, 0.0, camera.getYRot(), partialTick,
                        pose, buffer, FULL_BRIGHT);
                dispatcher.setRenderShadow(true);
                pose.popPose();
                textY = gap + height * scale + 0.15f * factor;
            } else {
                // 永久地点标记 / 物品：图标本体当位置标志（有完整 ItemStack 时就是物品本体），面向相机
                pose.pushPose();
                pose.translate(pos.x - cameraPos.x, pos.y - cameraPos.y, pos.z - cameraPos.z);
                pose.mulPose(camera.rotation());
                pose.scale(scale, scale, scale);
                minecraft.getItemRenderer().renderStatic(MarkerClient.iconStack(marker),
                        ItemDisplayContext.GROUND, FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                        pose, buffer, minecraft.level, 0);
                pose.popPose();
                textY = 0.32f * factor;
            }

            // 名称 + 距离只在准星聚焦时显示；临时标记没有名称，聚焦也只给距离
            if (!isFocused(camera, cameraPos, pos)) {
                continue;
            }
            String label = marker.type().temporaryWaypoint()
                    ? "" : marker.data().getString(MarkerData.KEY_LABEL);
            if (label.isBlank() && MarkerData.hasItem(marker)) {
                ItemStack stack = MarkerClient.iconStack(marker);
                if (!stack.isEmpty()) {
                    label = stack.getHoverName().getString();
                }
            }
            String distanceText = Math.round(distance) + "m";
            String text = label.isBlank() ? distanceText : label + "  " + distanceText;
            pose.pushPose();
            pose.translate(pos.x - cameraPos.x, pos.y - cameraPos.y, pos.z - cameraPos.z);
            pose.mulPose(camera.rotation());
            pose.translate(0.0f, textY, 0.0f);
            // 文字跟着距离补偿，否则远处只剩一个图标、名字缩成看不清。
            // X 必须为正：和原版名牌一致（负 X 会翻转四边形绕序，被 SEE_THROUGH 的背面剔除吃掉）
            pose.scale(glyphScale, -glyphScale, glyphScale);
            Matrix4f matrix = pose.last().pose();
            font.drawInBatch(text, -font.width(text) / 2.0f, 0.0f, 0xFFFFFFFF,
                    false, matrix, buffer, Font.DisplayMode.SEE_THROUGH, 0, FULL_BRIGHT);
            pose.popPose();
        }
        logHeartbeat(minecraft, markers, cameraPos);
    }

    /** 临时诊断（修好遮挡问题后删掉）：每 5 秒报一次本帧实际参与的世界标记，最多 12 条。 */
    private static long lastLogMillis;
    private static int logCount;
    private static final long LOG_INTERVAL_MILLIS = 5_000L;
    private static final int LOG_LIMIT = 12;

    private static void logHeartbeat(Minecraft minecraft, List<MarkerDto> markers, Vec3 cameraPos) {
        long now = System.currentTimeMillis();
        if (logCount >= LOG_LIMIT || now - lastLogMillis < LOG_INTERVAL_MILLIS) {
            return;
        }
        lastLogMillis = now;
        logCount++;
        StringBuilder line = new StringBuilder("[MapPack] world markers n=").append(markers.size());
        int shown = 0;
        for (MarkerDto marker : markers) {
            if (shown++ >= 6) {
                break;
            }
            Vec3 pos = MapHooks.INSTANCE.resolveMarker(minecraft.level, marker,
                    minecraft.getTimer().getGameTimeDeltaPartialTick(false));
            line.append(" | ").append(marker.type()).append(' ')
                    .append(marker.data().getString(MarkerData.KEY_LABEL))
                    .append(" icon=").append(MarkerClient.iconStack(marker).getItem())
                    .append(" dist=")
                    .append(pos == null ? "null" : Math.round(pos.distanceTo(cameraPos)));
        }
        LOGGER.info(line.toString());
    }

    /**
     * 记录用到的 RenderType，最后逐个 {@code endBatch}。
     *
     * <p>{@link MultiBufferSource.BufferSource} 在切换共享类型时只会刷掉「上一个」，
     * {@code endBatch()} 也只刷最后一个共享类型 + 固定缓冲 —— 一个标记同时用到三角形、物品模型
     * （可能不止一个 RenderType，比如 3D 方块图标）和文字时，光靠它必然漏刷。
     */
    private static final class Recorder implements MultiBufferSource {

        private final MultiBufferSource.BufferSource delegate;
        private final Set<RenderType> types = new LinkedHashSet<>();

        Recorder(MultiBufferSource.BufferSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            types.add(type);
            return delegate.getBuffer(type);
        }

        void flush() {
            types.forEach(delegate::endBatch);
            types.clear();
            delegate.endBatch();
        }
    }

    /** 准星是否落在标记上：相机视线与「相机 → 标记」的夹角小于 {@value #FOCUS_CONE_DEGREES}°。 */
    private static boolean isFocused(Camera camera, Vec3 cameraPos, Vec3 pos) {
        Vec3 direction = pos.subtract(cameraPos);
        double length = direction.length();
        if (length < 1.0e-6) {
            return true;
        }
        var look = camera.getLookVector();
        double dot = look.x * direction.x + look.y * direction.y + look.z * direction.z;
        return dot / length >= Math.cos(Math.toRadians(FOCUS_CONE_DEGREES));
    }

    /**
     * 白色向下等腰直角三角形：尖端（直角顶点）钉在标记点，
     * 上边宽 {@code 2 * TRIANGLE_HALF_WIDTH} 个字体单位。
     * <p>RenderType 关掉深度测试，所以永远盖在方块之上。
     */
    private static void drawTriangle(PoseStack pose, MultiBufferSource buffer,
                                     Camera camera, Vec3 pos, Vec3 cameraPos, float scale) {
        pose.pushPose();
        pose.translate(pos.x - cameraPos.x, pos.y - cameraPos.y, pos.z - cameraPos.z);
        pose.mulPose(camera.rotation());
        pose.scale(scale, scale, scale);
        Matrix4f matrix = pose.last().pose();
        VertexConsumer vertices = buffer.getBuffer(TRIANGLE);
        vertex(vertices, matrix, 0.0f, 0.0f);
        vertex(vertices, matrix, -TRIANGLE_HALF_WIDTH, TRIANGLE_HALF_WIDTH);
        vertex(vertices, matrix, TRIANGLE_HALF_WIDTH, TRIANGLE_HALF_WIDTH);
        pose.popPose();
    }

    private static void vertex(VertexConsumer vertices, Matrix4f matrix, float x, float y) {
        vertices.addVertex(matrix, x, y, 0.0f).setColor(1.0f, 1.0f, 1.0f, 1.0f);
    }
}
