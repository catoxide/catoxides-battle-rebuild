package example.contentpack.mapv2;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.FluidState;

/**
 * 一种流体的「表面」策略：平色从哪来、表面栈里那一层怎么记、多深还看得穿。
 *
 * <p>水是半透明的：列深不超过 {@link #shallowDepth()} 格时记一层半透明水继续采水底，
 * 更深就按流体色收尾；岩浆不透明：一层就是终点。采集端只向这个接口要参数，
 * 不再在列扫描里写死「water」。
 *
 * <p>加新流体：实现本接口，用 {@link FluidSurfaces#register} 登记。{@code layerId}
 * 是表面栈里那一层的 blockId，渲染端靠它认「这一层的 alpha 是流体不透明度」。
 */
public interface FluidSurface {

    /** 这一格的流体是不是它负责的（按标签判断）。 */
    boolean matches(FluidState fluid);

    /** 表面栈里这一层的 blockId（例如 {@code minecraft:water}）。 */
    String layerId();

    /** 流体表面的贴图。 */
    String sprite();

    /** 不透明的流体一层就是终点；半透明的可以继续看它下面。 */
    boolean opaque();

    /** 还能看穿的列深上限（格）；超过就按流体色收尾。不透明流体返回 0。 */
    int shallowDepth();

    /** 列深 {@code depth} 时这一层的平均不透明度（0..1）。 */
    float alpha(int depth);

    /** 流体表面的平色（ARGB）：JSON 覆盖、群系色都从 {@link MapColors} 来。 */
    int color(ClientLevel level, BlockPos pos);

    /** {@link MapShade} 的流体符号：正数按水压暗，负数按岩浆提亮。 */
    int depthSign();
}
