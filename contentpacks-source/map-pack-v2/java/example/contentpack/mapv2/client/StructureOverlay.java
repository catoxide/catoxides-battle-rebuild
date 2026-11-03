package example.contentpack.mapv2.client;

import com.mojang.blaze3d.systems.RenderSystem;
import example.contentpack.mapv2.structure.StructureEntry;
import example.contentpack.mapv2.view.MapProjection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 世界地图上的结构叠加层（独立顶层，不依赖群系预测层）。
 *
 * <p>绘制顺序在真实地形/方块顶面之后、标记之前：结构是地图内容的一部分，
 * 但玩家的路径点仍然压在最上面。图标是原版物品，随 GUI scale 保持固定视觉大小。
 *
 * <p>两档：候选级（服务端复算落点，未核实）半透明绘制；确认级（该区块已加载、
 * 原版结构起点里确实有它）不透明绘制。悬停提示写明是哪一档（D94）。
 */
final class StructureOverlay {

    private static final int MAX_RENDERED = 512;
    private static final float CANDIDATE_ALPHA = 0.8f;

    private StructureOverlay() {
    }

    static void render(GuiGraphics graphics, Minecraft minecraft,
                       int left, int top, int viewWidth, int viewHeight,
                       double bpp, double originX, double originZ,
                       ResourceLocation dimension) {
        List<StructureEntry> entries = StructureClient.ensureVisible(
                dimension, originX, originZ, viewWidth, viewHeight, bpp);
        if (entries.isEmpty()) {
            return;
        }
        float guiScale = (float) minecraft.getWindow().getGuiScale();
        int drawn = 0;
        for (StructureEntry entry : entries) {
            int px = left + MapProjection.pixel(entry.blockX(), originX, bpp);
            int py = top + MapProjection.pixel(entry.blockZ(), originZ, bpp);
            if (px < left || px >= left + viewWidth || py < top || py >= top + viewHeight) {
                continue;
            }
            if (drawn++ >= MAX_RENDERED) {
                break;
            }
            graphics.pose().pushPose();
            graphics.pose().translate(px, py, 0f);
            graphics.pose().scale(guiScale, guiScale, 1f);
            RenderSystem.enableBlend();
            RenderSystem.setShaderColor(1f, 1f, 1f,
                    entry.confirmed() ? 1f : CANDIDATE_ALPHA);
            graphics.renderItem(StructureClient.iconFor(entry), -8, -8);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            graphics.pose().popPose();
        }
    }

    static void drawTooltip(GuiGraphics graphics, Minecraft minecraft,
                            StructureEntry entry, int mouseX, int mouseY) {
        List<Component> lines = new ArrayList<>(3);
        lines.add(StructureClient.name(entry));
        lines.add(Component.literal(entry.blockX() + ", " + entry.blockZ()));
        lines.add(Component.translatable(entry.confirmed()
                ? "catoxidesbattlerebuild.map.structure.tier.confirmed"
                : "catoxidesbattlerebuild.map.structure.tier.candidate"));
        graphics.renderComponentTooltip(minecraft.font, lines, mouseX, mouseY);
    }
}
