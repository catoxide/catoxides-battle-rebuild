package com.catoxide.catoxidesbattlerebuild.client.quest;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.network.QuestSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.List;

/**
 * 任务状态简易 HUD（左上角，测试用）。
 * <p>显示当前玩家的激活任务：标题 + 状态（进行中/完成/失败）+ 子任务进度摘要。
 * <p>数据源：{@link ClientQuestCache}（服务端经 QuestSyncPacket 定时同步）——
 * 单机/多人统一，不直接触碰服务端 QuestSystem。
 */
@EventBusSubscriber(modid = CatoxidesBattleRebuildConstants.MODID, value = Dist.CLIENT)
public class QuestHUD {

    private static final int PADDING = 3;
    private static final int LINE_HEIGHT = 10;
    private static final int BACKGROUND_WIDTH = 220;
    private static final int COLOR_BG = 0x88000000;
    private static final int COLOR_ACTIVE = 0xFFFFFFFF;
    private static final int COLOR_COMPLETED = 0xFF55FF55;
    private static final int COLOR_FAILED = 0xFFFF5555;

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) {
            return;
        }
        List<QuestSyncPacket.QuestEntry> roots = ClientQuestCache.getInstance().getRootQuests();
        if (roots.isEmpty()) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        int x = 4;
        int y = 4;
        int height = roots.size() * LINE_HEIGHT + PADDING * 2;

        // 简易半透明背景
        graphics.fill(x, y, x + BACKGROUND_WIDTH, y + height, COLOR_BG);

        int textY = y + PADDING;
        for (QuestSyncPacket.QuestEntry entry : roots) {
            String line = format(entry);
            graphics.drawString(mc.font, line, x + PADDING, textY, colorOf(entry));
            textY += LINE_HEIGHT;
        }
    }

    private static String format(QuestSyncPacket.QuestEntry entry) {
        String status = switch (entry.state()) {
            case "COMPLETED" -> "完成";
            case "FAILED" -> "失败";
            default -> "进行中";
        };
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(entry.title()).append("] ").append(status);
        List<QuestSyncPacket.QuestEntry> children =
                ClientQuestCache.getInstance().getChildren(entry.questId());
        if (!children.isEmpty()) {
            long done = children.stream()
                    .filter(c -> "COMPLETED".equals(c.state()))
                    .count();
            sb.append(" (子任务 ").append(done).append('/').append(children.size()).append(')');
        }
        return sb.toString();
    }

    private static int colorOf(QuestSyncPacket.QuestEntry entry) {
        return switch (entry.state()) {
            case "COMPLETED" -> COLOR_COMPLETED;
            case "FAILED" -> COLOR_FAILED;
            default -> COLOR_ACTIVE;
        };
    }
}
