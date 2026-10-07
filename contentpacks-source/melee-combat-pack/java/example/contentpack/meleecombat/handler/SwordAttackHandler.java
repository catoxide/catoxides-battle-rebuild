package example.contentpack.meleecombat.handler;

import com.catoxide.catoxidesbattlerebuild.core.combat.ISwordAttackHandler;
import example.contentpack.meleecombat.combat.SwordCombat;
import com.catoxide.catoxidesbattlerebuild.weapon.IronSwordWeapon;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * 剑近战攻击处理器（core 实现，M5；M6 随 {@link SwordCombat} 一并移入战斗内容包，
 * 注册点同步移入包的 {@code register()} —— F4 单一注册点）。
 * <p>承载原 {@code SwordAttackMixin} 里的接管判定（F1：逻辑与门分离）：
 * 主手为模组剑 + 目标非豁免 → 接管；客户端只切断原版链（不做本地预测，
 * 挥砍动画由 swing 标记驱动、不受 attack() cancel 影响），服务端调用
 * {@link SwordCombat#processSwordAttack}。
 * <p>R3：未注册时模组剑走原版武器路径。
 */
public class SwordAttackHandler implements ISwordAttackHandler {

    @Override
    public boolean tryHandle(Player player, Entity target) {
        if (!(target instanceof LivingEntity)) {
            return false;
        }
        if (!(player.getMainHandItem().getItem() instanceof IronSwordWeapon)) {
            return false;
        }
        // 保留原版豁免：对原版不会造成伤害的目标（创意模式/非 PvP 玩家等）不接管，走原版链
        if (target.isInvulnerableTo(target.damageSources().playerAttack(player))) {
            return false;
        }

        // 客户端不做本地预测（伤害全由服务端判定）
        if (!player.level().isClientSide) {
            SwordCombat.processSwordAttack(player, (LivingEntity) target);
        }
        return true;
    }
}
