package example.contentpack.mapv2.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import example.contentpack.mapv2.MapHooks;
import example.contentpack.mapv2.marker.MarkerData;
import example.contentpack.mapv2.marker.MarkerDto;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector4f;
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
 * 这个阶段 entry 时相机旋转已经从 modelview 栈里弹掉了，所以投影到屏幕位置时直接用
 * {@code event.getProjectionMatrix() × event.getModelViewMatrix()} 在 Java 侧算（见 {@link #render}）。
 *
 * <p><b>标记永远盖在方块上</b>：这个阶段先清一次深度缓冲（原版第一人称手也这么做），
 * 所以三角形、物品图标、生物头像都不会被不透明或半透明方块挡住，标记之间仍按深度排序。
 *
 * <p><b>临时地点标记（PING）</b>画成<b>向下的白色等腰直角三角形</b>，尖端钉在标记点上。
 *
 * <p><b>永久地点标记（STICKY）</b>画绑定的图标本体当位置标志（图标可能是物品、生物头像或玩家头），
 * 不再用三角形。实体标记画 {@link MarkerClient#portrait} 造出的临时生物模型；物品/方块标记走原版
 * GUI 渲染路径（{@link ItemDisplayContext#GUI}）：方块是快捷栏里那种等轴测三面图标，平面物品就是
 * 它的贴图，大小是其它标记的 {@link #FLAT_ICON_SCALE} 倍。
 * 被标记的实体由服务端续原版 Glowing 效果（光灵箭描边），持续到标记结束。
 *
 * <p><b>名称 / 距离只在聚焦时显示</b>：准星（相机视线）落在标记的
 * {@value #FOCUS_CONE_DEGREES}° 锥内才画；临时标记没有名称，聚焦也只显示距离。
 *
 * <p><b>屏幕空间叠层</b>：标记的世界坐标先按当前透视投影算出屏幕位置，再用<b>正交投影</b>
 * 画成平面（屏幕半高 = 1 单位）。这样图标/文字的大小只由常量决定 —— 不随距离、FOV、镜头转动
 * 变化，也不会有透视把图标拉歪（用户要的「拍扁的贴图」）。投影在画之前换成
 * {@code ortho(-aspect, aspect, 1, -1)}（y 向下，和 GUI/字体坐标系一致），画完恢复。
 *
 * <p><b>物品图标</b>仍走原版 GUI 渲染路径（{@link ItemDisplayContext#GUI}）：方块是快捷栏里
 * 那种等轴测三面图标，平面物品就是它的贴图；因为投影也是 y 向下的，所以和 GUI 完全同形。
 * 生物头像画完整 3D 模型，需要额外把 y 翻回来抵消投影（否则会倒立且绕序/剔除反掉）。
 *
 * <p>超出 {@value #MAX_RENDER_DISTANCE} 格不画（只留罗盘）；
 * 尺寸以 {@value #REFERENCE_DISTANCE} 格、参考 FOV 下的原值为基准。
 */
public final class MarkerWorldRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double MAX_RENDER_DISTANCE = 256.0;
    /** 「聚焦」锥半角（度）：相机视线离标记这么近才显示名称/距离。 */
    private static final float FOCUS_CONE_DEGREES = 2.5f;
    /** 尺寸基准：{@value #REFERENCE_DISTANCE} 格、参考 FOV 处看起来的大小。 */
    private static final double REFERENCE_DISTANCE = 6.0;
    /** 参考视场角（度）：原版默认 70°。只影响「基准大小」的换算，运行时尺寸恒为屏幕空间。 */
    private static final float REFERENCE_FOV_DEGREES = 70.0f;
    /**
     * 1 世界单位在屏幕空间（半屏高 = 1）里占多少。
     * 由基准距离/基准 FOV 下的透视投影换算而来，所有尺寸常量都乘它。
     */
    private static final float SCREEN_PER_WORLD = (float) (1.0
            / (REFERENCE_DISTANCE * Math.tan(Math.toRadians(REFERENCE_FOV_DEGREES / 2.0))));
    private static final float BASE_SCALE = 0.25f;
    /** 物品/方块贴图相对 {@link #BASE_SCALE} 的倍率：实测 100% 偏大，缩到 75%。 */
    private static final float FLAT_ICON_SCALE = 0.75f;
    private static final float BASE_TEXT_SCALE = 0.015f;
    private static final int FULL_BRIGHT = 0xF000F0;
    /**
     * 屏幕空间里的 z：只用于标记之间的前后排序。取小值是为了避开雾（按视距算的）
     * 和深度精度问题 —— 反正地形深度已经清掉，不需要真实距离。
     */
    private static final float SCREEN_DEPTH = 2.0f;

    /** 三角形半宽（字体单位）：宽 9 = 1.5 个字符；高 = 半宽（等腰直角，直角朝下）。 */
    private static final float TRIANGLE_HALF_WIDTH = 4.5f;
    private static final float TRIANGLE_TEXT_GAP = 2.0f;
    private static final float FONT_LINE_HEIGHT = 9.0f;

    /**
     * 物品/方块的 GUI display 变换自带 0.625 缩放。这里补回去，让调用点传入的 scale
     * 就是最终可见尺寸（方块的等轴测外观、物品的平面外观都由原版 GUI 变换给出）。
     */
    private static final float ITEM_GUI_SCALE = 0.625f;

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

        // 世界坐标 → 裁剪空间：屏幕上放在哪，仍由真实透视投影决定（标记要钉在世界点上）。
        Matrix4f worldToClip = new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix());
        float aspect = (float) minecraft.getWindow().getWidth()
                / Math.max(1, minecraft.getWindow().getHeight());

        // 画的时候换成正交投影 + 单位 modelview：屏幕半高 = 1 单位，y 向下（和 GUI/字体一致）。
        // 这样图标/文字是真正的平面，不随距离、FOV、镜头转动变形或缩放。
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.backupProjectionMatrix();
        // 排序只能用 z：文字批次上传时按排序重排四边形，DISTANCE_TO_ORIGIN 会把 x/y 也算进去 ——
        // 屏幕左半 x 为负，阴影（比字形少 +0.03 z、x/y 各 +1）反而更近原点，被排到字形后面画上来。
        RenderSystem.setProjectionMatrix(
                new Matrix4f().setOrtho(-aspect, aspect, 1.0f, -1.0f, -1000.0f, 1000.0f),
                VertexSorting.ORTHOGRAPHIC_Z);
        // 标记是位置指示：清掉深度后图标/头像也不会被方块挡住（原版第一人称手用同样的办法）。
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        Recorder buffer = new Recorder(minecraft.renderBuffers().bufferSource());
        try {
            renderMarkers(event, minecraft, markers, buffer, worldToClip, aspect);
        } finally {
            // 这个阶段之后没有原版的全局 endBatch 兜底了，而且 BufferSource 只会自动刷
            // 「最后一个共享类型」+ 固定缓冲，所以用到的类型要逐个刷掉（否则图标会被漏掉）。
            // 必须在正交投影还生效时刷，换成屏幕坐标写入的顶点才能用对矩阵。
            buffer.flush();
            RenderSystem.restoreProjectionMatrix();
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static void renderMarkers(RenderLevelStageEvent event, Minecraft minecraft,
                                      List<MarkerDto> markers, MultiBufferSource buffer,
                                      Matrix4f worldToClip, float aspect) {
        PoseStack pose = new PoseStack();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();
        Font font = minecraft.font;
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        float time = (System.currentTimeMillis() % 100_000L) / 1000.0f;

        float glyphScale = BASE_TEXT_SCALE * SCREEN_PER_WORLD;
        float iconScale = BASE_SCALE * FLAT_ICON_SCALE * SCREEN_PER_WORLD;

        for (MarkerDto marker : markers) {
            Vec3 pos = MapHooks.INSTANCE.resolveMarker(minecraft.level, marker, partialTick);
            if (pos == null) {
                continue;
            }
            double distance = pos.distanceTo(cameraPos);
            if (distance > MAX_RENDER_DISTANCE) {
                continue;
            }
            // 屏幕位置：用真实透视投影算出 NDC，再换算到「半屏高 = 1、y 向下」的正交空间。
            Vec3 rel = pos.subtract(cameraPos);
            Vector4f clip = worldToClip.transform(
                    new Vector4f((float) rel.x, (float) rel.y, (float) rel.z, 1.0f));
            if (clip.w <= 0.05f) {
                continue; // 相机后方或贴脸
            }
            float sx = clip.x / clip.w * aspect;
            float sy = -clip.y / clip.w;
            if (Math.abs(sx) > aspect + 0.5f || Math.abs(sy) > 1.5f) {
                continue; // 屏幕外
            }
            float sz = clip.z / clip.w * SCREEN_DEPTH;
            float pulse = MarkerClient.isFlashing(marker.id())
                    ? 1.0f + 0.15f * Mth.sin(time * 8.0f) : 1.0f;
            float textUp;

            if (marker.type().temporaryWaypoint()) {
                // 临时标记：向下三角形，尖端钉在标记点，宽度 1.5 个字符
                drawTriangle(pose, buffer, sx, sy, glyphScale * pulse);
                textUp = (TRIANGLE_HALF_WIDTH + TRIANGLE_TEXT_GAP + FONT_LINE_HEIGHT) * glyphScale;
            } else if (MarkerClient.portrait(marker) instanceof LivingEntity living) {
                // 生物头像：完整模型摆到标记点上方，固定正脸（yaw 由 MarkerClient 设好）
                float height = Math.max(0.5f, living.getBbHeight());
                float gap = 0.2f * SCREEN_PER_WORLD;
                float portraitScale = BASE_SCALE * SCREEN_PER_WORLD;
                pose.pushPose();
                pose.translate(sx, sy - gap, sz);
                // 投影是 y 向下的；实体模型是 y 向上，翻回来才正立，绕序/剔除也才和原来一致
                pose.scale(1.0f, -1.0f, 1.0f);
                pose.scale(portraitScale, portraitScale, portraitScale);
                dispatcher.setRenderShadow(false);
                dispatcher.render(living, 0.0, 0.0, 0.0, camera.getYRot(), partialTick,
                        pose, buffer, FULL_BRIGHT);
                dispatcher.setRenderShadow(true);
                pose.popPose();
                textUp = gap + height * portraitScale + 0.15f * SCREEN_PER_WORLD;
            } else {
                // 永久地点标记 / 物品：和快捷栏一样的 GUI 渲染（方块是等轴测三面，物品是贴图）
                drawItemIcon(pose, buffer, sx, sy, sz, iconScale * pulse,
                        minecraft, MarkerClient.iconStack(marker));
                textUp = 0.32f * SCREEN_PER_WORLD * FLAT_ICON_SCALE;
            }

            // 临时标记在任何距离都显示距离（带阴影）；永久标记保持「准星聚焦才显示名称+距离」。
            boolean temporary = marker.type().temporaryWaypoint();
            if (!temporary && !isFocused(camera, cameraPos, pos)) {
                continue;
            }
            String label = temporary
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
            pose.translate(sx, sy - textUp, sz);
            // y 向下已经和字体坐标系一致，不用再翻 Y
            pose.scale(glyphScale, glyphScale, glyphScale);
            Matrix4f matrix = pose.last().pose();
            font.drawInBatch(text, -font.width(text) / 2.0f, 0.0f, 0xFFFFFFFF,
                    true, matrix, buffer, Font.DisplayMode.SEE_THROUGH, 0, FULL_BRIGHT);
            pose.popPose();
        }
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
     * 图标：走原版 GUI（快捷栏/背包）那条渲染路径 —— 方块是等轴测的立体图标（正面/侧面/顶面），
     * 平面物品就是它的贴图。
     *
     * <p>坐标系是 y 向下的屏幕空间，但物品模型是 y 向上；{@code GuiGraphics.renderItem} 里的
     * {@code scale(16, -16, 16)} 就是干这个的，这里也要翻一次 Y，否则图标上下颠倒
     * （对称的下界之星看不出来，船、矿车一眼就露馅）。
     *
     * <p>GUI 变换本身带 {@link #ITEM_GUI_SCALE} 缩放，所以外部 scale 先补回 1/0.625。
     */
    private static void drawItemIcon(PoseStack pose, MultiBufferSource buffer,
                                     float x, float y, float z, float scale,
                                     Minecraft minecraft, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemRenderer renderer = minecraft.getItemRenderer();
        BakedModel model = renderer.getModel(stack, minecraft.level, minecraft.player, 0);
        pose.pushPose();
        pose.translate(x, y, z);
        float normalized = scale / ITEM_GUI_SCALE;
        pose.scale(normalized, -normalized, normalized);
        renderer.render(stack, ItemDisplayContext.GUI, false, pose, buffer,
                FULL_BRIGHT, OverlayTexture.NO_OVERLAY, model);
        pose.popPose();
    }

    /**
     * 白色向下等腰直角三角形：尖端（直角顶点）钉在标记点，
     * 上边宽 {@code 2 * TRIANGLE_HALF_WIDTH} 个字体单位。
     * <p>RenderType 关掉深度测试，所以永远盖在方块之上。
     */
    private static void drawTriangle(PoseStack pose, MultiBufferSource buffer,
                                     float x, float y, float scale) {
        pose.pushPose();
        pose.translate(x, y, 0.0f);
        pose.scale(scale, scale, scale);
        Matrix4f matrix = pose.last().pose();
        VertexConsumer vertices = buffer.getBuffer(TRIANGLE);
        // y 向下：底边在尖端的上方（负 y）
        vertex(vertices, matrix, 0.0f, 0.0f);
        vertex(vertices, matrix, -TRIANGLE_HALF_WIDTH, -TRIANGLE_HALF_WIDTH);
        vertex(vertices, matrix, TRIANGLE_HALF_WIDTH, -TRIANGLE_HALF_WIDTH);
        pose.popPose();
    }

    private static void vertex(VertexConsumer vertices, Matrix4f matrix, float x, float y) {
        vertices.addVertex(matrix, x, y, 0.0f).setColor(1.0f, 1.0f, 1.0f, 1.0f);
    }
}
