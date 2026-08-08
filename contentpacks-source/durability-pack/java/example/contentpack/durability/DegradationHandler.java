package example.contentpack.durability;

import com.catoxide.catoxidesbattlerebuild.core.durability.IDurabilityDegradationHandler;
import com.catoxide.catoxidesbattlerebuild.core.durability.IVariableDurability;
import com.catoxide.catoxidesbattlerebuild.core.durability.DurabilityCapabilities;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.Random;
import java.util.Set;

/**
 * 耐久降级处理实现（三机制）
 * <p>从原版 Durability_Overhaul 1.21.1 移植，接入主 mod 分发器：
 * <ol>
 *   <li>经验修补 Mending：每修复 1 点耐久按概率降 1 最大耐久</li>
 *   <li>铁砧 Anvil：按修复量比例降最大耐久（暂简化为同 Mending 概率判定）</li>
 *   <li>合成台 Crafting：同上</li>
 * </ol>
 * 配置：默认概率 + 豁免列表（简化为静态常量，后续可改 ModConfigSpec）。
 */
public class DegradationHandler implements IDurabilityDegradationHandler {

    private static final String TAG = "DegradationHandler";
    private static final Random RANDOM = new Random();

    /** 配置（ModConfigSpec → toml，游戏内可改） */
    private static final Set<String> EXEMPT_ITEMS() {
        return Set.copyOf(DurabilityConfig.exemptItems);
    }

    @Override
    public void processMendingDegradation(ItemStack stack, ServerLevel level, int repairAmount) {
        try {
            if (level == null || stack.isEmpty() || repairAmount <= 0) return;
            if (stack.getMaxDamage() <= 0) return;
            String itemId = getItemId(stack);
            if (itemId == null || EXEMPT_ITEMS().contains(itemId)) return;
            rollAndReduce(stack, repairAmount, DurabilityConfig.mendingChance, "Mending", itemId);
        } catch (Throwable t) {
            LogManager.serverError(TAG, "Mending degradation failed: {}", t.getMessage());
        }
    }

    @Override
    public void processAnvilDegradation(ItemStack result, ItemStack left, ServerLevel level) {
        try {
            if (result == null || result.isEmpty() || left.isEmpty()) return;
            String itemId = getItemId(result);
            if (itemId == null || EXEMPT_ITEMS().contains(itemId)) return;
            // 铁砧：按修复量比例（简化：修复 1 点即判定一次）
            int repaired = left.getDamageValue() - result.getDamageValue();
            if (repaired <= 0) return;
            rollAndReduce(result, repaired, DurabilityConfig.anvilChance, "Anvil", itemId);
        } catch (Throwable t) {
            LogManager.serverError(TAG, "Anvil degradation failed: {}", t.getMessage());
        }
    }

    @Override
    public void processCraftingDegradation(ItemStack result, ItemStack damagedInput) {
        try {
            if (result == null || result.isEmpty() || damagedInput.isEmpty()) return;
            String itemId = getItemId(result);
            if (itemId == null || EXEMPT_ITEMS().contains(itemId)) return;
            int repaired = damagedInput.getDamageValue() - result.getDamageValue();
            if (repaired <= 0) return;
            rollAndReduce(result, repaired, DurabilityConfig.craftingChance, "Crafting", itemId);
        } catch (Throwable t) {
            LogManager.serverError(TAG, "Crafting degradation failed: {}", t.getMessage());
        }
    }

    /** 每修复 1 点独立概率判定，累计降级数 */
    private void rollAndReduce(ItemStack stack, int repairedPoints, double chance, String source, String itemId) {
        if (chance <= 0) return;
        int totalReduction = 0;
        for (int i = 0; i < repairedPoints; i++) {
            if (RANDOM.nextFloat() < chance) {
                totalReduction++;
            }
        }
        if (totalReduction <= 0) {
            LogManager.serverDebug(TAG, "[{}] No degradation for {} (repaired {} pts)", source, itemId, repairedPoints);
            return;
        }
        reduceMaxDurability(stack, totalReduction, source, itemId, chance);
    }

    /** 通过可变耐久能力降低最大耐久（下限 1） */
    private void reduceMaxDurability(ItemStack stack, int amount, String source, String itemId, double chance) {
        IVariableDurability cap = stack.getCapability(DurabilityCapabilities.VAR_DURABILITY_CAP, null);
        if (cap == null) return;
        int currentMax = cap.getMaxDamage(stack);
        int newMax = Math.max(1, currentMax - amount);
        cap.setMaxDamage(stack, newMax);
        LogManager.serverInfo(TAG, "[{}] Degraded {} max durability {} -> {} (reduced by {}, chance {})",
                source, itemId, currentMax, newMax, amount, chance);
    }

    private String getItemId(ItemStack stack) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key != null ? key.toString() : null;
    }
}
