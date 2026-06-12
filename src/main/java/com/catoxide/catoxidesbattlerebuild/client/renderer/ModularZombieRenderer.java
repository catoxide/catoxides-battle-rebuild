package com.catoxide.catoxidesbattlerebuild.client.renderer;

import com.catoxide.catoxidesbattlerebuild.client.model.ModularZombieModel;
import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class ModularZombieRenderer extends GeoEntityRenderer<ModularZombie> {
    public ModularZombieRenderer(EntityRendererProvider.Context context) {
        super(context, new ModularZombieModel());
        this.shadowRadius = 0.5f;
        LogManager.clientStartup("ModularZombieRenderer", "ModularZombieRenderer initialized (GeckoLib)");
    }
}
