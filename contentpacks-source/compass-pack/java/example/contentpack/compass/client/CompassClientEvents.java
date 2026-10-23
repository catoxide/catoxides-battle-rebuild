package example.contentpack.compass.client;

import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 客户端事件接线（端口自 SkyrimCompassHUD 的 ClientEvents：ModBus + ForgeBus → 显式注册）。
 *
 * <h3>1.21.1 事件总线映射（相对 MIT 原版 Forge 1.20.1）</h3>
 * <ul>
 *   <li>按键（H）注册 → <b>mod bus</b>（RegisterKeyMappingsEvent 为 IModBusEvent，原版是 ForgeModBus）</li>
 *   <li>客户端 tick 消费 H 键 → <b>全局 NeoForge.EVENT_BUS</b>
 *       （ClientTickEvent 是全局 NeoForge 事件，原版是 ForgeModBus 的 TickEvent）</li>
 *   <li>罗盘绘制 → <b>全局 NeoForge.EVENT_BUS</b> 的 {@code RenderGuiEvent$Pre}（见下）</li>
 * </ul>
 *
 * <h3>为何用 Pre 而非 GUI 层（solve：罗盘要低于 Jade，避免覆盖 Jade 顶部条）</h3>
 * NeoForge 1.21.1 的 GUI 通路过字节码验证（GameRenderer + GuiLayerManager.render）：
 * <pre>
 *   RenderGuiEvent$Pre（全局总线）
 *     → renderInner（所有 Layer：原版 HUD + 各 Mod 层）
 *       → 每个 Layer：RenderGuiLayerEvent$Pre → layer.render → RenderGuiLayerEvent$Post
 *   RenderGuiEvent$Post（全局总线）
 * </pre>
 * Jade（Waila 系 15.10.5）的顶部覆盖层在其 {@code ClientProxy.lambda$init$0} 里
 * 挂在全局总线的 {@code RenderGuiEvent$Post} 上、且仅在 {@code mc.screen == null}（游戏中）绘制。
 * 因此 Jade 的绘制发生在 {@code renderInner}（全部 Layer）<b>之后</b>。
 *
 * 关键：Jade 不走 Layer 体系（它是 Post 事件回调，不是 Layer），所以罗盘无论以何种
 * {@code RegisterGuiLayersEvent} 注册，都在 {@code renderInner} 里，仍早于 Jade 的 Post。
 * 若要罗盘<b>低于</b>Jade（让 Jade 盖住重叠区），罗盘就必须排在 Jade 的 Post 之前，
 * 且不能依赖 Layer 注册（Layer 无法排到 Post 之后……而是必须"更早"）。
 * 用 {@code RenderGuiEvent$Pre}：它在 GUI 通的最开头、早于一切 Layer、也早于 Jade 的 Post。
 * 结构保证绘制顺序：罗盘（Pre）→ 各 Layer → Jade（Post） —— 即 Jade 永远盖住重叠区。
 *
 * 行为一致性：Pre 仅在 GUI 渲染时触发（屏幕打开时 GUI 渲染不经过本 Pre 回调，因为
 * screen 有自己的 render 路径；且 Pre 只在无 screen 的全局 GUI 渲染触发），与原 Layer 方案
 * + {@code player != null && !hideGui} 守卫的可见范围等价（游戏内可见、主菜单/全屏时由 render 方法内守卫）。
 *
 * 均在 {@link CompassPack#register} 保证的 CLIENT 下由调用方接线。
 */
public final class CompassClientEvents {

    /** 罗盘显示状态（H 键切换）。volatile：全局 tick 线程写、渲染读。 */
    public static volatile boolean visible = true;

    /** H 键开关。类目沿用原 mod 的自定义类目（lang 已提供 key.categories.skyrimcompasshud）。 */
    public static final KeyMapping TOGGLE_COMPASS = new KeyMapping(
            "key.skyrimcompasshud.toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_H,
            "key.categories.skyrimcompasshud");

    private CompassClientEvents() {
    }

    /**
     * 事件接线。由 {@link CompassPack#register} 在 CLIENT 下调用。
     *
     * @param modBus 本包的 mod event bus（注册 mod-bus 事件用）
     */
    public static void init(IEventBus modBus) {
        // ===== 1. H 键：注册按键映射（mod bus） =====
        modBus.addListener(RegisterKeyMappingsEvent.class, e ->
                e.register(TOGGLE_COMPASS));

        // ===== 2. 客户端 tick：H 键按下切换罗盘（全局 NeoForge 事件，lambda 显式注册，同 quest 包写法） =====
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            while (TOGGLE_COMPASS.consumeClick()) {
                visible = !visible;
            }
        });

        // ===== 3. 罗盘绘制：全局总线 RenderGuiEvent$Pre（低于一切 Layer / 低于 Jade 的 Post） =====
        //
        // Pre 在 GUI 通的最早处，先于任何 Layer 渲染，也先于 Jade 的 RenderGuiEvent$Post，
        // 故结构保证 Jade 盖住罗盘重叠区。Guard（visible / hideGui / player!=null）仍在 render 方法内，
        // 此处仅转发。
        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Pre e) ->
                CompassOverlay.INSTANCE.render(e.getGuiGraphics(), e.getPartialTick()));
    }
}
