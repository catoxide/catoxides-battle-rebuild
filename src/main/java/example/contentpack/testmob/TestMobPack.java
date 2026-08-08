package example.contentpack.testmob;

import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPack;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.core.mob.MobDefinition;
import com.catoxide.catoxidesbattlerebuild.core.mob.MobDefinitionParser;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 测试内容包：验证「JSON 数据驱动实体」完整链路。
 * <p>复用 zombie3 的模型/动画/纹理资源（zombie3pack 命名空间），
 * 通过 {@link MobDefinitionParser} 从 jar 内 entities/*.json 解析定义，
 * 注册为 {@link com.catoxide.catoxidesbattlerebuild.core.mob.DataDrivenMob}。
 *
 * <p>验证目标：jar 类加载(ChildFirst) → 入口实例化 → JSON 解析 →
 * 数据驱动注册 → 通用渲染器 → 游戏内正确显示/行动。
 */
public class TestMobPack implements ContentPack {

    @Override
    public String getId() {
        return "test_mob_pack";
    }

    @Override
    public String getDisplayName() {
        return "Test Mob Pack";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public void register(ContentPackContext context) {
        try (InputStream in = getClass().getResourceAsStream("/entities/modular_zombie_test.json")) {
            if (in == null) {
                LogManager.serverError("TestMobPack", "entities/modular_zombie_test.json not found in jar");
                return;
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            MobDefinition definition = MobDefinitionParser.parse(json);
            context.registerDataDrivenMob(definition);
            LogManager.serverInfo("TestMobPack", "Registered data-driven test mob: %s (%d behaviors)",
                    definition.entityKey(), definition.behaviors().size());
        } catch (Exception e) {
            LogManager.serverError("TestMobPack", "Failed to register test mob: {}", e.getMessage(), e);
        }
    }
}
