package com.catoxide.catoxidesbattlerebuild;

import com.catoxide.catoxidesbattlerebuild.command.DebugCommands;
import com.catoxide.catoxidesbattlerebuild.registry.ModEntities;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.Mixins;

@Mod(CatoxidesBattleRebuild.MODID)
public class CatoxidesBattleRebuild {
    public static final String MODID = "catoxidesbattlerebuild";

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public CatoxidesBattleRebuild() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 注册Deferred Register
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        ModEntities.ENTITIES.register(modEventBus);

        // 注册事件总线
        MinecraftForge.EVENT_BUS.register(this);

        MixinBootstrap.init();
        Mixins.addConfiguration("catoxidesbattlerebuild.mixins.json");

    }
    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        DebugCommands.register(event.getDispatcher());
    }
    public class ClientSetup {

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            // 确保在客户端初始化
            System.out.println("自定义碰撞箱渲染系统已加载");
        }
    }
}