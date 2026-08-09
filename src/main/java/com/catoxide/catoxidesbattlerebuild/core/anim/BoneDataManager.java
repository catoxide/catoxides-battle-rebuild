package com.catoxide.catoxidesbattlerebuild.core.anim;

import cn.solarmoon.spark_core.event.BoneUpdateEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 骨骼数据缓存（服务端+客户端共用）
 * <p>存储每个骨骼的 pivot 世界坐标和变换矩阵，供 OBB 受击盒判定和调试渲染使用。
 *
 * <h3>数据来源</h3>
 * <ul>
 *   <li>服务端：通过 {@link #onBoneUpdate(BoneUpdateEvent)} 从 Spark-Core 骨骼事件拉取</li>
 *   <li>客户端：通过 {@link #updateFromClient(Map)} 从同步包或 BonePose 直接获取</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * 数据在主线程 tick 中读写，无需同步。
 */
public final class BoneDataManager {

    /** 骨骼 pivot 世界坐标（用于快速距离判定） */
    private final Map<String, Vector3f> bonePositions = new HashMap<>();
    /** 骨骼 pivot 世界变换矩阵（含旋转，用于 OBB 构造） */
    private final Map<String, Matrix4f> boneMatrices = new HashMap<>();

    private long lastUpdateTime = 0;

    /**
     * 服务端：从 BoneUpdateEvent 拉取骨骼数据
     * @param event   骨骼事件
     * @param gameTime 当前游戏刻（用于时间戳）
     */
    public void onBoneUpdate(BoneUpdateEvent event, long gameTime) {
        String boneName = event.getBonePose().getName();
        Vector3f worldPos = event.getBonePose().getWorldBonePivot(net.minecraft.world.phys.Vec3.ZERO, 1.0f);
        Matrix4f worldMat = event.getBonePose().getWorldBonePivotMatrix(1.0f);
        bonePositions.put(boneName, worldPos);
        boneMatrices.put(boneName, worldMat);
        lastUpdateTime = gameTime;
    }

    /**
     * 客户端：从同步数据更新位置（每 tick 全量替换。
     * 位置是绝对浮点坐标，必须全量同步保证精度，不做差值/增量编码）。
     * @param positions 骨骼位置 map
     * @param gameTime   当前游戏刻
     */
    public void updateFromClient(Map<String, Vector3f> positions, long gameTime) {
        bonePositions.clear();
        bonePositions.putAll(positions);
        lastUpdateTime = gameTime;
    }

    /**
     * 客户端：从 BonePose 矩阵直接更新（用于调试渲染，避免服务端同步延迟）
     */
    public void updateMatrixFromClient(String boneName, Matrix4f mat) {
        boneMatrices.put(boneName, mat);
    }

    public Vector3f getPosition(String boneName) {
        return bonePositions.get(boneName);
    }

    public Matrix4f getMatrix(String boneName) {
        return boneMatrices.get(boneName);
    }

    public Map<String, Vector3f> getPositions() {
        return Collections.unmodifiableMap(bonePositions);
    }

    public Map<String, Matrix4f> getMatrices() {
        return Collections.unmodifiableMap(boneMatrices);
    }

    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    public int getBoneCount() {
        return bonePositions.size();
    }

    public void clear() {
        bonePositions.clear();
        boneMatrices.clear();
    }
}
