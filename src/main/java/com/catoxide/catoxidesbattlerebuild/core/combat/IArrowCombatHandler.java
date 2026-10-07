package com.catoxide.catoxidesbattlerebuild.core.combat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;

/**
 * 弓箭/投射物战斗处理器契约（R2：契约住 core，实现住包）。
 * <p>{@code AbstractArrow} mixin 是纯门（R1）：每 tick 的自定义轨迹物理与骨骼碰撞
 * （{@link #onTick}）、命中实体时切断原版 AABB 伤害链（{@link #onHitEntity}）
 * 全部委托给本注册表里的处理器。
 * <p>R3：未注册时箭矢 = 原版行为（原版物理 + AABB 伤害）。
 * <p>规则 A：本签名只引用原版/core 类型，不得出现包类型。
 */
public interface IArrowCombatHandler {

    /**
     * 箭矢 tick 时调用（门保证客户端不调用）。
     *
     * @param arrow 箭矢（服务端）
     * @return true = 本处理器已处理（如骨骼碰撞命中、箭矢已销毁），门停止询问后续处理器
     */
    boolean onTick(AbstractArrow arrow);

    /**
     * 箭矢命中实体时调用。
     *
     * @param arrow  箭矢
     * @param target 被命中的实体
     * @return true = 接管该次命中（门将 cancel 原版伤害链）
     */
    boolean onHitEntity(AbstractArrow arrow, Entity target);
}
