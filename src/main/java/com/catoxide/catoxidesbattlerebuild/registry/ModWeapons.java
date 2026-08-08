package com.catoxide.catoxidesbattlerebuild.registry;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.weapon.CustomBowWeapon;
import com.catoxide.catoxidesbattlerebuild.weapon.IronSwordWeapon;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModWeapons {

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(
        net.minecraft.core.registries.Registries.ITEM,
        CatoxidesBattleRebuildConstants.MODID
    );

    public static final DeferredHolder<Item, Item> IRON_SWORD_WEAPON = ITEMS.register(
        "iron_sword_weapon",
        () -> new IronSwordWeapon(new Item.Properties().stacksTo(1))
    );

    public static final DeferredHolder<Item, Item> CUSTOM_BOW = ITEMS.register(
        "custom_bow",
        () -> new CustomBowWeapon(new Item.Properties().stacksTo(1))
    );
}