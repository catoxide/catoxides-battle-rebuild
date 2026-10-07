package example.contentpack.meleecombat.collision;

import com.catoxide.catoxidesbattlerebuild.core.anim.AnimatedMob;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyUnit;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.EntityBoneSystem;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 剑碰撞检测（服务端，弓同源骨骼数据）。
 * <p>对标 {@link ArrowBoneCollision} 的原理，剑版差异：剑没有飞行轨迹，
 * 碰撞箱 = 玩家视点出发的<b>扇形体积</b>（挥剑扇区）：
 * <ol>
 *   <li>距离门：眼睛 → 目标中心 ≤ {@link #SWORD_REACH}（原近战 4.5 格公式）；</li>
 *   <li>触及部位选择：目标骨骼中落在扇形内（半角 {@link #CONE_HALF_ANGLE}，
 *       轴=玩家视线方向 getViewVector(含俯仰)）的全部骨骼；<b>深度沿剑的运动路径量</b>
 *       （横扫运动方向=垂直朝向、正前方由右向左扫过的水平切线方向）：
 *       首接触点（投影最小）深度=0，沿运动方向越远深度越大，
 *       管线按深度升序逐部位衰减（piercing=5，弓同款机制）；</li>
 *   <li>扇形内无骨骼（目标在身后等）→ 回退最近单骨（保持"总能打中面向最近的部位"）。</li>
 * </ol>
 * <p>二期：真·剑刃 OBB（客户端 blade 骨世界矩阵实测 → HitAttemptPacket 上报，服务端复核）。
 */
public final class SwordCollision {

    private static final String TAG = "SwordCollision";

    /** 剑攻击距离（格），与原近战判定一致 */
    public static final double SWORD_REACH = 4.5;

    /** 挥剑扇区半角（3D 锥角，含俯仰）。60° 覆盖正前方大部分区域，可调（SwordHitboxDebugRenderer 可视化共用） */
    public static final double CONE_HALF_ANGLE = Math.toRadians(60.0);
    public static final double CONE_COS = Math.cos(CONE_HALF_ANGLE);

    /** 扇形长度余量（格）：目标在距离门内时，其身体远侧骨骼允许略超 SWORD_REACH（SwordHitboxDebugRenderer 可视化共用） */
    public static final double BONE_REACH_MARGIN = 1.5;

    private SwordCollision() {
    }

    /**
     * 剑命中结果。
     *
     * @param hit      是否命中（距离门通过）
     * @param hitUnits 触及的骨骼单位（按沿挥砍运动路径的深度升序；空 = 目标无骨骼数据，调用方自行兜底）
     * @param distance 眼睛到目标中心的距离
     */
    public record SwordHitResult(boolean hit, List<BodyUnit> hitUnits, double distance) {
    }

    /**
     * 检测剑触及目标的部位（弓的贯穿机制按 hitUnits 顺序逐部位衰减）。
     */
    public static SwordHitResult checkHit(Player attacker, LivingEntity target) {
        Vec3 eye = attacker.getEyePosition();
        Vec3 axis = attacker.getViewVector(1.0F); // 含俯仰的视线方向（getRotationVector 在此版本只返回偏航 Vec2）

        // 1. 距离门（原公式：眼睛 → 目标中心）
        Vec3 targetPos = target.position().add(0, target.getEyeHeight() * 0.5, 0);
        double targetDist = eye.distanceTo(targetPos);
        if (targetDist > SWORD_REACH) {
            return new SwordHitResult(false, List.of(), targetDist);
        }

        if (target instanceof AnimatedMob<?> mob) {
            List<BodyUnit> units = EntityBoneSystem.getInstance().getDefaultBodyUnits(target.getId());
            if (!units.isEmpty()) {
                Map<String, Vector3f> bones = mob.getAllServerBonePositions();
                List<BodyUnit> inCone = new ArrayList<>();
                BodyUnit closest = null;
                double closestDist = Double.MAX_VALUE;

                for (BodyUnit unit : units) {
                    Vector3f p = bones.get(unit.getBoneName());
                    if (p == null) {
                        continue;
                    }
                    Vec3 bonePos = new Vec3(p.x(), p.y(), p.z());
                    double d = eye.distanceTo(bonePos);
                    if (d > SWORD_REACH + BONE_REACH_MARGIN) {
                        continue;
                    }
                    if (d < closestDist) {
                        closestDist = d;
                        closest = unit;
                    }
                    // 扇形判定：视线方向与 视点→骨骼 的夹角 ≤ 半角
                    Vec3 to = bonePos.subtract(eye);
                    double len = to.length();
                    if (len > 1e-6) {
                        double cosAngle = (to.x * axis.x + to.y * axis.y + to.z * axis.z) / len;
                        if (cosAngle >= CONE_COS) {
                            inCone.add(unit);
                        }
                    }
                }

                if (!inCone.isEmpty()) {
                    // 深度沿剑的运动路径（不是从视点量）：横扫运动方向 = 垂直朝向、
                    // 正前方由右向左扫过的水平切线方向（up × facing = 玩家左侧）。
                    // ⚠️ 服务端无法区分横扫/劈砍（原版攻击包不带挥砍类型），统一按横扫近似。
                    // 深度 = 各骨沿 D 的投影 − 首接触点（投影最小者）；管线按此升序逐部位衰减。
                    float yaw = attacker.getYRot() * ((float) (Math.PI / 180.0f));
                    Vec3 swingDir = new Vec3(Math.cos(yaw), 0.0, Math.sin(yaw));
                    Vec3 origin = attacker.position();
                    Map<String, Double> proj = new HashMap<>();
                    double minProj = Double.MAX_VALUE;
                    for (BodyUnit u : inCone) {
                        Vector3f bp = bones.get(u.getBoneName());
                        if (bp == null) {
                            continue;
                        }
                        Vec3 rel = new Vec3(bp.x() - origin.x, bp.y() - origin.y, bp.z() - origin.z);
                        double v = rel.dot(swingDir);
                        proj.put(u.getBoneName(), v);
                        minProj = Math.min(minProj, v);
                    }
                    final double minDepth = minProj;
                    inCone.sort(Comparator.comparingDouble(u -> {
                        Double v = proj.get(u.getBoneName());
                        return v == null ? Double.MAX_VALUE : v - minDepth;
                    }));
                    return new SwordHitResult(true, inCone, targetDist);
                }

                // 2. 扇形内无骨骼（目标在身后等）→ 回退最近单骨
                if (closest != null) {
                    LogManager.serverDebug(TAG, "[FALLBACK] cone empty (target behind?), using closest bone={}",
                            closest.getBoneName());
                    return new SwordHitResult(true, List.of(closest), targetDist);
                }
            }
        }

        // 距离通过但无骨骼数据（原版生物等）→ 命中，hitUnits 空（调用方兜底）
        return new SwordHitResult(true, List.of(), targetDist);
    }
}
