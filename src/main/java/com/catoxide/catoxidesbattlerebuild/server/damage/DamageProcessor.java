package com.catoxide.catoxidesbattlerebuild.server.damage;

import com.catoxide.catoxidesbattlerebuild.core.behavior.BehaviorRouter;
import com.catoxide.catoxidesbattlerebuild.core.behavior.HurtContext;
import com.catoxide.catoxidesbattlerebuild.server.bodypart.EntityBoneSystem;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.world.entity.LivingEntity;

public class DamageProcessor {
    private static final DamageProcessor INSTANCE = new DamageProcessor();

    private DamageProcessor() {
        LogManager.serverStartup("DamageProcessor", "DamageProcessor initialized");
    }

    public static DamageProcessor getInstance() {
        return INSTANCE;
    }

    public void processHit(LivingEntity attacker, LivingEntity target, String boneName, float damage) {
        // 行为分发：插件注册的受击处理器可接管整条伤害链路（短路默认逻辑）
        if (BehaviorRouter.routeHurt(new HurtContext(attacker, target, boneName, damage))) {
            return;
        }

        LogManager.serverDebug("DamageProcessor", "Processing hit: attacker={}, target={}, boneName={}, damage={}",
                attacker.getName().getString(), target.getName().getString(), boneName, damage);
        
        EntityBoneSystem.DamageResult result = EntityBoneSystem.getInstance()
                .processEntityHit(target.getId(), boneName, damage);

        if (result != null) {
            LogManager.serverInfo("DamageProcessor", "Applying base damage to target: target={}, damage={}",
                    target.getName().getString(), result.baseDamage());
            
            target.hurt(target.damageSources().mobAttack(attacker), result.baseDamage());

            if (result.isFatal()) {
                LogManager.serverInfo("DamageProcessor", "Fatal hit, killing target: target={}",
                        target.getName().getString());
                target.kill();
            }

            LogManager.serverDebug("DamageProcessor", "Hit result: partName={}, boneName={}, unitDamage={}, " +
                            "baseDamage={}, isFatal={}, specialEffects={}, hitSound={}",
                    result.partName(), result.boneName(), result.moduleDamage(),
                    result.baseDamage(), result.isFatal(), result.specialEffects(), result.hitSound());
        } else {
            LogManager.serverDebug("DamageProcessor", "No damage result, hit not processed");
        }
    }
}
