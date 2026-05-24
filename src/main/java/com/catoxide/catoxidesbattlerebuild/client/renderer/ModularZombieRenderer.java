package com.catoxide.catoxidesbattlerebuild.client.renderer;

import com.catoxide.catoxidesbattlerebuild.client.model.ModularZombieModel;
import com.catoxide.catoxidesbattlerebuild.mob.ModularZombie;
import com.catoxide.catoxidesbattlerebuild.util.LogManager;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class ModularZombieRenderer extends GeoEntityRenderer<ModularZombie> {
    public ModularZombieRenderer(EntityRendererProvider.Context context) {
        super(context, new ModularZombieModel());
        this.shadowRadius = 0.5f;
        LogManager.clientStartup("ModularZombieRenderer", "ModularZombieRenderer initialized");
    }

    @Override
    public void render(ModularZombie entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        String message = String.format("Rendering entity: entityId=%d, name=%s, partialTick=%.2f",
                entity.getId(), entity.getName().getString(), partialTick);
        LogManager.clientDebugThrottled("ModularZombieRenderer", message, 500);
        
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }
}