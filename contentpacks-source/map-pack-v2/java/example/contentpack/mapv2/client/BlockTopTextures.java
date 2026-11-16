package example.contentpack.mapv2.client;

import com.mojang.math.Axis;
import example.contentpack.mapv2.FluidSurfaces;
import example.contentpack.mapv2.MapConfig;
import example.contentpack.mapv2.MapShade;
import example.contentpack.mapv2.MapStackLayer;
import example.contentpack.mapv2.MapStore;
import example.contentpack.mapv2.view.MapProjection;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.List;

/**
 * 最深几档缩放下的「方块顶面纹理」层。
 *
 * <p>地图瓦片按像素存了表面栈（{@link MapStore.StackSample#stack()}），每层带贴图 ID、UV 子矩形
 * 和格内投影；放大到每方块十几个像素时逐层铺上去，比一个纯色方块好看得多。
 *
 * <p><b>颜色不丢</b>：贴图用顶点色乘一次「瓦片记录的最终颜色 ÷ 贴图平均色」——
 * 于是纹理细节回来了，而群系 tint、山体阴影这些记录在瓦片里的信息也还成立；
 * 若贴图自带颜色（石头等），这个系数≈1，相当于原样画。
 *
 * <p><b>层序</b>：从表面栈最底下（终点）开始往上画，每一层按它记录的贴图子矩形
 * 和格内投影贴上去 —— 玻璃的边框、树叶的镂空、火把的 2×2 都保持原样；
 * 叠了几层（{@link MapStackLayer#count()}）会压暗一点，看得出厚度。
 * 记录的投影是旋转之前的本地矩形，再带一个 22.5° 一档的旋转（铁轨走向、中继器朝向、
 * 45° 斜放的告示牌）：先按本地矩形铺图形，再把整个格子绕中心顺时针转回去。
 *
 * <p><b>平色层</b>：{@code sprite} 为空的层没有图集贴图（生物头/玩家皮肤不在图集里），
 * 按记录的头顶平均色直接填矩形，同样跟着旋转和高度明暗。
 *
 * <p><b>只画有的</b>：底层平面色瓦片先画，这里只覆盖「有顶面贴图」的方块；
 * 十字模型没有朝上的一面，保持平面色。
 */
public final class BlockTopTextures {

    /**
     * 每方块至少 16 个 GUI 像素（{@code bpp <= 1/16}）才画纹理：
     * 再浅的档位一格塞不下 16×16 的贴图，只是多几万次 blit。
     */
    static final double MAX_BPP = 1.0 / 16.0;

    private BlockTopTextures() {
    }

    static boolean applies(double bpp) {
        return bpp <= MAX_BPP;
    }

    /** 贴图与模型都在资源重载时换了，缓存必须跟着清。 */
    static PreparableReloadListener reloadListener() {
        return new SimplePreparableReloadListener<Void>() {
            @Override
            protected Void prepare(ResourceManager manager, ProfilerFiller profiler) {
                return null;
            }

            @Override
            protected void apply(Void value, ResourceManager manager, ProfilerFiller profiler) {
                invalidate();
            }
        };
    }

    static void render(GuiGraphics graphics, int left, int top, int view,
                       ResourceLocation dimension, List<String> sampleOrder,
                       double minWorldX, double minWorldZ, double bpp,
                       int minY, int heightRange) {
        if (!applies(bpp) || sampleOrder.isEmpty()) {
            return;
        }
        int relief = MapConfig.terrainRelief();
        int minBlockX = (int) Math.floor(minWorldX);
        int minBlockZ = (int) Math.floor(minWorldZ);
        int maxBlockX = (int) Math.ceil(minWorldX + view * bpp);
        int maxBlockZ = (int) Math.ceil(minWorldZ + view * bpp);
        for (int blockZ = minBlockZ; blockZ <= maxBlockZ; blockZ++) {
            int py0 = top + MapProjection.pixel(blockZ, minWorldZ, bpp);
            int py1 = top + MapProjection.pixel(blockZ + 1, minWorldZ, bpp);
            if (py1 <= top || py0 >= top + view) {
                continue;
            }
            for (int blockX = minBlockX; blockX <= maxBlockX; blockX++) {
                MapStore.StackSample sample = MapStore.sampleStack(dimension, sampleOrder, blockX, blockZ);
                if (!sample.known() || sample.stack().isEmpty()) {
                    continue;
                }
                int px0 = left + MapProjection.pixel(blockX, minWorldX, bpp);
                int px1 = left + MapProjection.pixel(blockX + 1, minWorldX, bpp);
                if (px1 <= left || px0 >= left + view) {
                    continue;
                }
                int width = Math.max(1, px1 - px0);
                int height = Math.max(1, py1 - py0);
                List<MapStackLayer> stack = sample.stack();
                int baseIndex = stack.size() - 1;
                int westHeight = heightAt(dimension, sampleOrder, blockX - 1, blockZ);
                int northHeight = heightAt(dimension, sampleOrder, blockX, blockZ - 1);
                for (int i = baseIndex; i >= 0; i--) {
                    drawLayer(graphics, stack.get(i), sample, i == baseIndex,
                            px0, py0, width, height,
                            westHeight, northHeight, minY, heightRange, relief);
                }
            }
        }
    }

