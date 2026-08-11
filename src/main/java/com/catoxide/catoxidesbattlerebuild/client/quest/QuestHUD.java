package com.catoxide.catoxidesbattlerebuild.client.quest;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.network.QuestSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务状态简易 HUD（左上角，测试用）。
 * <p>显示当前玩家的激活任务树：根任务（标题+状态）+ 缩进子任务（标题+状态）。
 * <p>数据源：{@link ClientQuestCache}（服务端经 QuestSyncPacket 定时同步）。
 */
@EventBusSubscriber(modid = CatoxidesBattleRebuildConstants.MODID, value = Dist.CLIENT)
public class QuestHUD {

    private static final int PADDING = 3;
    private static final int LINE_HEIGHT = 10;
    private static final int BACKGROUND_WIDTH = 240;
    private static final int COLOR_BG = 0x88000000;
    private static final int COLOR_ACTIVE = 0xFFFFFFFF;
    private static final int COLOR_COMPLETED = 0xFF55FF55;
    private static final int COLOR_FAILED = 0xFFFF5555;
    private static final int COLOR_SUBTASK = 0xFFAAAAAA;

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

        // 收集显示行：根 + 缩进子任务
        List<HudLine> lines = new ArrayList<>();
        for (QuestSyncPacket.QuestEntry root : roots) {
            lines.add(new HudLine(formatQuest(root), colorOf(root)));
            for (QuestSyncPacket.QuestEntry child :
                    ClientQuestCache.getInstance().getChildren(root.questId())) {
                lines.add(new HudLine("  ▸ " + formatSub(child), COLOR_SUBTASK));
            }
        }

        GuiGraphics graphics = event.getGuiGraphics();
        int x = 4;
        int y = 4;
        int height = lines.size() * LINE_HEIGHT + PADDING * 2;

        graphics.fill(x, y, x + BACKGROUND_WIDTH, y + height, COLOR_BG);

        int textY = y + PADDING;
        for (HudLine line : lines) {
            graphics.drawString(mc.font, line.text(), x + PADDING, textY, line.color());
            textY += LINE_HEIGHT;
        }
    }

    /** HUD 单行：文本 + 颜色 */
    private record HudLine(String text, int color) {
    }

    private static String formatQuest(QuestSyncPacket.QuestEntry entry) {
        return "[" + entry.title() + "] " + stateCn(entry.state());
    }

    private static String formatSub(QuestSyncPacket.QuestEntry entry) {
        return entry.title() + " " + stateCn(entry.state());
    }

    private static String stateCn(String state) {
        return switch (state) {
            case "COMPLETED" -> "§a完成";
            case "FAILED" -> "§c失败";
            default -> "§f进行中";
        };
    }

    private static int colorOf(QuestSyncPacket.QuestEntry entry) {
        return switch (entry.state()) {
            case "COMPLETED" -> COLOR_COMPLETED;
            case "FAILED" -> COLOR_FAILED;
            default -> COLOR_ACTIVE;
        };
    }
}
