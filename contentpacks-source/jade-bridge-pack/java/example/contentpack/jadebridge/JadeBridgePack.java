package example.contentpack.jadebridge;

import com.catoxide.catoxidesbattlerebuild.core.contentpack.ClientRenderRegistrar;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPack;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;

/**
 * 实验：Jade 作为外部库 + 手动初始化
 * <p>验证"把 mod 当库用、不靠注解扫描、由 contentpack 自行初始化"的路是否可行。
 * <p>核心思路：
 * <ol>
 *   <li>Jade 由 NeoForge 正常加载（mods/ 里的 Jade.jar，保持原样）</li>
 *   <li>本 contentpack 通过 {@code Module-Lib-Requires: jade} 把 Jade jar 加入类加载闭包</li>
 *   <li>本包不实现 {@code @WailaPlugin}（不靠 Jade 的注解扫描发现）——而是在
 *       {@code init()} 里通过 {@code WailaCommonRegistration.instance()} 拿到 Jade 的注册器单例，
 *       手动注册我们自己的插件</li>
 *   <li>描边渲染逻辑（OBB 线框等）作为本包的自有代码，独立挂载</li>
 * </ol>
 * <p>刻意不初始化：只注册我们需要的（骨骼血量 provider），不注册 Jade 的任何内置插件。
 */
public class JadeBridgePack implements ContentPack {

    @Override
    public String getId() {
        return "jade-bridge-pack";
    }

    @Override
    public String getDisplayName() {
        return "Jade Bridge (manual init experiment)";
    }

    @Override
    public String getVersion() {
        return "0.1.0";
    }

    @Override
    public void register(ContentPackContext context) {
        LogManager.serverInfo("JadeBridge", "register() called — 注册阶段（先不做注册表内容）");
    }

    @Override
    public void init() {
        // ===== 手动初始化 Jade：不靠 @WailaPlugin 注解扫描 =====
        LogManager.serverInfo("JadeBridge", "init() — 尝试手动注册 Jade 插件（绕过注解扫描）");
        try {
            // 通过反射访问 Jade 的注册器单例（避免编译期强依赖 Jade，缺 Jade 时本包静默降级）
            Class<?> commonRegClass = Class.forName("snownee.jade.impl.WailaCommonRegistration");
            Object commonReg = commonRegClass.getMethod("instance").invoke(null);
            if (commonReg == null) {
                LogManager.serverWarn("JadeBridge", "Jade WailaCommonRegistration.instance() 返回 null — 跳过");
                return;
            }
            LogManager.serverInfo("JadeBridge", "✓ 成功拿到 Jade 注册器: %s", commonRegClass.getSimpleName());

            // 客户端注册（若在客户端环境）
            if (net.neoforged.api.distmarker.Dist.CLIENT.isClient()) {
                try {
                    Class<?> clientRegClass = Class.forName("snownee.jade.impl.WailaClientRegistration");
                    Object clientReg = clientRegClass.getMethod("instance").invoke(null);
                    LogManager.serverInfo("JadeBridge", "✓ 客户端注册器: %s", clientReg != null ? "OK" : "null");
                } catch (Exception e) {
                    LogManager.serverWarn("JadeBridge", "客户端注册器不可用（服务端环境或无客户端）: %s", e.getMessage());
                }
            }

            LogManager.serverInfo("JadeBridge", "=== 手动初始化成功：Jade 注册器已拿到（注解扫描被绕过）===");
        } catch (Exception e) {
            LogManager.serverError("JadeBridge", "手动初始化 Jade 失败（Jade 可能未安装）: %s", e.getMessage());
        }
    }

    @Override
    public void registerClientRenderers(ClientRenderRegistrar registrar) {
        // 描边渲染逻辑入口（后续接入 OBBRenderer / BoneDebugRenderer）
        LogManager.clientStartup("JadeBridge", "registerClientRenderers() — 描边渲染挂载点（待接入）");
    }
}
