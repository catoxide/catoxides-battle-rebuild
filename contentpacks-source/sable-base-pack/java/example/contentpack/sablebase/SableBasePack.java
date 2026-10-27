package example.contentpack.sablebase;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPack;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.core.sable.SubLevelServiceRegistry;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import example.contentpack.sablebase.map.SableMapIntegration;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * sable-base 内容包：防腐层适配器
 * <p>依赖 sable mod（MANIFEST 声明 {@code Module-Mod-Requires: sable>=2.0.3}，
 * 未安装时本包不加载，主 mod 的 {@link SubLevelServiceRegistry#isAvailable()} 返回 false，调用方降级）。
 *
 * <p>职责：注册 {@link SableSubLevelService} 到防腐层注册表，并在服务端世界加载时 attach。
 * 客户端额外提供可选的地图联动（sub-level 俯视层 / 船上实体 / 标点锚定）：
 * 依赖 map-pack 时通过 core {@code MapCaps.API} 挂接，未安装时静默禁用，不影响防腐层。
 */
public class SableBasePack implements ContentPack {

    private final SableSubLevelService service = new SableSubLevelService();
    private SableMapIntegration mapIntegration;

    @Override
    public String getId() {
        return "sable_base";
    }

    @Override
    public String getDisplayName() {
        return "Sable Base Adapter";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public void register(ContentPackContext context) {
        // 无内容注册（纯适配层）
        // 地图联动是可选消费方：只绑配置/自检，真正取 map:api 放到 init（能力已锁定）
        if (CatoxidesBattleRebuildConstants.CLIENT) {
            this.mapIntegration = new SableMapIntegration();
            this.mapIntegration.register(context);
        }
    }

    @Override
    public void init() {
        // 注册防腐层服务
        SubLevelServiceRegistry.register(service);
        LogManager.serverInfo("SableBasePack", "SableSubLevelService registered (sable adapter ready)");

        // 服务端世界加载时 attach sub-level 追踪
        NeoForge.EVENT_BUS.addListener((LevelEvent.Load event) -> {
            if (event.getLevel() instanceof ServerLevel level) {
                service.attach(level);
            }
        });

        if (CatoxidesBattleRebuildConstants.CLIENT && this.mapIntegration != null) {
            // map-pack 是可选依赖（Module-Dependencies-Optional），
            // 此时所有包的 register 已完成，能力查询稳定。
            this.mapIntegration.init();
        }
    }
}
