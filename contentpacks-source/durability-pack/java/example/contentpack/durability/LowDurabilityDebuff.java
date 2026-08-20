package example.contentpack.durability;

import com.catoxide.catoxidesbattlerebuild.core.durability.DurabilitySupport;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * 低耐久属性衰减（武器系统 2.系统）。
 * <p>可变耐久武器（经 {@link DurabilitySupport} 注册）在耐久低时攻击伤害衰减：
 * <pre>
 * 耐久 ≥50%  → ×1.0（正常）
 * 耐久 30-50% → ×0.85
 * 耐久 15-30% → ×0.7
 * 耐久 <15%  → ×0.5（接近报废）
 * </pre>
 * 阈值/衰减因子后续可迁移 toml 配置（同 durability-pack 模式）。
 */
public class LowDurabilityDebuff {

    private static final String TAG = "LowDurabilityDebuff";

    @SubscribeEvent
    public void onLivingIncomingDamage(LivingIncomingDamageEvent event) {
        Entity attacker = event.getSource().getDirectEntity();
        if (!(attacker instanceof LivingEntity living)) {
            return;
        }
        ItemStack weapon = living.getMainHandItem();
        if (weapon.isEmpty() || !DurabilitySupport.isVariableDurability(weapon)) {
            return;
        }
        float ratio = durabilityRatio(weapon);
        float factor = damageFactor(ratio);
        if (factor >= 1.0f) {
            return;
        }
        float original = event.getAmount();
        float reduced = original * factor;
        event.setAmount(reduced);
        LogManager.serverDebug(TAG, "Low-durability debuff: ratio=%.2f factor=%.2f damage=%.1f→%.1f (%s)",
                ratio, factor, original, reduced, weapon.getHoverName().getString());
    }

    /** 当前耐久比例 0.0~1.0 */
    private static float durabilityRatio(ItemStack stack) {
        int max = DurabilitySupport.getMaxDurability(stack);
        if (max <= 0) {
            return 1.0f;
        }
        int damage = stack.getDamageValue();
        return Math.max(0.0f, 1.0f - (float) damage / max);
    }

    /** 按耐久比例的伤害衰减因子 */
    private static float damageFactor(float ratio) {
        if (ratio >= 0.50f) {
            return 1.0f;
        }
        if (ratio >= 0.30f) {
            return 0.85f;
        }
        if (ratio >= 0.15f) {
            return 0.70f;
        }
        return 0.50f;
    }
}
