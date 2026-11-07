package example.contentpack.sablev2.map;

import com.catoxide.catoxidesbattlerebuild.core.sable.SubLevelBounds;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;

/**
 * 世界空间的正俯视正交相机（纯数学，不碰 GL / sable）。
 *
 * <p>相机方向固定为世界正上方朝下（屏幕右 = 世界 +X，屏幕上 = 世界 -Z），
 * 不跟着船体姿态转；渲染时只把相机放到船体包围盒中心的世界位置，再由
 * {@code renderGroup} 按各 sub-level 的真实姿态绘制。这样贴到地图上的图
 * 和地形是同一个观察方向。
 *
 * <p>视口是正方形，边长取包围盒的 3D 外接球直径：与 yaw/roll/pitch 无关，
 * 所以船转向/摇晃不会导致纹理尺寸反复重建，只需重渲。每方块最多
 * {@link #MAX_PIXELS_PER_BLOCK} 像素，超出 {@code maxTexture} 预算才降采样；
 * 步长 {@code stepX = diameter / imageWidth} 由叠加层用于换算世界尺寸。
 */
public record TopDownView(
        int imageWidth,
        int imageHeight,
        double stepX,
        double stepZ,
        double diameter,
        Matrix4f projection,
        Vector3d localCenter,
        float cameraLift,
        Quaternionf orientation
) {

    private static final float NEAR = 0.05f;

    /** 外接球之外再留一圈，避免倾斜时边缘被裁。 */
    private static final double PADDING = 2.0;

    /** 每方块最多采样多少纹理像素；与地图最深缩放档的顶面纹理密度对齐。 */
    static final double MAX_PIXELS_PER_BLOCK = 16.0;

    public static TopDownView of(SubLevelBounds bounds, int maxTexture) {
        double width = bounds.maxX() - bounds.minX() + 1;
        double depth = bounds.maxZ() - bounds.minZ() + 1;
        double height = bounds.maxY() - bounds.minY() + 1;

        // 3D 外接球直径：任何姿态下船都落在球内，视口尺寸因此与旋转无关
        double radius = 0.5 * Math.sqrt(width * width + depth * depth + height * height);
        double diameter = radius * 2.0 + PADDING;

        double scale = Math.min(MAX_PIXELS_PER_BLOCK, maxTexture / diameter);
        int imageSize = Math.max(1, (int) Math.round(diameter * scale));
        double step = diameter / imageSize;
        double half = diameter / 2.0;

        Vector3d center = new Vector3d(
                bounds.minX() + width / 2.0,
                bounds.minY() + height / 2.0,
                bounds.minZ() + depth / 2.0);

        // 世界朝下：视空间 +Y → 世界 -Z（屏幕上 = 北），视空间 +Z → 世界 +Y（相机在上方）
        Quaternionf orientation = new Quaternionf().rotationX((float) (-Math.PI / 2.0));
        // 相机放在外接球顶再抬 PADDING/2，避免球顶恰好落在近裁面上被切掉
        float cameraLift = (float) (radius + PADDING / 2.0);

        // 视口边长必须与按直径算出的步长一致，否则贴图会被拉伸
        Matrix4f projection = new Matrix4f().ortho(
                (float) -half, (float) half,
                (float) -half, (float) half,
                NEAR, (float) (diameter + PADDING));

        return new TopDownView(imageSize, imageSize, step, step, diameter,
                projection, center, cameraLift, orientation);
    }
}
