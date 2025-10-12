package com.catoxide.catoxidesbattlerebuild;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

// 武器碰撞箱接口
public interface IWeaponHitbox {
    /**
     * 获取当前帧的武器碰撞箱（世界坐标）
     * @param wielder 持有者实体
     * @param stanceId 当前架势ID
     * @param attackProgress 攻击进度 (0.0-1.0)
     * @return 世界空间中的碰撞箱
     */
    AABB getWorldHitbox(LivingEntity wielder, String stanceId, float attackProgress);

    /**
     * 是否启用动态碰撞箱
     */
    boolean isDynamic();

    /**
     * 获取碰撞箱的调试颜色（用于可视化）
     */
    default int getDebugColor() { return 0xFFFF0000; } // 默认红色
}
