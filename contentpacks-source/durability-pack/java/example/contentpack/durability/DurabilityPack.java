package example.contentpack.durability;

import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPack;
import com.catoxide.catoxidesbattlerebuild.core.contentpack.ContentPackContext;
import com.catoxide.catoxidesbattlerebuild.core.durability.DurabilityCapabilities;
import com.catoxide.catoxidesbattlerebuild.core.durability.VariableDurabilityCapability;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 耐久内容包（过渡接入）
 * <p>给所有可损坏的原版物品注册可变耐久能力——这是「没有自定义定义时改变原版行为」的兜底：
 * 原版物品通过 {@code ItemStackMixin} + 本能力获得自定义最大耐久（未来由降级/修复系统驱动）。
 *
 * <p>未来自定义武器：直接实现 {@link com.catoxide.catoxidesbattlerebuild.core.durability.IVariableDurability}
 * 并注册到能力即可，无需经过本包的全量注册。
 */
public class DurabilityPack implements ContentPack {

    @Override
    public String getId() {
        return "durability_pack";
    }

    @Override
    public String getDisplayName() {
        return "Durability Overhaul (过渡接入)";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public void register(ContentPackContext context) {
        // 注册配置（ModConfigSpec → toml，游戏内可编辑 + reload 生效）
        context.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON,
                DurabilityConfig.SPEC, "durability-pack-common");
        context.getModEventBus().addListener(
                (net.neoforged.fml.event.config.ModConfigEvent.Loading e) -> DurabilityConfig.load());

        // 通过 mod 总线注册 RegisterCapabilitiesEvent（mod 加载阶段）
        context.getModEventBus().addListener((RegisterCapabilitiesEvent event) -> {
            List<Item> damageable = new ArrayList<>();
            BuiltInRegistries.ITEM.forEach(item -> {
                if (item.getDefaultInstance().getMaxDamage() > 0) {
                    damageable.add(item);
                }
            });
            if (!damageable.isEmpty()) {
                event.registerItem(DurabilityCapabilities.VAR_DURABILITY_CAP,
                        (stack, ctx) -> new VariableDurabilityCapability(),
                        damageable.toArray(new Item[0]));
            }
            LogManager.serverInfo("DurabilityPack",
                    "Registered variable durability capability for %d damageable items", damageable.size());
        });
    }

    @Override
    public void init() {
        // 注册降级处理器（Mending/铁砧/合成台三机制）——主 mod mixin 经分发器调用
        com.catoxide.catoxidesbattlerebuild.core.durability.DurabilityDegradationRegistry
                .register(new DegradationHandler());
        LogManager.serverInfo("DurabilityPack", "Degradation handler registered (Mending/Anvil/Crafting)");
    }
}
