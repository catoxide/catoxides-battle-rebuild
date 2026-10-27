package example.contentpack.map.marker;

import net.minecraft.resources.ResourceLocation;

/** 标记的纯规则（无运行时依赖，便于自检）。 */
public final class MarkerRules {

    private MarkerRules() {
    }

    /** 服务端广播过滤：维度 + 距离。 */
    public static boolean visibleTo(ResourceLocation playerDimension,
                                    ResourceLocation markerDimension,
                                    double distanceSq, int range, boolean allDimensions) {
        if (!allDimensions && !playerDimension.equals(markerDimension)) {
            return false;
        }
        if (range > 0) {
            return playerDimension.equals(markerDimension)
                    && distanceSq <= (double) range * range;
        }
        return true;
    }

    public static boolean expired(long nowMillis, long expiresAtMillis) {
        return expiresAtMillis > 0 && nowMillis > expiresAtMillis;
    }
}
