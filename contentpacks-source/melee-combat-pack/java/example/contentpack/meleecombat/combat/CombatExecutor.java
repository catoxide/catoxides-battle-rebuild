package example.contentpack.meleecombat.combat;

import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyPart;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyUnit;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.EntityBoneSystem;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 共享战斗执行器（③打包复用：剑 {@code SwordCombat} 与弓 {@code ArrowHitMixin} 同一条执行路径）。
 * <p>职责 = 两条武器路径里逐行重复的那段：
 * <ol>
 *   <li>取目标部位（EntityBoneSystem：BodyPart 展开，空则默认部位）；</li>
 *   <li>收集命中单位的部位模块血量；</li>
 *   <li>执行 {@link CombatSystem} 管线（穿甲/贯穿 → 逐单位伤害）；</li>
 *   <li>按 BodyPart 聚合扣模块血量，致命部位摧毁标记；</li>
 *   <li>总 HP 伤害按 {@link BaseHpMode} 取值并经 {@code target.hurt} 扣基础血量
 *       （调用方需保证 hurt mixin 的处理标记已置位，否则会被 hurt mixin 二次处理）；</li>
 *   <li>致命部位摧毁 → kill。</li>
 * </ol>
 * <p>碰撞检测、基础伤害公式、武器后处理（耐久/击退/音效/箭矢销毁）仍留在各武器侧。
 * <p>⚠️ 本类是普通类（非 mixin 包）：mixin handler 内联后引用本类安全（mixin 包类互引才会 IllegalClassLoadError）。
 */
public final class CombatExecutor {

    private CombatExecutor() {
    }

    /** 总 HP 伤害的取值方式 */
    public enum BaseHpMode {
        /** Σ(部位伤害 × transmissionToBase)——设计通道（applyDamage 返回值）。剑 10/06 起使用 */
        TRANSMITTED,
        /** 管线原始总伤 totalDamage——弓现行行为（是否切换为 TRANSMITTED 待用户拍板） */
        RAW
    }

    /** 执行结果 */
    public record Execution(boolean damaged, float baseHpDamage, boolean fatalDestroyed) {
        public static final Execution NONE = new Execution(false, 0.0F, false);
    }

    /**
     * 目标部位列表（剑/弓同源）：BodyPart 展开全部单位，空则回退默认部位。
     */
    public static List<BodyUnit> getTargetUnits(int targetId) {
        EntityBoneSystem boneSystem = EntityBoneSystem.getInstance();
        List<BodyUnit> units = new ArrayList<>();
        for (BodyPart part : boneSystem.getBodyParts(targetId)) {
            units.addAll(part.getUnits());
        }
        if (units.isEmpty()) {
            units = boneSystem.getDefaultBodyUnits(targetId);
        }
        return units;
    }

    /** 收集命中单位的部位模块血量快照（管线入参） */
    public static float[] collectModuleHealths(List<BodyUnit> hitUnits) {
        float[] healths = new float[hitUnits.size()];
        for (int i = 0; i < hitUnits.size(); i++) {
            healths[i] = hitUnits.get(i).getParentPart().getModuleHealth();
        }
        return healths;
    }

    /**
     * 执行管线 + 扣部位血量 + 扣总 HP + 致命处理（剑/弓共用核心）。
     *
     * @param target   被击目标
     * @param source   总 HP 伤害源（剑=playerAttack、弓=mobAttack(射手)）
     * @param mode     总 HP 取值方式（见 {@link BaseHpMode}）
     * @param attack   攻击参数（穿甲/贯穿/基础伤害）
     * @param hitUnits 命中单位（按深度/距离升序；管线按序贯穿衰减）
     * @param logTag   日志标签（"SwordCombat" / "ArrowCombat"）
     */
    public static Execution execute(LivingEntity target, DamageSource source, BaseHpMode mode,
                                    AttackProperties attack, List<BodyUnit> hitUnits, String logTag) {
        float[] healths = collectModuleHealths(hitUnits);
        CombatResult result = CombatSystem.getInstance().processAttack(attack, hitUnits, healths);

        if (result.totalDamage() <= 0.0F) {
            LogManager.serverDebug(logTag, "  [BLOCKED] attack blocked by armor/piercing");
            return Execution.NONE;
        }

        // 按 BodyPart 聚合伤害
        Map<BodyPart, Float> partDamageMap = new HashMap<>();
        for (CombatResult.HitResult hr : result.hitResults()) {
            partDamageMap.merge(hr.bodyUnit().getParentPart(), hr.actualDamage(), Float::sum);
        }

        boolean hasFatalDestroyed = false;
        float transmittedToBase = 0f;
        for (Map.Entry<BodyPart, Float> entry : partDamageMap.entrySet()) {
            BodyPart part = entry.getKey();
            float damageToPart = entry.getValue();
            float before = part.getModuleHealth();
            // applyDamage 返回 伤害×transmissionToBase（头1.5/躯干1.0/四肢0.5）= 该部位传给总HP的量
            transmittedToBase += part.applyDamage(damageToPart);
            LogManager.serverInfo(logTag, "  [DAMAGE] part={}, before={}, dmg={}, after={}, destroyed={}, toBase={}",
                    part.getPartName(), before, damageToPart, part.getModuleHealth(),
                    part.isDestroyed(), part.getTransmissionToBase());
            if (part.isDestroyed() && part.isFatal()) {
                hasFatalDestroyed = true;
                LogManager.serverInfo(logTag, "💀 FATAL PART DESTROYED: {}", part.getPartName());
            }
        }

        float baseHpDamage = mode == BaseHpMode.TRANSMITTED ? transmittedToBase : result.totalDamage();
        if (baseHpDamage > 0.0F) {
            target.hurt(source, baseHpDamage);
        }

        if (hasFatalDestroyed) {
            target.kill();
        }
        return new Execution(true, baseHpDamage, hasFatalDestroyed);
    }
}