    /** 相邻方块高度（按叠层从高到低采样）；整条栈都未采集返回 {@link MapShade#NO_HEIGHT}。 */
    private static int heightAt(ResourceLocation dimension, List<String> sampleOrder,
                                int blockX, int blockZ) {
        MapStore.StackSample sample = MapStore.sampleStack(dimension, sampleOrder, blockX, blockZ);
        return sample.known() ? sample.height() : MapShade.NO_HEIGHT;
    }

    /**
     * 画表面栈里的一层，从最底下的终点层开始往上。
     *
     * <p>每层都用**自己记录的平色**做顶点色（不拿栈顶已混过叠层的瓦片色），
     * 再除以贴图子矩形平均色：于是群系 tint、高度阴影回来，纹理细节也回来。
     * 水按深度给 alpha；其余层交给贴图自身 alpha，上面的层再按层数压暗。
     * 整格整贴图的层走带色调的 blit（能带 alpha）；子矩形层按 UV 子区域贴，
     * 玻璃边框、树叶镂空、火把的 2×2 原样保留。
     */
    private static void drawLayer(GuiGraphics graphics, MapStackLayer layer,
                                  MapStore.StackSample sample, boolean bottom,
                                  int px0, int py0, int width, int height,
                                  int westHeight, int northHeight,
                                  int minY, int heightRange, int relief) {
        boolean flat = layer.sprite().isEmpty();
        TextureAtlasSprite sprite = flat ? null : BlockSurfaces.sprite(layer.sprite());
        if (!flat && sprite == null) {
            return;
        }
        // 流体层的透明度按深度记录（水面深度决定）；其余层交给贴图自己的 alpha ——
        // 树叶/玻璃的平均不透明度混了镂空，拿来当整体 alpha 会把实心像素洗成半透明。
        boolean fluid = FluidSurfaces.isFluidLayer(layer.blockId());
        float alpha = fluid && !bottom
                ? MapStackLayer.stackedAlpha(layer.alpha(), layer.count()) : 1f;
        if (alpha <= 0.02f) {
            return;
        }
        // 每层用自己记录的平色：底层不再用已混过叠层的瓦片色，避免透明层被算两遍
        float[] tint = flat
                ? shadeOf(layer.argb(), sample.height(), westHeight, northHeight,
                        minY, heightRange, relief)
                : tintOf(layer.argb(), sample.height(), westHeight, northHeight,
                        minY, heightRange, relief, sprite, layer);
        if (!bottom) {
            float density = MapStackLayer.density(layer.count());
            tint[0] *= density;
            tint[1] *= density;
            tint[2] *= density;
        }
        int rotation = layer.rotation();
        // 记录的是旋转之前的本地矩形：先按它铺图形，再把整个格子绕中心顺时针转
        // rotation×22.5°。铁轨走向、中继器朝向、斜放告示牌都只在这一步里。
        int dx0 = px0 + Math.round(width * layer.x0() / (float) MapStackLayer.UNITS);
        int dx1 = px0 + Math.round(width * layer.x1() / (float) MapStackLayer.UNITS);
        int dy0 = py0 + Math.round(height * layer.z0() / (float) MapStackLayer.UNITS);
        int dy1 = py0 + Math.round(height * layer.z1() / (float) MapStackLayer.UNITS);
        if (dx1 <= dx0 || dy1 <= dy0) {
            return;
        }
        if (rotation != 0) {
            float cx = px0 + width * 0.5f;
            float cy = py0 + height * 0.5f;
            graphics.pose().pushPose();
            graphics.pose().translate(cx, cy, 0f);
            graphics.pose().mulPose(Axis.ZP.rotationDegrees(rotation * 22.5f));
            graphics.pose().translate(-cx, -cy, 0f);
        }
        if (flat) {
            int color = Math.round(alpha * 255f) << 24
                    | Math.round(tint[0] * 255f) << 16
                    | Math.round(tint[1] * 255f) << 8
                    | Math.round(tint[2] * 255f);
            graphics.fill(dx0, dy0, dx1, dy1, color);
        } else if (layer.fullFootprint() && layer.fullUv()) {
            graphics.blit(px0, py0, 0, width, height, sprite,
                    tint[0], tint[1], tint[2], alpha);
        } else {
            int spriteW = Math.max(1, sprite.contents().width());
            int spriteH = Math.max(1, sprite.contents().height());
            int uPx = Math.round(layer.u0() / (float) MapStackLayer.UV_UNITS * spriteW);
            int vPx = Math.round(layer.v0() / (float) MapStackLayer.UV_UNITS * spriteH);
            int uW = Math.max(1,
                    Math.round((layer.u1() - layer.u0()) / (float) MapStackLayer.UV_UNITS * spriteW));
            int vH = Math.max(1,
                    Math.round((layer.v1() - layer.v0()) / (float) MapStackLayer.UV_UNITS * spriteH));
            // 图集子矩形没有带色 blit 重载：把同一套顶点色挂到 GUI 着色器上再复位。
            // 少了这一步，火把的 2×2、红石粉的连线、玻璃板的窄条会按贴图原色画 ——
            // 红石粉的线贴图是白色，中继器的火把反而会保留红色，和下面整格的黑对不上。
            // 贴图可能在别的图集里（床在 beds.png），必须用 sprite 自己的图集。
            graphics.setColor(tint[0], tint[1], tint[2], alpha);
            graphics.blit(sprite.atlasLocation(), dx0, dy0, dx1 - dx0, dy1 - dy0,
                    (float) (sprite.getX() + uPx), (float) (sprite.getY() + vPx), uW, vH,
                    BlockSurfaces.atlasWidth(sprite), BlockSurfaces.atlasHeight(sprite));
            graphics.setColor(1.0f, 1.0f, 1.0f, 1.0f);
        }
        if (rotation != 0) {
            graphics.pose().popPose();
        }
    }

