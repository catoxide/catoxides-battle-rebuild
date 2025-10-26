package com.catoxide.catoxidesbattlerebuild.client.renderer;

import com.catoxide.catoxidesbattlerebuild.client.renderer.CustomHitboxRenderer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

@OnlyIn(Dist.CLIENT)
public class KeyInputHandler {

    public static final KeyMapping TOGGLE_HITBOX_RENDER = new KeyMapping(
            "key.catoxidesbattlerebuild.toggle_hitbox",
            GLFW.GLFW_KEY_H,
            "key.categories.catoxidesbattlerebuild"
    );

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // 检测 F3+B 组合键
        if (event.getKey() == GLFW.GLFW_KEY_B && event.getAction() == GLFW.GLFW_PRESS) {
            // 检查F3键是否按下
            if (Minecraft.getInstance().options.keyTogglePerspective.isDown()) {
                CustomHitboxRenderer.toggleRendering();
            }
        }

        // 检测自定义快捷键 H
        if (event.getKey() == TOGGLE_HITBOX_RENDER.getKey().getValue() && event.getAction() == GLFW.GLFW_PRESS) {
            CustomHitboxRenderer.toggleRendering();
        }
    }

    @SubscribeEvent
    public static void onKeyRegister(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_HITBOX_RENDER);
    }
}