package com.catoxide.catoxidesbattlerebuild.client.quest;

import com.catoxide.catoxidesbattlerebuild.CatoxidesBattleRebuildConstants;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestInstance;
import com.catoxide.catoxidesbattlerebuild.core.quest.QuestSystem;
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
 * <p>单机（集成服务器）下直接读服务端 {@link QuestSystem} 单例（同 JVM）。
 * 多人游戏需要任务同步包（TODO：正式 UI 时实现 QuestSyncPacket + 客户端缓存）。
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
        QuestSystem questSystem = QuestSystem.getInstance();
        List<QuestInstance> quests = questSystem.getActiveQuestsSorted(
                mc.player.getUUID(), mc.player.position());
        if (quests.isEmpty()) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        int x = 4;
        int y = 4;
        int height = quests.size() * LINE_HEIGHT + PADDING * 2;

        // 简易半透明背景
        graphics.fill(x, y, x + BACKGROUND_WIDTH, y + height, COLOR_BG);

        int textY = y + PADDING;
        for (QuestInstance quest : quests) {
            String line = format(quest);
            graphics.drawString(mc.font, line, x + PADDING, textY, colorOf(quest));
            textY += LINE_HEIGHT;
        }
    }

    private static String format(QuestInstance quest) {
        String title = quest.getDefinition().title().getString();
        String status = quest.isCompleted() ? "完成" : quest.isFailed() ? "失败" : "进行中";
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(title).append("] ").append(status);
        if (!quest.getChildInstances().isEmpty()) {
            long done = quest.getChildInstances().stream().filter(QuestInstance::isCompleted).count();
            sb.append(" (子任务 ").append(done).append('/').append(quest.getChildInstances().size()).append(')');
        }
        return sb.toString();
    }

    private static int colorOf(QuestInstance quest) {
        if (quest.isCompleted()) {
            return COLOR_COMPLETED;
        }
        if (quest.isFailed()) {
            return COLOR_FAILED;
        }
        return COLOR_ACTIVE;
    }
}
