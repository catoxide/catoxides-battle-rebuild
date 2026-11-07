package example.contentpack.sablev2.map.client;

import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.mojang.blaze3d.platform.NativeImage;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import example.contentpack.sablev2.map.TopDownView;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Collection;

/**
 * 反射桥：调用 simulated 的整船渲染 + Veil 的离屏 FBO，不引入编译期依赖。
 *
 * <p>simulated 只在 Create: Aeronautics 的捆绑 jar 里，Veil 又由 sable 嵌套提供，
 * 两者都不是本包的编译依赖。这里把用到的两三个入口按名字取一次并缓存；
 * 类缺失或签名变化时只让图解图层 shutdown（见 {@link #available()}），不影响防腐服务。
 *
 * <p>只在渲染线程调用；{@code renderGroup} 自己负责绑定/恢复相机、投影与 FBO。
 */
public final class SimulatedDiagramBridge {

    private static final String TAG = "SableDiagram";
    private static final String RENDERER_CLASS =
            "dev.simulated_team.simulated.util.SimpleSubLevelGroupRenderer";
    private static final String FBO_CLASS =
            "foundry.veil.api.client.render.framebuffer.AdvancedFbo";

    private static boolean probed;
    private static boolean available;
    private static boolean failed;

    private static Method getRenderedChain;
    private static Method renderGroup;
    private static Method withSize;
    private static Method addColorTextureBuffer;
    private static Method setDepthTextureBuffer;
    private static Method setName;
    private static Method build;
    private static Method bind;
    private static Method clear;
    private static Method unbind;

    private SimulatedDiagramBridge() {
    }

    public static boolean available() {
        probe();
        return available && !failed;
    }

    private static synchronized void probe() {
        if (probed) {
            return;
        }
        probed = true;
        try {
            Class<?> fbo = Class.forName(FBO_CLASS);
            Class<?> builder = Class.forName(FBO_CLASS + "$Builder");
            Class<?> renderer = Class.forName(RENDERER_CLASS);

            withSize = fbo.getMethod("withSize", int.class, int.class);
            addColorTextureBuffer = builder.getMethod("addColorTextureBuffer");
            setDepthTextureBuffer = builder.getMethod("setDepthTextureBuffer");
            setName = builder.getMethod("setName", String.class);
            build = builder.getMethod("build", boolean.class);
            bind = fbo.getMethod("bind", boolean.class);
            clear = fbo.getMethod("clear");
            unbind = fbo.getMethod("unbind");

            getRenderedChain = renderer.getMethod("getRenderedChain", ClientSubLevel.class);
            renderGroup = renderer.getMethod("renderGroup",
                    ClientLevel.class, Collection.class, fbo,
                    Matrix4f.class, Matrix4f.class, Vector3d.class, Quaternionf.class,
                    float.class, boolean.class);
            available = true;
            LogManager.clientInfo(TAG, "simulated 图解渲染入口已就绪（反射）");
        } catch (Throwable t) {
            available = false;
            LogManager.clientWarn(TAG, "simulated/Veil 图解渲染入口不可用: {}", t.toString());
        }
    }

    /** 创建并清空一张带深度缓冲的离屏图；必须在渲染线程调用。 */
    @Nullable
    static Fbo create(int width, int height, String label) {
        if (!available()) {
            return null;
        }
        try {
            Object builder = withSize.invoke(null, width, height);
            addColorTextureBuffer.invoke(builder);
            setDepthTextureBuffer.invoke(builder);
            setName.invoke(builder, label);
            Object handle = build.invoke(builder, true);

            bind.invoke(handle, true);
            clear.invoke(handle);
            unbind.invoke(null);
            return new Fbo(handle, width, height);
        } catch (Throwable t) {
            fail("创建离屏图失败", t);
            return null;
        }
    }

    /**
     * 以世界正俯视相机渲染 {@code sub} 所在的连通组。
     *
     * <p>相机位置 = 船体局部中心的当前世界位置 + 世界竖直方向抬升；朝向固定为
     * 世界朝下，不跟着船体姿态转。船体的旋转/倾斜由各 sub-level 自身的 pose 体现。
     *
     * @return false 表示渲染失败（已记 ERROR）
     */
    static boolean render(Fbo fbo, ClientLevel level, ClientSubLevel sub,
                          TopDownView view, float partialTick) {
        if (!available() || fbo == null) {
            return false;
        }
        try {
            Pose3dc pose = sub.renderPose(partialTick);
            Vector3d camera = pose.transformPosition(new Vector3d(view.localCenter()));
            camera.add(0.0, view.cameraLift(), 0.0);
            // renderGroup 收的是视空间朝向（相机世界朝向的逆）
            Quaternionf orientation = new Quaternionf(view.orientation()).conjugate();

            bind.invoke(fbo.handle, true);
            clear.invoke(fbo.handle);
            Object chain = getRenderedChain.invoke(null, sub);
            renderGroup.invoke(null, level, chain, fbo.handle, new Matrix4f(),
                    view.projection(), camera, orientation, partialTick, false);
            // renderGroup 的每条返回路径都会 restore 相机并 unbind
            return true;
        } catch (Throwable t) {
            fail("离屏渲染失败", t);
            return false;
        }
    }

    /**
     * 把 FBO 颜色缓冲读回 {@code image}，并翻转行序：
     * GL 原点在左下，NativeImage/贴图第 0 行在上（局部 -Z / 北）。
     */
    static boolean read(Fbo fbo, NativeImage image, ByteBuffer buffer) {
        if (!available() || fbo == null) {
            return false;
        }
        try {
            bind.invoke(fbo.handle, true);
            buffer.clear();
            GL11.glReadPixels(0, 0, fbo.width, fbo.height,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
            unbind.invoke(null);

            int width = fbo.width;
            int stride = width * 4;
            for (int y = 0; y < fbo.height; y++) {
                int row = (fbo.height - 1 - y) * stride;
                for (int x = 0; x < width; x++) {
                    int i = row + x * 4;
                    int r = buffer.get(i) & 0xFF;
                    int g = buffer.get(i + 1) & 0xFF;
                    int b = buffer.get(i + 2) & 0xFF;
                    int a = buffer.get(i + 3) & 0xFF;
                    // NativeImage 的 RGBA 内存布局：低字节 = R → 传 ABGR 整数
                    image.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
            return true;
        } catch (Throwable t) {
            fail("离屏图读取失败", t);
            return false;
        }
    }

    static void close(Fbo fbo) {
        if (fbo == null) {
            return;
        }
        try {
            ((AutoCloseable) fbo.handle).close();
        } catch (Throwable t) {
            LogManager.clientWarn(TAG, "释放离屏图失败: {}", t.toString());
        }
    }

    private static void fail(String what, Throwable t) {
        failed = true;
        LogManager.clientError(TAG, what + "（图解图层后续 shutdown）: " + t);
    }

    /** 一张离屏图句柄；尺寸是创建时定的，包围盒变化就重建。 */
    static final class Fbo {

        final Object handle;
        final int width;
        final int height;

        Fbo(Object handle, int width, int height) {
            this.handle = handle;
            this.width = width;
            this.height = height;
        }
    }
}
