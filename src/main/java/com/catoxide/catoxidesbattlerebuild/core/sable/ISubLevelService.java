package com.catoxide.catoxidesbattlerebuild.core.sable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.List;
import java.util.UUID;

/**
 * sub-level 查询服务（防腐层稳定接口）
 * <p>主 mod 与其他内容包通过本接口与「可移动物理结构」（sable sub-level）交互，
 * <b>不接触任何 sable 类型</b>。sable-base contentpack 适配器实现本接口，
 * 把 sable 的 SubLevel 对象转换为 {@link SubLevelInfo}。
 *
 * <p>坐标转换数学内建于 default 实现（基于 {@link PoseData}，纯 JOML），
 * 适配器只需实现查询与「从 sable 对象提取 PoseData / 方块」。
 */
public interface ISubLevelService {

    /**
     * 查询世界坐标落在哪个 sub-level（不在任何 sub-level 返回 null）。
     */
    @Nullable
    SubLevelInfo getSubLevelAt(Level level, BlockPos worldPos);

    // ==================== 客户端只读扩展 ====================

    /**
     * 当前维度已加载的全部 sub-level（客户端为已同步到本地的）。
     */
    default List<SubLevelInfo> getSubLevels(Level level) {
        return List.of();
    }

    /**
     * 按 UUID 找当前维度已加载的 sub-level。
     */
    @Nullable
    default SubLevelInfo getSubLevel(Level level, UUID id) {
        return null;
    }

    /**
     * 按 <b>plot 局部坐标</b> 查询所在的 sub-level。
     * <p>用于处理 sable 方块射线返回的局部坐标命中。
     */
    @Nullable
    default SubLevelInfo getSubLevelContainingLocal(Level level, Vec3 localPos) {
        return null;
    }

    /**
     * 读取 sub-level 中某个局部方块的方块状态；未加载/越界返回空气。
     *
     * @param x/y/z plot 局部方块坐标
     */
    default BlockState getBlockState(Level level, SubLevelInfo sub, int x, int y, int z) {
        return Blocks.AIR.defaultBlockState();
    }

    /**
     * 某局部列最高非空气方块的 y；整列为空返回 {@link Integer#MIN_VALUE}。
     */
    default int topBlockY(Level level, SubLevelInfo sub, int x, int z) {
        return Integer.MIN_VALUE;
    }

    // ==================== 坐标数学 ====================

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
