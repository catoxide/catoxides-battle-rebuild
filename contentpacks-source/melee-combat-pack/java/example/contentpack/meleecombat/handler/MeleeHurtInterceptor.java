package example.contentpack.meleecombat.handler;

import example.contentpack.meleecombat.combat.ArrowCombatState;
import example.contentpack.meleecombat.combat.AttackProperties;
import example.contentpack.meleecombat.combat.CombatResult;
import example.contentpack.meleecombat.combat.CombatSystem;
import com.catoxide.catoxidesbattlerebuild.core.combat.IHurtInterceptor;
import example.contentpack.meleecombat.combat.SwordCombat;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyPart;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyUnit;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.EntityBoneSystem;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 近战受击拦截器（core 实现，M5；M6 随战斗逻辑一并移入战斗内容包，
 * 注册点同步移入包的 {@code register()} —— F4 单一注册点）。
 * <p>承载原内联在 {@code LivingEntityHurtMixin} 里的部位伤害机制（F1：逻辑与门分离）：
 * LivingEntity 近战 → 战斗管线（穿甲 5 / 贯穿 0）→ BodyPart 模块血量 →
 * 致命部位摧毁 = 直接死亡（替代原版只看基础血量的死亡判定）。
 * <p>特例：剑/弓处理器已接管时（其 flag 已置位），进入 {@code hurt} 的调用是它们
 * 扣基础血量的回调 —— 此时放行原版路径扣基础血（返回 false），防止二次处理部位。
 */
public class MeleeHurtInterceptor implements IHurtInterceptor {

    private static final String TAG = "MeleeHurt";

    /** 默认近战参数：5 穿甲，0 贯穿（近战不穿透） */
    private static final float MELEE_ARMOR_PENETRATION = 5.0f;
    private static final float MELEE_PIERCING = 0.0f;

    @Override
    public boolean intercept(LivingEntity target, DamageSource source, float amount) {
        // 剑/弓处理器正在处理（此 hurt 是其回基础血量的调用）→ 放行原版扣基础血，防二次处理
        // ⚠️ flag 必须读普通类（SwordCombat / ArrowCombatState），不得读 mixin 包类（IllegalClassLoadError）
        if (SwordCombat.isProcessingSwordHit() || ArrowCombatState.isProcessing()) {
            return false;
        }

        Entity attacker = source.getEntity();
        if (!(attacker instanceof LivingEntity)) {
            return false;
        }

        List<BodyUnit> units = EntityBoneSystem.getInstance().getDefaultBodyUnits(target.getId());
        if (units.isEmpty()) {
            return false;
        }

        // 收集 BodyPart 模块血量（用 Part 的模块血量，不用 Unit 的硬编码 100）
        float[] healths = new float[units.size()];
        for (int i = 0; i < units.size(); i++) {
            healths[i] = units.get(i).getParentPart().getModuleHealth();
        }

        CombatResult result = CombatSystem.getInstance()
                .processAttack(AttackProperties.of(MELEE_ARMOR_PENETRATION, MELEE_PIERCING, amount), units, healths);
        if (result.totalDamage() <= 0.0f) {
            return false;
        }

        // 按 BodyPart 聚合伤害并扣模块血量
        Map<BodyPart, Float> partDamage = new HashMap<>();
        for (CombatResult.HitResult hit : result.hitResults()) {
            partDamage.merge(hit.bodyUnit().getParentPart(), hit.actualDamage(), Float::sum);
        }

        boolean fatalDestroyed = false;
        for (Map.Entry<BodyPart, Float> entry : partDamage.entrySet()) {
            BodyPart part = entry.getKey();
            part.applyDamage(entry.getValue());
            if (part.isDestroyed() && part.isFatal()) {
                fatalDestroyed = true;
            }
        }

        if (fatalDestroyed) {
            LogManager.serverInfo(TAG, "Fatal part destroyed → killing entity={}", target.getId());
            target.kill();
            return true;
        }
        // 非致命：放行原版路径继续扣基础血量
        return false;
    }
}
