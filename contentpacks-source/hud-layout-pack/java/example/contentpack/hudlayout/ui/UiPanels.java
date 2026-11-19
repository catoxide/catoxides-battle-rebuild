package example.contentpack.hudlayout.ui;

import com.catoxide.catoxidesbattlerebuild.core.ui.ScreenPanels;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.BeaconScreen;
import net.minecraft.client.gui.screens.inventory.BlastFurnaceScreen;
import net.minecraft.client.gui.screens.inventory.BrewingStandScreen;
import net.minecraft.client.gui.screens.inventory.CartographyTableScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.DispenserScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.client.gui.screens.inventory.FurnaceScreen;
import net.minecraft.client.gui.screens.inventory.GrindstoneScreen;
import net.minecraft.client.gui.screens.inventory.HopperScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.LoomScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.client.gui.screens.inventory.SmithingScreen;
import net.minecraft.client.gui.screens.inventory.SmokerScreen;
import net.minecraft.client.gui.screens.inventory.StonecutterScreen;
import net.minecraft.resources.ResourceLocation;

/**
 * 原版容器屏 → 面板 id。
 *
 * <p>只登记「确实会去改」的那些屏；没登记的容器屏（比如创造物品栏）布局层完全不碰。
 * 第三方屏由它的包自己调 {@link ScreenPanels#register}（opt-in，契约 §7-3）。
 */
public final class UiPanels {

    private UiPanels() {
    }

    public static void registerVanilla() {
        ScreenPanels.register(InventoryScreen.class, id("inventory"));
        ScreenPanels.register(CraftingScreen.class, id("crafting"));
        ScreenPanels.register(FurnaceScreen.class, id("furnace"));
        ScreenPanels.register(BlastFurnaceScreen.class, id("blast_furnace"));
        ScreenPanels.register(SmokerScreen.class, id("smoker"));
        // 箱子/木桶等通用容器在原版就是 ContainerScreen；子类若单独登记会先命中自己。
        ScreenPanels.register(ContainerScreen.class, id("chest"));
        ScreenPanels.register(ShulkerBoxScreen.class, id("shulker_box"));
        ScreenPanels.register(HopperScreen.class, id("hopper"));
        ScreenPanels.register(DispenserScreen.class, id("dispenser"));
        ScreenPanels.register(BrewingStandScreen.class, id("brewing_stand"));
        ScreenPanels.register(AnvilScreen.class, id("anvil"));
        ScreenPanels.register(EnchantmentScreen.class, id("enchanting"));
        ScreenPanels.register(GrindstoneScreen.class, id("grindstone"));
        ScreenPanels.register(LoomScreen.class, id("loom"));
        ScreenPanels.register(CartographyTableScreen.class, id("cartography_table"));
        ScreenPanels.register(StonecutterScreen.class, id("stonecutter"));
        ScreenPanels.register(SmithingScreen.class, id("smithing"));
        ScreenPanels.register(MerchantScreen.class, id("merchant"));
        ScreenPanels.register(BeaconScreen.class, id("beacon"));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }
}
