package com.catoxide.catoxidesbattlerebuild.client.renderer;

import cn.solarmoon.spark_core.animation.IEntityAnimatable;
import cn.solarmoon.spark_core.animation.model.BonePose;
import cn.solarmoon.spark_core.animation.model.ModelInstance;
import cn.solarmoon.spark_core.animation.model.ModelPose;
import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.core.anim.AnimatedMob;
import com.catoxide.catoxidesbattlerebuild.core.collision.SwordCollision;
import com.catoxide.catoxidesbattlerebuild.core.hitbox.HitboxConfig;
import com.catoxide.catoxidesbattlerebuild.core.hitbox.HitboxResolver;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyUnit;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.EntityBoneSystem;
import com.catoxide.catoxidesbattlerebuild.weapon.IronSwordWeapon;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 剑碰撞箱调试可视化（客户端，开发工具）。
 * <p><b>仅手持本 Mod 铁剑时绘制</b>，与 {@link SwordCollision#checkHit} 的判定完全同源
 * （同一组常量、同一个骨骼 pivot、同样的扇形/距离门/深度排序），所见即所得：
 * <ul>
 *   <li><b>红色锥面</b>：扇形碰撞体——顶点=眼睛，轴=视线（含俯仰），半角 60°，
 *       截断长度 = reach 4.5 + 骨骼余量 1.5；扇形内的骨骼 = 触及部位；</li>
 *   <li><b>暗黄球线框</b>：目标中心距离门 4.5（眼→目标中心 ≤ 4.5 才能命中该目标）；</li>
 *   <li><b>紫色箭头</b>：横扫方向 swingDir（深度测量轴，从脚底起）；每个触及骨骼上
 *       有一条紫色短刻线 = 沿该轴的投影位置；</li>
 *   <li><b>数字 1..n</b>：触及骨骼按深度升序（1=首接触，伤害衰减第一档），
 *       骨骼 OBB 与眼→骨连线按排名着色（橙→黄→绿→灰）；</li>
 *   <li><b>白叉</b>：扇形为空时的回退最近骨（FALLBACK 路径）；
 *       <b>黄环</b>：无骨骼数据的整身命中目标；</li>
 *   <li><b>绿/红 眼→目标中心线</b>：瞄准目标在/不在 4.5 距离门内。</li>
 * </ul>
 * <p>骨骼世界坐标直接读客户端 Spark 模型 pose（{@code getWorldBonePivotMatrix(partialTick)}
 * 的平移）——与服务端 {@code BoneDataManager.onBoneUpdate} 存的
 * {@code getWorldBonePivot} 是同一个量。部位集合取 {@link EntityBoneSystem} 的
 * BodyUnit（单人同 JVM 可读），取不到时回退 HitboxConfig 骨骼集合。
 * <p>左上角 HUD（{@link #renderHud(GuiGraphics)}）输出数值摘要，便于截图确认。
 */
@EventBusSubscriber(modid = CatoxidesBattleRebuildConstants.MODID, value = Dist.CLIENT)
public final class SwordHitboxDebugRenderer {

    /** 切换键（默认 F6，可在控制菜单重绑）。10/07 用户要求：默认隐藏，需要时按一下调出 */
    public static final KeyMapping TOGGLE_SWORD_HITBOX_DEBUG = new KeyMapping(
            "key.catoxidesbattlerebuild.sword_hitbox_debug",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F6, "key.categories.catoxides");

    /** 默认关闭（隐藏）；F6 切换 */
    private static volatile boolean enabled = false;

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_SWORD_HITBOX_DEBUG);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (TOGGLE_SWORD_HITBOX_DEBUG.consumeClick()) {
            enabled = !enabled;
        }
    }

    // ---- 颜色（ARGB）----
    private static final int COLOR_CONE = 0x99FF3030;      // 红：扇形碰撞体
    private static final int COLOR_REACH = 0x40FFD040;     // 暗黄：4.5 距离球
    private static final int COLOR_SWING = 0xB0C060FF;     // 紫：横扫/深度轴
    private static final int COLOR_OK = 0xFF40FF40;        // 绿：目标在距离门内
    private static final int COLOR_BAD = 0xFFFF4040;       // 红：目标超出距离门
    private static final int COLOR_FALLBACK = 0xFFFFFFFF;  // 白：回退最近骨
    private static final int COLOR_WHOLE = 0xFFFFD040;     // 黄：整身命中

    /** 排名色：1=首接触(亮橙) → 6+(灰)；与服务端按序衰减对应 */
    private static final int[] RANK_COLORS = {
            0xFFFF5020, 0xFFFFA020, 0xFFE8E830, 0xFF60E060, 0xFF40B0A0, 0xFF909090
    };

    /** 当前帧 HUD 行（客户端渲染线程写、同线程读） */
    public static volatile List<String> hudLines = List.of();

    private SwordHitboxDebugRenderer() {}

    /**
     * 世界空间绘制（RenderLevelStageEvent AFTER_PARTICLES 调用，同 BoneDebugRenderer）。
     */
    public static void render(PoseStack ignored, Vec3 camPos, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || mc.level == null || mc.player == null) {
            hudLines = List.of();
            return;
        }
        Player player = mc.player;
        if (!(player.getMainHandItem().getItem() instanceof IronSwordWeapon)) {
            hudLines = List.of();
            return;
        }

        Vec3 eye = player.getEyePosition();
        Vec3 axis = player.getViewVector(1.0F); // 含俯仰视线（与 SwordCollision 同源）
        float yawRad = player.getYRot() * ((float) (Math.PI / 180.0f));
        Vec3 swingDir = new Vec3(Math.cos(yawRad), 0.0, Math.sin(yawRad));
        Vec3 feet = player.position(); // 深度投影原点 = 脚底（与 SwordCollision 同源）

        PoseStack identity = new PoseStack();
        MultiBufferSource.BufferSource bs = mc.renderBuffers().bufferSource();
        VertexConsumer vc = bs.getBuffer(RenderType.lines());

        // 1) 扇形碰撞体（骨骼捕获体积）
        renderCone(vc, identity, eye, axis,
                SwordCollision.SWORD_REACH + SwordCollision.BONE_REACH_MARGIN,
                SwordCollision.CONE_COS, COLOR_CONE, camPos);

        // 2) 距离门球（目标中心 4.5）
        renderReachCircles(vc, identity, eye, SwordCollision.SWORD_REACH, COLOR_REACH, camPos);

        // 3) 横扫方向（深度轴）箭头，从脚底起
        OBBRenderer.renderLine(vc, identity, v3(feet), v3(feet.add(swingDir.scale(3.0))), COLOR_SWING, camPos);

        // 4) 各目标骨骼捕获 + 深度排序
        List<String> hud = new ArrayList<>();
        hud.add(String.format("[剑碰撞] 距离门 %.1f | 骨余量 %.1f | 半角 %.0f° | 贯穿 5（F6 隐藏）",
                SwordCollision.SWORD_REACH, SwordCollision.BONE_REACH_MARGIN,
                Math.toDegrees(SwordCollision.CONE_HALF_ANGLE)));
        hud.add("红锥=扇形 黄球=距离门 紫箭头=横扫方向(深度轴) 数字=深度序(1=首接触)");

        // ⚠️ 缓冲纪律（10/06 23:51 实崩 "Not building!"）：renderFloatingText→font.drawInBatch
        // 内部 getBuffer(文字型) 会把当前 shared 缓冲（lines 批次）先 bake 掉 ⇒
        // ① 画线 consumer 不得跨文字调用缓存，每个连续块开头重新 getBuffer；
        // ② 文字必须在该块所有画线完成之后统一画。
        Entity aimed = mc.crosshairPickEntity; // 1.21.1 准星拾取目标字段名
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof AnimatedMob<?> mob)) continue;
            if (player.distanceToSqr(mob) > 100.0) continue;
            boolean isAimed = e == aimed;
            TargetCapture tc = computeCapture(mob, eye, axis, swingDir, feet, partialTick);
            if (isAimed) appendHud(hud, mob, tc);
            renderCapture(bs, identity, eye, swingDir, tc, isAimed, camPos);
        }

        // 5) 瞄准目标（含非 AnimatedMob）：眼→目标中心线 + 整身命中环（块开头重取 consumer）
        if (aimed instanceof LivingEntity le && !(le instanceof Player)
                && player.distanceToSqr(le) <= 100.0) {
            Vec3 center = le.position().add(0, le.getEyeHeight() * 0.5, 0);
            double d = eye.distanceTo(center);
            boolean inRange = d <= SwordCollision.SWORD_REACH;
            VertexConsumer vc2 = bs.getBuffer(RenderType.lines());
            OBBRenderer.renderLine(vc2, identity, v3(eye), v3(center), inRange ? COLOR_OK : COLOR_BAD, camPos);
            if (!(le instanceof AnimatedMob)) {
                renderRing(vc2, identity, center, 0.35F, COLOR_WHOLE, camPos);
            }
        }

        bs.endBatch(); // 无参：结束 shared（lines/文字）+ 全部 fixed 批次
        hudLines = List.copyOf(hud);
    }

    // ================= 数据计算（与 SwordCollision.checkHit 同源） =================

    /** 捕获结果：中心距离 + 扇形内骨骼（按深度升序）+ 回退骨 + 整身标记 */
    private record TargetCapture(
            double targetDist,
            boolean inRange,
            List<CapturedBone> caps,
            CapturedBone fallback,
            boolean wholeBody
    ) {}

    private record CapturedBone(String name, Vec3 pos, double depth, Matrix4f matrix, HitboxConfig cfg) {}

    private static TargetCapture computeCapture(AnimatedMob<?> mob, Vec3 eye, Vec3 axis,
                                                Vec3 swingDir, Vec3 feet, float partialTick) {
        Vec3 center = mob.position().add(0, mob.getEyeHeight() * 0.5, 0);
        double targetDist = eye.distanceTo(center);
        boolean inRange = targetDist <= SwordCollision.SWORD_REACH;

        if (!(mob instanceof IEntityAnimatable<?> ia)) {
            return new TargetCapture(targetDist, inRange, List.of(), null, true);
        }
        ModelInstance model = ia.getModelController().getModel();
        if (model == null) return new TargetCapture(targetDist, inRange, List.of(), null, true);
        ModelPose poseModel = model.getPose();
        if (poseModel == null) return new TargetCapture(targetDist, inRange, List.of(), null, true);
        Map<String, BonePose> bonePoses = poseModel.getBonePoses();
        if (bonePoses.isEmpty()) return new TargetCapture(targetDist, inRange, List.of(), null, true);

        Map<String, HitboxConfig> configs = HitboxResolver.resolveFromModel(model);

        // 候选骨 = BodyUnit 集合（服务端同源）；取不到则回退 HitboxConfig 骨骼
        List<String> candidates = null;
        try {
            List<BodyUnit> units = EntityBoneSystem.getInstance().getDefaultBodyUnits(mob.getId());
            if (units != null && !units.isEmpty()) {
                candidates = new ArrayList<>();
                for (BodyUnit u : units) {
                    if (bonePoses.containsKey(u.getBoneName())) candidates.add(u.getBoneName());
                }
            }
        } catch (Throwable ignored) {
            // 客户端无骨骼系统数据（多人）→ 走回退
        }
        if (candidates == null || candidates.isEmpty()) {
            candidates = new ArrayList<>();
            for (String b : configs.keySet()) {
                if (bonePoses.containsKey(b)) candidates.add(b);
            }
        }
        if (candidates.isEmpty()) {
            return new TargetCapture(targetDist, inRange, List.of(), null, true);
        }

        List<CapturedBone> caps = new ArrayList<>();
        CapturedBone closest = null;
        double closestDist = Double.MAX_VALUE;
        for (String bone : candidates) {
            BonePose bp = bonePoses.get(bone);
            Matrix4f m = bp.getWorldBonePivotMatrix(partialTick);
            Vector3f t = new Vector3f();
            m.getTranslation(t);
            Vec3 pos = new Vec3(t.x, t.y, t.z);
            double d = eye.distanceTo(pos);
            if (d > SwordCollision.SWORD_REACH + SwordCollision.BONE_REACH_MARGIN) continue;
            if (d < closestDist) {
                closestDist = d;
                closest = new CapturedBone(bone, pos, 0, m, configs.get(bone));
            }
            Vec3 to = pos.subtract(eye);
            double len = to.length();
            if (len < 1e-6) continue;
            double cosAngle = (to.x * axis.x + to.y * axis.y + to.z * axis.z) / len;
            if (cosAngle >= SwordCollision.CONE_COS) {
                double depth = pos.subtract(feet).dot(swingDir);
                caps.add(new CapturedBone(bone, pos, depth, m, configs.get(bone)));
            }
        }

        if (caps.isEmpty()) {
            // 回退最近骨（服务端同路径）
            return new TargetCapture(targetDist, inRange, List.of(), closest, false);
        }
        double minDepth = caps.stream().mapToDouble(CapturedBone::depth).min().orElse(0.0);
        caps.sort(Comparator.comparingDouble(c -> c.depth() - minDepth));
        return new TargetCapture(targetDist, inRange, caps, null, false);
    }

    // ================= 绘制 =================

    /** 排名数字标签（文字统一在画线全部完成后绘制，见 render() 里的缓冲纪律注释） */
    private record Label(String text, double x, double y, double z, int color, float scale) {}

    private static void renderCapture(MultiBufferSource bs, PoseStack identity,
                                      Vec3 eye, Vec3 swingDir, TargetCapture tc,
                                      boolean isAimed, Vec3 camPos) {
        if (tc.wholeBody()) {
            // 整身命中（无骨骼数据）：中心黄环由 render() 第 5 段负责
            return;
        }
        int alpha = isAimed ? 230 : 110;
        VertexConsumer vc = bs.getBuffer(RenderType.lines()); // 块开头取一次，块内不插文字
        List<Label> labels = new ArrayList<>();

        if (!tc.caps().isEmpty()) {
            for (int i = 0; i < tc.caps().size(); i++) {
                CapturedBone c = tc.caps().get(i);
                int rankColor = RANK_COLORS[Math.min(i, RANK_COLORS.length - 1)];
                int rgba = (alpha << 24) | (rankColor & 0x00FFFFFF);
                if (c.cfg() != null) {
                    AABB local = new AABB(-c.cfg().halfExtents().x(), -c.cfg().halfExtents().y(), -c.cfg().halfExtents().z(),
                            c.cfg().halfExtents().x(), c.cfg().halfExtents().y(), c.cfg().halfExtents().z());
                    Quaternionf rot = new Quaternionf();
                    c.matrix().getUnnormalizedRotation(rot);
                    OBBRenderer.renderOBB(vc, identity, local, rot,
                            new Color(rankColor & 0x00FFFFFF, true), alpha / 255f, v3(c.pos()), camPos);
                } else {
                    renderCross(vc, identity, c.pos(), 0.18F, rgba, camPos);
                }
                OBBRenderer.renderLine(vc, identity, v3(eye), v3(c.pos()), rgba, camPos);
                // 深度投影刻线（沿横扫轴）
                OBBRenderer.renderLine(vc, identity,
                        v3(c.pos().subtract(swingDir.scale(0.15))),
                        v3(c.pos().add(swingDir.scale(0.15))), COLOR_SWING, camPos);
                labels.add(new Label(String.valueOf(i + 1), c.pos().x, c.pos().y + 0.35, c.pos().z,
                        0xFFFFFF, 0.035F));
            }
        } else if (tc.fallback() != null) {
            CapturedBone fb = tc.fallback();
            renderCross(vc, identity, fb.pos(), 0.25F, COLOR_FALLBACK, camPos);
            OBBRenderer.renderLine(vc, identity, v3(eye), v3(fb.pos()), COLOR_FALLBACK, camPos);
            labels.add(new Label("F", fb.pos().x, fb.pos().y + 0.35, fb.pos().z,
                    COLOR_FALLBACK, 0.04F));
        }

        for (Label lb : labels) {
            DebugRenderer.renderFloatingText(identity, bs, lb.text(), lb.x(), lb.y(), lb.z(), lb.color(), lb.scale());
        }
    }

    /** 红色锥面：顶点=apex，轴=axis（单位向量），半角 cosHalf，表面长度 length */
    private static void renderCone(VertexConsumer vc, PoseStack identity, Vec3 apex, Vec3 axis,
                                   double length, double cosHalf, int rgba, Vec3 camPos) {
        double sinHalf = Math.sqrt(Math.max(0.0, 1.0 - cosHalf * cosHalf));
        Vec3 ref = Math.abs(axis.y) < 0.99 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 p1 = ref.cross(axis).normalize();
        Vec3 p2 = axis.cross(p1).normalize();
        int seg = 24;
        Vector3f prev = null;
        for (int i = 0; i <= seg; i++) {
            double t = 2.0 * Math.PI * i / seg;
            Vec3 dir = axis.scale(cosHalf)
                    .add(p1.scale(Math.cos(t) * sinHalf))
                    .add(p2.scale(Math.sin(t) * sinHalf));
            Vec3 rim = apex.add(dir.scale(length));
            Vector3f r3 = v3(rim);
            if (i % 3 == 0 && i < seg) {
                OBBRenderer.renderLine(vc, identity, v3(apex), r3, rgba, camPos); // 8 条辐条
            }
            if (prev != null) {
                OBBRenderer.renderLine(vc, identity, prev, r3, rgba, camPos); // 远端环
            }
            prev = r3;
        }
    }

    /** 距离门球：眼为球心、半径 r 的三圈大圆（水平 + 两个垂直面） */
    private static void renderReachCircles(VertexConsumer vc, PoseStack identity, Vec3 eye,
                                           double r, int rgba, Vec3 camPos) {
        Vec3[] us = {new Vec3(1, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0)};
        Vec3[] vs = {new Vec3(0, 0, 1), new Vec3(0, 1, 0), new Vec3(0, 0, 1)};
        int seg = 16;
        for (int plane = 0; plane < 3; plane++) {
            Vector3f prev = null;
            for (int i = 0; i <= seg; i++) {
                double t = 2.0 * Math.PI * i / seg;
                Vec3 p = eye.add(us[plane].scale(r * Math.cos(t))).add(vs[plane].scale(r * Math.sin(t)));
                Vector3f p3 = v3(p);
                if (prev != null) OBBRenderer.renderLine(vc, identity, prev, p3, rgba, camPos);
                prev = p3;
            }
        }
    }

    private static void renderCross(VertexConsumer vc, PoseStack identity, Vec3 center,
                                    float size, int rgba, Vec3 camPos) {
        OBBRenderer.renderLine(vc, identity, v3(center.add(-size, 0, 0)), v3(center.add(size, 0, 0)), rgba, camPos);
        OBBRenderer.renderLine(vc, identity, v3(center.add(0, -size, 0)), v3(center.add(0, size, 0)), rgba, camPos);
        OBBRenderer.renderLine(vc, identity, v3(center.add(0, 0, -size)), v3(center.add(0, 0, size)), rgba, camPos);
    }

    private static void renderRing(VertexConsumer vc, PoseStack identity, Vec3 center,
                                   float r, int rgba, Vec3 camPos) {
        int seg = 16;
        Vector3f prev = null;
        for (int i = 0; i <= seg; i++) {
            double t = 2.0 * Math.PI * i / seg;
            Vec3 p = center.add(r * Math.cos(t), 0, r * Math.sin(t));
            Vector3f p3 = v3(p);
            if (prev != null) OBBRenderer.renderLine(vc, identity, prev, p3, rgba, camPos);
            prev = p3;
        }
    }

    // ================= HUD =================

    private static void appendHud(List<String> hud, AnimatedMob<?> mob, TargetCapture tc) {
        String name = net.minecraft.world.entity.EntityType.getKey(mob.getType()).toString();
        String range = tc.inRange() ? "√ 在距离门内" : "× 超出距离门";
        hud.add(String.format("瞄准 %s  d=%.2f %s", name, tc.targetDist(), range));
        if (tc.wholeBody()) {
            hud.add("  → 整身命中（无骨骼数据）");
        } else if (!tc.caps().isEmpty()) {
            StringBuilder sb = new StringBuilder("  → 触及部位（深度序）:");
            double min = tc.caps().get(0).depth();
            for (int i = 0; i < tc.caps().size(); i++) {
                CapturedBone c = tc.caps().get(i);
                sb.append(String.format("  [%d]%s(%.2f)", i + 1, c.name(), c.depth() - min));
            }
            hud.add(sb.toString());
        } else if (tc.fallback() != null) {
            hud.add(String.format("  → 扇形为空，回退最近骨: %s", tc.fallback().name()));
        }
    }

    /** HUD 绘制（RenderGuiEvent.Post 调用） */
    public static void renderHud(GuiGraphics g) {
        List<String> lines = hudLines;
        if (lines.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int y = 4;
        for (String line : lines) {
            g.drawString(font, line, 4, y, 0xFFE0E0E0, true);
            y += 10;
        }
    }

    private static Vector3f v3(Vec3 v) {
        return new Vector3f((float) v.x, (float) v.y, (float) v.z);
    }
}
