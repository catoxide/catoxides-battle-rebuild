package example.contentpack.map;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 世界空间朝向。
 *
 * <p>地图/罗盘原来用 {@code entity.getYRot()}；在 Sable 的 sub-level 上，
 * 那是船体局部 yaw。{@code getViewVector()} 经过 sable 的
 * {@code calculateViewVector} mixin 变换，拿到的是屏幕/世界方向，
 * 因此所有 HUD 朝向统一从这里取。
 */
public final class MapView {

    private MapView() {
    }

    /** 世界 yaw（MC 约定：0 = 南 +Z，90 = 西 -X）。 */
    public static float yaw(Entity entity, float partialTick) {
        Vec3 view = entity.getViewVector(partialTick);
        return (float) Math.toDegrees(Math.atan2(-view.x, view.z));
    }

    /** 世界 pitch（正 = 向下）。 */
    public static float pitch(Entity entity, float partialTick) {
        Vec3 view = entity.getViewVector(partialTick);
        return (float) -Math.toDegrees(Math.asin(Mth.clamp(view.y, -1.0, 1.0)));
    }
}
