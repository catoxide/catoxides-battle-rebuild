//package com.catoxide.catoxidesbattlerebuild.client.renderer;
//
//import com.catoxide.catoxidesbattlerebuild.client.renderer.OBBDebugRenderer;
//import com.mojang.blaze3d.platform.Window;
//import com.mojang.blaze3d.vertex.PoseStack;
//import net.minecraft.client.Minecraft;
//import net.minecraft.client.gui.GuiGraphics;
//import net.minecraft.network.chat.Component;
//import net.minecraft.world.phys.HitResult;
//import net.minecraft.world.phys.Vec3;
//
///**
// * 调试信息叠加层
// */
//public class DebugOverlay {
//    private static final DebugOverlay INSTANCE = new DebugOverlay();
//    private boolean showInfo = true;
//
//    private DebugOverlay() {}
//
//    public static DebugOverlay getInstance() {
//        return INSTANCE;
//    }
//
//    public void toggleInfo() {
//        showInfo = !showInfo;
//    }
//
//    public void render(GuiGraphics guiGraphics, float partialTick) {
//        if (!showInfo) return;
//
//        Minecraft mc = Minecraft.getInstance();
//        if (mc.player == null || mc.level == null) return;
//
//        PoseStack poseStack = guiGraphics.pose();
//        Window window = mc.getWindow();
//
//        int x = 10;
//        int y = 10;
//        int lineHeight = 12;
//
//        // OBB调试信息
//        if (OBBDebugRenderer.getInstance().isEnabled()) {
//            String debugInfo = OBBDebugRenderer.getInstance().getDebugInfo();
//            guiGraphics.drawString(mc.font, debugInfo, x, y, 0xFFFFFF);
//            y += lineHeight;
//
//            // 显示视线指向的OBB
//            Vec3 eyePos = mc.player.getEyePosition(partialTick);
//            Vec3 lookVec = mc.player.getLookAngle();
//
//            OBBDebugRenderer.getInstance().raycastOBB(eyePos, lookVec, 20.0f)
//                    .ifPresent(hitPoint -> {
//                        String hitInfo = String.format("OBB Hit: (%.2f, %.2f, %.2f)",
//                                hitPoint.x, hitPoint.y, hitPoint.z);
//                        guiGraphics.drawString(mc.font, hitInfo, x, y, 0xFF5555);
//                        y += lineHeight;
//                    });
//
//            // 显示选中的方块/实体
//            if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY) {
//                String lookingAt = "Looking at entity (ID: " +
//                        mc.hitResult.getEntity().getId() + ")";
//                guiGraphics.drawString(mc.font, lookingAt, x, y, 0x55FF55);
//                y += lineHeight;
//            }
//        }
//
//        // FPS信息
//        int fps = Minecraft.getInstance().getFps();
//        String fpsText = "FPS: " + fps;
//        guiGraphics.drawString(mc.font, fpsText, x, window.getGuiScaledHeight() - 20, 0xFFFFFF);
//    }
//}
