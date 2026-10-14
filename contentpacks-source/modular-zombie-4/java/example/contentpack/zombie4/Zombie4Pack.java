package example.contentpack.zombie4;

import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPack;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.core.mob.DataDrivenMob;
import com.catoxide.catoxidesbattlerebuild.core.mob.MobDefinition;
import com.catoxide.catoxidesbattlerebuild.core.mob.MobDefinitionParser;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.registries.DeferredHolder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * ModularZombie4 ContentPack：完全数据驱动复现 zombie-3（生物零 Java）。
 *
 * <p>与 zombie-3 的差异只在"行为定义方式"：
 * <ul>
 *   <li>zombie-3：ModularZombie3 extends AnimatedMob（284 行 Java：状态判定/受击方向/计时全硬编码）</li>
 *   <li>zombie-4：{@link DataDrivenMob} + 实体 JSON + <b>TimeTape 状态机 JSON</b>
 *       （data/zombie4pack/state_machines/modular_zombie_4.json，本 jar 内；
 *       DataDrivenMob 按约定路径自动发现，AnimatedMob 懒加载）</li>
 * </ul>
 *
 * <p>外观/动画/音效完全复用 zombie3pack 命名空间资产（模型/8 状态动画/贴图/原版僵尸声音）。
 * 本入口只做一件事：调库函数 {@code registerDataDrivenMob}（"完全通过库里的函数和方法复现 3"）。
 */
public class Zombie4Pack implements ContentPack {

    DeferredHolder<EntityType<?>, EntityType<DataDrivenMob>> MODULAR_ZOMBIE_4;

    @Override
    public String getId() {
        return "modular_zombie_4";
    }

    @Override
    public String getDisplayName() {
        return "Modular Zombie 4 Pack (TimeTape 数据驱动)";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public void register(ContentPackContext context) {
        try (InputStream in = getClass().getResourceAsStream("/entities/modular_zombie_4.json")) {
            if (in == null) {
                LogManager.serverError("Zombie4Pack", "entities/modular_zombie_4.json not found in jar");
                return;
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            MobDefinition def = MobDefinitionParser.parse(json);
            MODULAR_ZOMBIE_4 = context.registerDataDrivenMob(def);
            // 状态机 = 约定路径自动发现（无需显式注册）：
            //   data/zombie4pack/state_machines/modular_zombie_4.json（本 jar 内，
            //   ContentPackRepositorySource 挂载为 SERVER_DATA 资源）
            LogManager.serverInfo("Zombie4Pack",
                    "Registered data-driven zombie-4: %s (behaviors=%d, state machine via convention path)",
                    def.entityKey(), def.behaviors().size());
        } catch (Exception e) {
            LogManager.serverError("Zombie4Pack", "Failed to register zombie-4: {}", e.getMessage(), e);
        }
    }

    @Override
    public void init() {
        LogManager.serverInfo("Zombie4Pack", "Initializing ContentPack: {} v{}", getId(), getVersion());
    }
}
