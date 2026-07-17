package com.catoxide.catoxidesbattlerebuild.mixin;

import com.catoxide.catoxidesbattlerebuild.core.combat.AttackProperties;
import com.catoxide.catoxidesbattlerebuild.core.combat.CombatResult;
import com.catoxide.catoxidesbattlerebuild.core.combat.CombatSystem;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyPart;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.BodyUnit;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.EntityBoneSystem;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 拦截所有实体受伤事件，将攻击纳入战斗系统处理。
 * <p>替代原版死亡判定：实体死亡不再只看基础血量，而是检查是否有致命部位被破坏。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityHurtMixin {

    static {
        LogManager.serverInfo("TEST-Mixin", "✅ LivingEntityHurtMixin loaded into JVM");
    }

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void onHurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        LogManager.serverInfo("TEST-HurtMixin", "========== [TEST] LivingEntityHurtMixin TRIGGERED ==========");

        LivingEntity target = (LivingEntity) (Object) this;
        if (target.level().isClientSide()) {
            LogManager.serverDebug("TEST-HurtMixin", "  [SKIP] Client-side");
            return;
        }

        Entity attacker = source.getEntity();
        if (!(attacker instanceof LivingEntity)) {
            LogManager.serverDebug("TEST-HurtMixin", "  [SKIP] Attacker is not LivingEntity (is {})",
                attacker != null ? attacker.getClass().getSimpleName() : "null");
            return;
        }

        int entityId = target.getId();
        LogManager.serverInfo("TEST-HurtMixin", "  Target entity={}, type={}, health={}",
            entityId, target.getType().getDescriptionId(), target.getHealth());
        LogManager.serverInfo("TEST-HurtMixin", "  Attacker={}, rawDamage={}",
            attacker.getName().getString(), amount);

        EntityBoneSystem boneSystem = EntityBoneSystem.getInstance();

        // 获取目标的所有骨骼单位
        List<BodyUnit> units = boneSystem.getDefaultBodyUnits(entityId);

        if (units.isEmpty()) {
            LogManager.serverDebug("TEST-HurtMixin", "  [SKIP] No body units for entity={}", entityId);
            LogManager.serverDebug("TEST-HurtMixin", "  Falling through to vanilla damage");
            return;
        }

        // 打印所有 Part 状态
        Set<BodyPart> seenParts = new HashSet<>();
        for (BodyUnit u : units) {
            BodyPart part = u.getParentPart();
            if (!seenParts.contains(part)) {
                seenParts.add(part);
                LogManager.serverInfo("TEST-HurtMixin", "  [PART] name={}, moduleHealth={}/{}, isFatal={}, isDestroyed={}",
                    part.getPartName(), part.getModuleHealth(), part.getMaxModuleHealth(),
                    part.isFatal(), part.isDestroyed());
            }
        }

        // 收集 BodyPart 模块血量（用 Part 的模块血量，不用 Unit 的硬编码 100）
        float[] healths = new float[units.size()];
        for (int i = 0; i < units.size(); i++) {
            healths[i] = units.get(i).getParentPart().getModuleHealth();
        }

        // 默认近战攻击参数：5穿甲，0贯穿（近战不穿透）
        AttackProperties attack = AttackProperties.of(5.0f, 0.0f, amount);
        LogManager.serverInfo("TEST-HurtMixin", "  Attack params: armorPen=5, piercing=0, baseDamage={}", amount);

        // 通过战斗系统处理
        CombatResult result = CombatSystem.getInstance().processAttack(attack, units, healths);

        LogManager.serverInfo("TEST-HurtMixin", "  CombatResult: totalDamage={}, hitCount={}, hasDestroyed={}",
            result.totalDamage(), result.hitResults().size(), result.hasDestroyedParts());

        if (result.totalDamage() > 0) {
            // 按 BodyPart 聚合伤害
            Map<BodyPart, Float> partDamageMap = new HashMap<>();
            for (CombatResult.HitResult hit : result.hitResults()) {
                BodyPart part = hit.bodyUnit().getParentPart();
                partDamageMap.merge(part, hit.actualDamage(), Float::sum);
            }

            // 扣除每个 BodyPart 的模块血量
            boolean hasFatalDestroyed = false;
            for (Map.Entry<BodyPart, Float> entry : partDamageMap.entrySet()) {
                BodyPart part = entry.getKey();
                float before = part.getModuleHealth();
                part.applyDamage(entry.getValue());
                float after = part.getModuleHealth();

                LogManager.serverInfo("TEST-HurtMixin", "  [DAMAGE] part={}, before={}, damage={}, after={}, destroyed={}, fatal={}",
                    part.getPartName(), before, entry.getValue(), after, part.isDestroyed(), part.isFatal());

                if (part.isDestroyed() && part.isFatal()) {
                    hasFatalDestroyed = true;
                    LogManager.serverInfo("TEST-HurtMixin", "  🚨 FATAL PART DESTROYED: {}", part.getPartName());
                }
            }

            if (hasFatalDestroyed) {
                LogManager.serverInfo("TEST-HurtMixin", "  💀 Killing entity due to fatal part destruction");
                target.kill();
                cir.setReturnValue(true);
                cir.cancel();
            } else {
                LogManager.serverDebug("TEST-HurtMixin", "  Continuing with vanilla hurt for base HP damage");
            }
        }

        LogManager.serverInfo("TEST-HurtMixin", "========== [TEST] LivingEntityHurtMixin END ==========");
    }
}
