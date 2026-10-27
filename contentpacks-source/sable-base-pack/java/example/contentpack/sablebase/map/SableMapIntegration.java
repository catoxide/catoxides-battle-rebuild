package example.contentpack.sablebase.map;

import com.catoxide.catoxidesbattlerebuild.core.contentpack.CapabilityRegistry;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.core.map.MapCaps;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import example.contentpack.sablebase.map.client.SableMapController;

/**
 * sable-base 的地图联动：把 sub-level 俯视层与标点锚定挂到 map-pack。
 *
 * <p>生命周期与 pack 的两阶段一致：
 * <ul>
 *   <li>{@link #register(ContentPackContext)}：绑定配置、跑纯数学自检（register 阶段）；</li>
 *   <li>{@link #init()}：所有包的 register 已完成、能力已锁定，
 *       此时再取 {@code map:api}。这样即使 map-pack 因可选依赖排在后面也不会漏。</li>
 * </ul>
 * 没有 map-pack 时静默不启用，sable-base 的防腐层服务照常工作。
 */
public final class SableMapIntegration {

    private static final String TAG = "SableMap";

    private SableMapController controller;
    private boolean mathOk = true;

    /** register 阶段：配置只能在此时绑定。 */
    public void register(ContentPackContext context) {
        SableMapConfig.bind(context.config("sablemap"));
        this.mathOk = SableMapSelfTest.run();
    }

    /** init 阶段：能力已就绪，挂 overlay / 坐标解析器。 */
    public void init() {
        if (!this.mathOk) {
            LogManager.clientError(TAG, "Sable map math self-test failed; layer disabled");
            return;
        }
        CapabilityRegistry.get(MapCaps.API).ifPresentOrElse(api -> {
            this.controller = new SableMapController();
            this.controller.register(api);
            LogManager.clientStartup(TAG,
                    "Sable sub-level 地图层已注册（俯视快照 + 船上实体 + 标点锚定）");
        }, () -> LogManager.clientInfo(TAG,
                "map-pack 未安装或未提供 map:api；Sable 地图层不启用"));
    }
}
