package com.catoxide.catoxidesbattlerebuild.core.sable;

import java.util.UUID;

/**
 * sub-level 信息（防腐层值类型）
 * <p>主 mod 对「可移动物理结构」的不透明表示：ID + 名称 + 姿态。
 * 由 sable-base contentpack 适配器从 sable 的 SubLevel 对象转换而来。
 */
public record SubLevelInfo(
        UUID id,
        String name,
        PoseData pose
) {}
