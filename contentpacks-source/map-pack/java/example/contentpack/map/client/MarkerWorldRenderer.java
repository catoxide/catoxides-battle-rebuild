package example.contentpack.map.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
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

/**
 * 标记在世界空间渲染。
 *
 * <p>地点标记（PING / STICKY）画成<b>向下的白色等腰直角三角形</b>，上边写直线距离；
 * 用它代替物品图标是因为图标远了看不清、近了又太大，而且三角形能明确指向脚下那个点。
 * 三角形用无深度测试的 RenderType，永远盖在方块上面（但仍在 HUD 之下）。
 *
 * <p>实体标记画的是 {@link MarkerClient#portrait} 造出的临时生物模型，物品标记画物品本体。
 * 被标记的实体由服务端续原版 Glowing 效果（光灵箭描边），持续到标记结束。
 *
 * <p>固定在世界坐标，视角/移动天然正确，不依赖屏幕投影；超出
 * {@value #MAX_RENDER_DISTANCE} 格不画（只留罗盘）。
 *
 * <p><b>屏幕恒定大小</b>：世界尺寸按距离成比例放大（{@code scale ∝ distance}），
 * 所以远近看起来一样大，不会走到跟前变成一大块。{@value #REFERENCE_DISTANCE}
 * 格处的大小 = 原来的大小；想整体调大/调小改 {@link #BASE_SCALE} 或参考距离。
 *
 * <p><b>文字/头像必须在同一个 billboard 空间里偏移</b>：先转到面向相机，再沿本地 +Y 抬高；
 * 否则从侧面看，文字会偏离三角形、不再垂直。
 */
public final class MarkerWorldRenderer {

    private static final double MAX_RENDER_DISTANCE = 256.0;
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
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        var markers = MarkerClient.visibleMarkers(minecraft.level.dimension().location());
        if (markers.isEmpty()) {
            return;
        }

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        PoseStack pose = event.getPoseStack();
        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();
        MultiBufferSource.BufferSource buffer = minecraft.renderBuffers().bufferSource();
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

            if (marker.type().waypoint()) {
                // 向下三角形：尖端钉在标记点，宽度 1.5 个字符
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
                // 物品模型（有完整 ItemStack 时就是物品本体）：固定世界坐标，面向相机
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

            // 名字 + 距离：物品标记没起名字时用物品名
            String label = marker.data().getString(MarkerData.KEY_LABEL);
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
        buffer.endBatch(TRIANGLE);
    }

    /**
     * 白色向下等腰直角三角形：尖端（直角顶点）钉在标记点，
     * 上边宽 {@code 2 * TRIANGLE_HALF_WIDTH} 个字体单位。
     * <p>RenderType 关掉深度测试，所以永远盖在方块之上。
     */
    private static void drawTriangle(PoseStack pose, MultiBufferSource.BufferSource buffer,
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
