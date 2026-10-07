package com.catoxide.catoxidesbattlerebuild.core.combat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * 剑近战攻击处理器契约（R2：契约住 core，实现住包）。
 * <p>{@code Player.attack} mixin 是纯门（R1）：有处理器登记且处理器报告接管时，
 * 切断整条原版近战链（伤害/击退/横扫/耐久/音效），由处理器自管。
 * <p>R3：未注册时模组剑走原版武器路径 —— 主 mod 单独运行 = 原版行为。
 * <p>规则 A：本签名只引用原版/core 类型，不得出现包类型。
 */
public interface ISwordAttackHandler {

    /**
     * 尝试接管一次近战攻击（客户端与服务端都会调用）。
     *
     * @param player 发起攻击的玩家
     * @param target 被点击的实体
     * @return true = 已接管（门将 cancel 原版攻击链；客户端/服务端职责划分由实现自管）
     */
    boolean tryHandle(Player player, Entity target);
}
