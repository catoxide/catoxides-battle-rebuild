package com.catoxide.catoxidesbattlerebuild.core.sable;

/**
 * sub-level 姿态的纯数据表示（防腐层值类型）
 * <p>对应 sable 的 {@code Pose3dc}（position + orientation + rotationPoint + scale）。
 * <b>不包含任何 sable 类型</b>——sable 对象只出现在 sable-base contentpack 适配器内部，
 * 主 mod 与其他内容包只认这个 record。sable API 再怎么变，这些数学量不变。
 *
 * @param posX/posY/posZ        位置（世界坐标）
 * @param quatX/Y/Z/W           朝向（单位四元数）
 * @param scaleX/Y/Z            缩放
 * @param rotPointX/Y/Z         旋转中心（局部坐标）
 */
public record PoseData(
        double posX, double posY, double posZ,
        double quatX, double quatY, double quatZ, double quatW,
        double scaleX, double scaleY, double scaleZ,
        double rotPointX, double rotPointY, double rotPointZ
) {

    public static final PoseData IDENTITY = new PoseData(
            0, 0, 0,
            0, 0, 0, 1,
            1, 1, 1,
            0, 0, 0
    );
}
