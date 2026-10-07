package com.catoxide.catoxidesbattlerebuild.core.combat;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 箭矢命中处理状态（普通类，<b>不在 mixin 包内</b>）。
 * <p>⚠️ {@code LivingEntityHurtMixin} 的 handler 被内联进 {@code LivingEntity} 后
 * 属于非 mixin 类的字节码，不能直接引用 mixin 包里的类（Mixin 框架抛
 * {@code IllegalClassLoadError}，2026-10-06 23:00 实崩）。因此"箭矢命中处理中"
 * 标志必须放这里：{@code ArrowHitMixin} 只负责置位/清位，
 * {@code LivingEntityHurtMixin} 负责读取。剑的对应 flag 在 {@link SwordCombat}（同为普通类）。</p>
 */
public final class ArrowCombatState {

    private static final AtomicBoolean PROCESSING = new AtomicBoolean(false);

    private ArrowCombatState() {
    }

    /** 置位：箭矢命中战斗处理开始/结束 */
    public static void setProcessing(boolean processing) {
        PROCESSING.set(processing);
    }

    /** 供 LivingEntityHurtMixin 查询：是否正在处理箭矢命中（是则跳过，防二次处理） */
    public static boolean isProcessing() {
        return PROCESSING.get();
    }
}