    /**
     * 顶点色 = 记录色 ÷ 贴图平均色（让「贴图均值」回到地图上原本的颜色）。
     * 某个通道会超过 1 时整体等比压暗，避免偏色。纯运算在 {@link MapStackLayer#vertexTint}。
     */
    private static float[] tintOf(int argb, int height, int westHeight, int northHeight,
                                  int minY, int heightRange, int relief,
                                  TextureAtlasSprite sprite, MapStackLayer layer) {
        float[] shaded = shadeOf(argb, height, westHeight, northHeight, minY, heightRange, relief);
        float[] avg = BlockSurfaces.avgColor(sprite, layer.u0(), layer.v0(),
                layer.u1(), layer.v1());
        return MapStackLayer.vertexTint(shaded[0], shaded[1], shaded[2],
                avg[0], avg[1], avg[2]);
    }

    /** 记录色 → 高度阴影 + 邻格起伏后的 0..1 三通道；平色层直接用它，不再除贴图均值。 */
    private static float[] shadeOf(int argb, int height, int westHeight, int northHeight,
                                   int minY, int heightRange, int relief) {
        int shaded = MapShade.applyShade(argb, height, minY, heightRange, 0);
        shaded = MapShade.applyRelief(shaded, height, westHeight, northHeight, 1, relief);
        return new float[]{
                ((shaded >> 16) & 0xFF) / 255f,
                ((shaded >> 8) & 0xFF) / 255f,
                (shaded & 0xFF) / 255f};
    }

    /** 换世界：方块 ID 一样但模型/贴图可能来自不同资源环境。 */
    public static void invalidate() {
        BlockSurfaces.invalidate();
    }
}
