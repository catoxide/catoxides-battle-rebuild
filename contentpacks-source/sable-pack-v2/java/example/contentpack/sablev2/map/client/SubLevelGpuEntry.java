package example.contentpack.sablev2.map.client;

import com.catoxide.catoxidesbattlerebuild.core.sable.PoseData;
import com.catoxide.catoxidesbattlerebuild.core.sable.SubLevelBounds;
import com.catoxide.catoxidesbattlerebuild.core.sable.SubLevelInfo;
import com.mojang.blaze3d.platform.NativeImage;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import example.contentpack.sablev2.map.TopDownView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * 一艘船的离屏俯视图：FBO（GPU 渲染）→ NativeImage（回读）→ DynamicTexture（贴图）。
 *
 * <p>相机固定在世界正上方、跟随船体中心平移，所以平移不需要重渲，姿态变化才需要。
 * 纹理只在「地图上可见」且「过期/脏」时才重渲；包围盒或 {@code maxTexture}
 * 变化会重建资源。
 */
final class SubLevelGpuEntry {

    private static final int REFRESH_TICKS = 100;

    /** 世界相机固定后，姿态变化会改变纹理内容；小于 0.5° 不重渲。 */
    private static final double ATTITUDE_EPSILON_COS = Math.cos(Math.toRadians(0.5));

    private final UUID id;
    private SubLevelInfo info;
    private TopDownView view;

    private SimulatedDiagramBridge.Fbo fbo;
    private NativeImage image;
    private DynamicTexture texture;
    private ResourceLocation location;
    private ByteBuffer readBuffer;
    private int imageWidth;
    private int imageHeight;

    private long lastRenderedTick = Long.MIN_VALUE / 2;
    private boolean rendered;
    private boolean requested;
    private boolean dirty = true;

    SubLevelGpuEntry(SubLevelInfo info, int maxTexture) {
        this.id = info.id();
        this.info = info;
        this.update(info, maxTexture);
    }

    UUID id() {
        return this.id;
    }

    SubLevelInfo info() {
        return this.info;
    }

    TopDownView view() {
        return this.view;
    }

    int imageWidth() {
        return this.imageWidth;
    }

    int imageHeight() {
        return this.imageHeight;
    }

    boolean hasTexture() {
        // 包围盒刚变化、资源还没重建时不要用旧尺寸去拉伸
        return this.rendered && this.location != null && this.view != null
                && this.imageWidth == this.view.imageWidth()
                && this.imageHeight == this.view.imageHeight();
    }

    ResourceLocation textureLocation() {
        return this.location;
    }

    /** 更新姿态/包围盒；返回是否有需要重渲的变化。 */
    boolean update(SubLevelInfo next, int maxTexture) {
        boolean turned = attitudeChanged(this.info.pose(), next.pose());
        this.info = next;
        SubLevelBounds bounds = next.bounds();
        if (bounds.isEmpty()) {
            this.view = null;
            return false;
        }
        TopDownView nextView = TopDownView.of(bounds, maxTexture);
        if (nextView.equals(this.view)) {
            // 平移不影响图（相机跟着船心走），只重新排队姿态变化
            if (turned) {
                this.dirty = true;
                return true;
            }
            return false;
        }
        this.view = nextView;
        this.dirty = true;
        return true;
    }

    private static boolean attitudeChanged(PoseData from, PoseData to) {
        double dot = from.quatX() * to.quatX() + from.quatY() * to.quatY()
                + from.quatZ() * to.quatZ() + from.quatW() * to.quatW();
        return Math.abs(dot) < ATTITUDE_EPSILON_COS;
    }

    /** 叠加层每次画到该船时调用；只有到期/脏/从未渲染才排进渲染队列。 */
    void request(long tick) {
        if (this.requested || this.view == null) {
            return;
        }
        if (!this.rendered || this.dirty || tick - this.lastRenderedTick >= REFRESH_TICKS) {
            this.requested = true;
        }
    }

    boolean isRequested() {
        return this.requested;
    }

    void clearRequest() {
        this.requested = false;
    }

    /** 标记需要重渲（方块变化/包围盒变化）；下一次可见时生效。 */
    void markDirty() {
        this.dirty = true;
    }

    /** 在渲染线程执行：确保资源 → 离屏渲染 → 回读 → 上传。 */
    void render(ClientLevel level, ClientSubLevel sub, long tick, float partialTick) {
        if (this.view == null) {
            this.requested = false;
            return;
        }
        if (!this.ensureResources()) {
            this.requested = false;
            return;
        }
        if (!SimulatedDiagramBridge.render(this.fbo, level, sub, this.view, partialTick)) {
            this.requested = false;
            return;
        }
        if (!SimulatedDiagramBridge.read(this.fbo, this.image, this.readBuffer)) {
            this.requested = false;
            return;
        }
        this.texture.upload();
        this.rendered = true;
        this.dirty = false;
        this.requested = false;
        this.lastRenderedTick = tick;
    }

    private boolean ensureResources() {
        int width = this.view.imageWidth();
        int height = this.view.imageHeight();
        if (this.fbo != null && this.image != null && this.location != null
                && this.imageWidth == width && this.imageHeight == height) {
            return true;
        }
        this.releaseResources();

        this.fbo = SimulatedDiagramBridge.create(width, height,
                "sable_map_ship_" + this.id.toString().replace("-", ""));
        if (this.fbo == null) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        this.image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        this.texture = new DynamicTexture(this.image);
        this.location = minecraft.getTextureManager().register(
                "sable_map_ship_" + this.id.toString().replace("-", ""), this.texture);
        this.readBuffer = ByteBuffer.allocateDirect(width * height * 4);
        this.imageWidth = width;
        this.imageHeight = height;
        return true;
    }

    void release() {
        this.releaseResources();
        this.rendered = false;
        this.requested = false;
        this.dirty = true;
        this.lastRenderedTick = Long.MIN_VALUE / 2;
    }

    private void releaseResources() {
        if (this.location != null) {
            // TextureManager.release → DynamicTexture.close() → NativeImage.close()
            Minecraft.getInstance().getTextureManager().release(this.location);
        }
        SimulatedDiagramBridge.close(this.fbo);
        this.location = null;
        this.texture = null;
        this.image = null;
        this.fbo = null;
        this.readBuffer = null;
        this.imageWidth = 0;
        this.imageHeight = 0;
    }
}
