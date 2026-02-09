package com.catoxide.catoxidesbattlerebuild;

import com.catoxide.catoxidesbattlerebuild.registry.ModEntities;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
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
        
        // 手动注册RenderEventHandler到Forge事件总线
        MinecraftForge.EVENT_BUS.register(RenderEventHandler.class);

        MixinBootstrap.init();
        Mixins.addConfiguration("catoxidesbattlerebuild.mixins.json");
    }
}