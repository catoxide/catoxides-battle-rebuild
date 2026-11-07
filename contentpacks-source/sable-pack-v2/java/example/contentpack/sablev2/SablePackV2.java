package example.contentpack.sablev2;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPack;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.core.sable.SubLevelServiceRegistry;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import example.contentpack.sablev2.map.SableMapIntegration;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Sable 适配层（v2）：sub-level 防腐服务 + GPU 船体图解地图层。
 *
 * <p>硬依赖 sable（MANIFEST {@code Module-Mod-Requires}）；图解渲染额外需要
 * simulated（软依赖 {@code Module-Mod-Optional}，Create: Aeronautics 随附）。
 * 只有 sable 时本包照常提供防腐服务，但图解图层在 init 阶段响亮关闭。
 */
public class SablePackV2 implements ContentPack {

    private final SableSubLevelService service = new SableSubLevelService();
    private SableMapIntegration mapIntegration;

    @Override
    public String getId() {
        // 与 MANIFEST 的 Module-Name 一致：配置/语言的命名空间与资源目录都按这个键取
        return "sable-pack-v2";
    }

    @Override
    public String getDisplayName() {
        return "Sable Pack v2";
    }

    @Override
    public String getVersion() {
        return "2.0.0";
    }

    @Override
    public void register(ContentPackContext context) {
        // 地图联动是可选消费方：register 只绑配置/跑纯数学自检，
        // 真正取 map:api 与 simulated 门控放到 init（所有包 register 已完成）。
        if (CatoxidesBattleRebuildConstants.CLIENT) {
            this.mapIntegration = new SableMapIntegration();
            this.mapIntegration.register(context);
        }
    }

    @Override
    public void init() {
        SubLevelServiceRegistry.register(service);
        LogManager.serverInfo("SablePackV2", "SableSubLevelService registered (sable adapter ready)");

        NeoForge.EVENT_BUS.addListener((LevelEvent.Load event) -> {
            if (event.getLevel() instanceof ServerLevel level) {
                service.attach(level);
            }
        });

        if (CatoxidesBattleRebuildConstants.CLIENT && this.mapIntegration != null) {
            this.mapIntegration.init();
        }
    }
}
