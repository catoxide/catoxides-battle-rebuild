package example.contentpack.mapv2;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 流体表面策略的登记表：采集时按流体标签挑一个 {@link FluidSurface}。
 *
 * <p>内置水（半透明、浅水看底、群系色）和岩浆（不透明、一层就是终点、JSON 里的
 * {@code minecraft:lava} 颜色）。按登记顺序匹配；未登记的流体返回 {@code null}，
 * 采集端按旧口径当终点、不记流体层。
 */
public final class FluidSurfaces {

    /** 水：半透明，最多看穿 3 格，1/2/3 格不透明度 0.45/0.65/0.80。 */
    public static final FluidSurface WATER = new FluidSurface() {
        @Override
        public boolean matches(FluidState fluid) {
            return fluid.is(FluidTags.WATER);
        }

        @Override
        public String layerId() {
            return "minecraft:water";
        }

        @Override
        public String sprite() {
            return "minecraft:block/water_still";
        }

        @Override
        public boolean opaque() {
            return false;
        }

        @Override
        public int shallowDepth() {
            return 3;
        }

        @Override
        public float alpha(int depth) {
            return switch (Math.max(1, Math.min(shallowDepth(), depth))) {
                case 1 -> 0.45f;
                case 2 -> 0.65f;
                default -> 0.80f;
            };
        }

        @Override
        public int color(ClientLevel level, BlockPos pos) {
            return MapColors.colorFor(Blocks.WATER.defaultBlockState(), level, pos);
        }

        @Override
        public int depthSign() {
            return 1;
        }
    };

    /** 岩浆：不透明，一层就是终点，不做浅层透底；流动层级也照样记表面层。 */
    public static final FluidSurface LAVA = new FluidSurface() {
        @Override
        public boolean matches(FluidState fluid) {
            return fluid.is(FluidTags.LAVA);
        }

        @Override
        public String layerId() {
            return "minecraft:lava";
        }

        @Override
        public String sprite() {
            return "minecraft:block/lava_still";
        }

        @Override
        public boolean opaque() {
            return true;
        }

        @Override
        public int shallowDepth() {
            return 0;
        }

        @Override
        public float alpha(int depth) {
            return 1f;
        }

        @Override
        public int color(ClientLevel level, BlockPos pos) {
            return MapColors.colorFor(Blocks.LAVA.defaultBlockState(), level, pos);
        }

        @Override
        public int depthSign() {
            return -1;
        }
    };

    private static final Map<String, FluidSurface> REGISTERED = new LinkedHashMap<>();

    static {
        register(WATER);
        register(LAVA);
    }

    private FluidSurfaces() {
    }

    public static void register(FluidSurface surface) {
        REGISTERED.put(surface.layerId(), surface);
    }

    /** 这一格的流体归哪种策略管；没有登记返回 {@code null}。 */
    public static FluidSurface of(FluidState fluid) {
        for (FluidSurface surface : REGISTERED.values()) {
            if (surface.matches(fluid)) {
                return surface;
            }
        }
        return null;
    }

    /** 表面栈里这一层的 blockId 是不是流体层（渲染端靠它认「alpha 是流体不透明度」）。 */
    public static boolean isFluidLayer(String blockId) {
        return blockId != null && REGISTERED.containsKey(blockId);
    }

    /**
     * 按顶面贴图反查流体。炼药锅这类方块自己有流体外观、但 {@code getFluidState()}
     * 是空的（没有 WATERLOGGED、也不含水），只能从模型里那张水面/岩浆面认出来。
     */
    public static FluidSurface ofSprite(String spriteId) {
        if (spriteId == null) {
            return null;
        }
        for (FluidSurface surface : REGISTERED.values()) {
            if (surface.sprite().equals(spriteId)) {
                return surface;
            }
        }
        return null;
    }
}
