package com.catoxide.catoxidesbattlerebuild.core.hitbox;

import org.joml.Vector3f;

/**
 * 单骨骼 OBB 受击盒配置
 * <p>用于构造骨骼级有向包围盒，支持精确命中判定。
 * <p>数据来源：
 * <ul>
 *   <li>默认：从 Bedrock geo.json 的 bones[].cubes 自动提取</li>
 *   <li>覆盖：从 hitbox.json 手动指定</li>
 * </ul>
 *
 * @param boneName     骨骼名（对应 geo.json 中的 bone name）
 * @param localOffset  从 pivot 到 cube 中心的偏移（局部空间，米，已含 Bedrock→MC X 翻转）
 * @param halfExtents  半尺寸（局部空间，米）
 * @param armor        装甲厚度（0=无装甲，用于调试渲染不同颜色，TODO 后续接入）
 */
public record HitboxConfig(
        String boneName,
        Vector3f localOffset,
        Vector3f halfExtents,
        float armor
) {
    /**
     * 简化构造（无装甲）
     */
    public HitboxConfig(String boneName, Vector3f localOffset, Vector3f halfExtents) {
        this(boneName, localOffset, halfExtents, 0f);
    }
}
