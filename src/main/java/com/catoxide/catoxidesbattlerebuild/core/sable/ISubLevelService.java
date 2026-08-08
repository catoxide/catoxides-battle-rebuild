package com.catoxide.catoxidesbattlerebuild.core.sable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * sub-level 查询服务（防腐层稳定接口）
 * <p>主 mod 与其他内容包通过本接口与「可移动物理结构」（sable sub-level）交互，
 * <b>不接触任何 sable 类型</b>。sable-base contentpack 适配器实现本接口，
 * 把 sable 的 SubLevel 对象转换为 {@link SubLevelInfo}。
 *
 * <p>坐标转换数学内建于 default 实现（基于 {@link PoseData}，纯 JOML），
 * 适配器只需实现 {@link #getSubLevelAt} 与「从 sable 对象提取 PoseData」。
 */
public interface ISubLevelService {

    /**
     * 查询世界坐标落在哪个 sub-level（不在任何 sub-level 返回 null）。
     */
    @Nullable
    SubLevelInfo getSubLevelAt(Level level, BlockPos worldPos);

    /** 世界坐标 → sub-level 局部坐标 */
    default Vec3 worldToLocal(SubLevelInfo sub, Vec3 worldPos) {
        PoseData p = sub.pose();
        // 1. 平移
        Vector3d v = new Vector3d(worldPos.x - p.posX(), worldPos.y - p.posY(), worldPos.z - p.posZ());
        // 2. 逆旋转（共轭四元数）
        Quaterniond inv = new Quaterniond(p.quatX(), p.quatY(), p.quatZ(), p.quatW()).conjugate();
        v.rotate(inv);
        // 3. 逆缩放 + 旋转中心
        return new Vec3(
                p.rotPointX() + v.x / p.scaleX(),
                p.rotPointY() + v.y / p.scaleY(),
                p.rotPointZ() + v.z / p.scaleZ()
        );
    }

    /** sub-level 局部坐标 → 世界坐标 */
    default Vec3 localToWorld(SubLevelInfo sub, Vec3 localPos) {
        PoseData p = sub.pose();
        // 1. 绕旋转中心缩放
        Vector3d v = new Vector3d(
                (localPos.x - p.rotPointX()) * p.scaleX(),
                (localPos.y - p.rotPointY()) * p.scaleY(),
                (localPos.z - p.rotPointZ()) * p.scaleZ()
        );
        // 2. 旋转
        v.rotate(new Quaterniond(p.quatX(), p.quatY(), p.quatZ(), p.quatW()));
        // 3. 平移到世界位置
        return new Vec3(p.posX() + v.x, p.posY() + v.y, p.posZ() + v.z);
    }
}
