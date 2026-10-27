package com.catoxide.catoxidesbattlerebuild.core.map;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 地图坐标解析器：把「非普通世界空间」的点与地图/标点系统对接。
 *
 * <p>典型场景是 Sable sub-level：方块存在 plot 局部坐标里，方块的
 * {@code BlockHitResult} 也返回局部坐标；玩家/标记需要的是当前姿态下的世界坐标。
 * 本接口让这类信息只存在于对应内容包，地图包保持无 mod 依赖。
 *
 * <p>只在客户端有实现；所有方法都可能被每帧调用，必须廉价。
 */
public interface MapPointResolver {

    /**
     * 标点创建时把原始点转换成世界坐标，并可在 {@code anchorData} 里写入锚点。
     *
     * @param level      当前客户端世界
     * @param raw        原始点。命中方块时可能是该空间的局部坐标；无命中/地图点击时是世界坐标
     * @param hit        触发点（{@code BlockHitResult} 表示准星命中方块；{@code null} 表示地图点击）
     * @param anchorData 标点扩展载荷，写入内容会随标点保存/同步
     * @return 世界坐标；返回 {@code null} 表示本解析器不处理
     */
    @Nullable
    Vec3 toWorld(Level level, Vec3 raw, @Nullable HitResult hit, CompoundTag anchorData);

    /**
     * 标点渲染时把存储的锚点解析成当前世界坐标。
     *
     * @return 世界坐标；{@code null} 表示无法解析（调用方回退到存储的 x/y/z）
     */
    @Nullable
    Vec3 resolve(Level level, double x, double y, double z,
                 CompoundTag anchorData, float partialTick);

    /**
     * 在「非普通世界空间」里打射线（例如 Sable sub-level）。
     *
     * <p>普通 {@code level.clip} 看不到 sub-level 的方块，射线会直接穿过去。
     * 返回的 {@link HitResult} 可以带局部坐标，随后由 {@link #toWorld} 转换。
     *
     * @return 命中；{@code null} 表示本解析器没有命中/不处理
     */
    @Nullable
    default HitResult raycast(Level level, Vec3 from, Vec3 to, Entity source) {
        return null;
    }

    /**
     * 世界 (x,z) 处可站立表面的 y；{@code null} 表示本解析器没有覆盖该位置。
     */
    @Nullable
    Double surfaceY(Level level, int x, int z);

    /**
     * 该方块位置是否属于「不应当被普通地图采集」的分离空间（如 plot 中的船体）。
     */
    default boolean isDetached(Level level, BlockPos pos) {
        return false;
    }
}
