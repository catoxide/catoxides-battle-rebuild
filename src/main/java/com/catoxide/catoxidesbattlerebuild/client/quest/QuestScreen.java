package com.catoxide.catoxidesbattlerebuild.client.quest;

import com.catoxide.catoxidesbattlerebuild.network.QuestSyncPacket;
import com.catoxide.catoxidesbattlerebuild.network.QuestToggleStarPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 任务界面（类原版成就界面）。
 * <p>左侧：任务滚动列表（自定义，鼠标滚轮滚动 + 点击选中，自带滑条视觉）；
 * 右侧：选中任务详情（描述/状态/子任务）；按钮：星标切换、关闭。
 * <p>数据源：{@link ClientQuestCache}（服务端 QuestSyncPacket 同步）。
 */
public class QuestScreen extends Screen {

    private static final int LIST_X = 20;
    private static final int LIST_WIDTH = 240;
    private static final int LIST_Y0 = 40;
    private static final int ITEM_HEIGHT = 24;
    private static final int COLOR_BG = 0x88000000;

    private List<QuestSyncPacket.QuestEntry> roots;
    private int scrollOffset;
    private QuestSyncPacket.QuestEntry selected;
    private Button starButton;

    public QuestScreen() {
        super(Component.literal("任务"));
    }

    @Override
    protected void init() {
        this.roots = ClientQuestCache.getInstance().getRootQuests();
        this.selected = null;
        this.scrollOffset = 0;

        // 关闭按钮
        this.addRenderableWidget(Button.builder(Component.literal("关闭"), b -> this.onClose())
                .bounds(this.width - 100, this.height - 30, 80, 20)
                .build());

        // 星标按钮
        this.starButton = Button.builder(Component.literal("星标"), b -> toggleStar())
                .bounds(this.width - 100, this.height - 54, 80, 20)
                .build();
        this.starButton.active = false;
        this.addRenderableWidget(this.starButton);
    }

    private void toggleStar() {
        if (selected == null) {
            return;
        }
        // 客户端 → 服务端星标切换（服务端持久化 + 同步回推）
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            mc.getConnection().send(new QuestToggleStarPacket(selected.instanceId()));
        }
        // 本地预览
        selected = new QuestSyncPacket.QuestEntry(
                selected.instanceId(), selected.definitionId(), selected.title(), selected.description(),
                selected.state(), !selected.starred(), selected.parentInstanceId());
        updateStarButton();
        ClientQuestCache.getInstance().applyStar(selected.instanceId(), selected.starred());
        // 刷新列表快照（★ 标记立刻反映到列表，不等同步包回推）
        this.roots = ClientQuestCache.getInstance().getRootQuests();
    }

    private void updateStarButton() {
        if (starButton != null) {
            starButton.active = selected != null;
            starButton.setMessage(Component.literal(
                    selected != null && selected.starred() ? "取消星标" : "星标"));
        }
    }

    // ========== 渲染 ==========

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(this.font, "任务", LIST_X, 22, 0xFFFFFF);

        this.renderQuestList(graphics);
        this.renderDetails(graphics);
    }

    /** 左侧任务列表（滚动 + 选中高亮） */
    private void renderQuestList(GuiGraphics graphics) {
        int listY1 = this.height - 40;
        graphics.fill(LIST_X, LIST_Y0, LIST_X + LIST_WIDTH, listY1, COLOR_BG);

        int visible = (listY1 - LIST_Y0) / ITEM_HEIGHT;
        for (int i = 0; i < visible; i++) {
            int index = scrollOffset + i;
            if (index >= roots.size()) {
                break;
            }
            QuestSyncPacket.QuestEntry quest = roots.get(index);
            int y = LIST_Y0 + i * ITEM_HEIGHT;
            if (quest == selected) {
                graphics.fill(LIST_X, y, LIST_X + LIST_WIDTH, y + ITEM_HEIGHT, 0x33FFFFFF);
            }
            String line = quest.title() + "  " + stateCn(quest.state())
                    + (quest.starred() ? " ★" : "");
            graphics.drawString(this.font, line, LIST_X + 4, y + 7, stateColor(quest.state()));
        }
    }

    /** 右侧详情：选中任务 */
    private void renderDetails(GuiGraphics graphics) {
        if (selected == null) {
            return;
        }
        int x = LIST_X + LIST_WIDTH + 15;
        int y = 45;
        graphics.drawString(this.font, selected.title(), x, y, 0xFFFFFF);
        y += 14;
        if (!selected.description().isEmpty()) {
            graphics.drawString(this.font, "描述: " + selected.description(), x, y, 0xAAAAAA);
            y += 12;
        }
        graphics.drawString(this.font, "状态: " + stateCn(selected.state()), x, y, stateColor(selected.state()));
        y += 12;
        List<QuestSyncPacket.QuestEntry> children =
                ClientQuestCache.getInstance().getChildren(selected.instanceId());
        if (!children.isEmpty()) {
            graphics.drawString(this.font, "子任务:", x, y, 0xFFFFFF);
            y += 12;
            for (QuestSyncPacket.QuestEntry child : children) {
                graphics.drawString(this.font, "  ▸ " + child.title() + " " + stateCn(child.state()),
                        x, y, stateColor(child.state()));
                y += 11;
            }
        }
    }

    // ========== 交互 ==========

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int maxScroll = Math.max(0, roots.size() - (this.height - 40 - LIST_Y0) / ITEM_HEIGHT);
        this.scrollOffset = Math.max(0, Math.min(maxScroll, this.scrollOffset - (int) verticalAmount));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 点击列表条目 → 选中
        if (mouseX >= LIST_X && mouseX <= LIST_X + LIST_WIDTH
                && mouseY >= LIST_Y0 && mouseY <= this.height - 40) {
            int index = scrollOffset + (int) ((mouseY - LIST_Y0) / ITEM_HEIGHT);
            if (index >= 0 && index < roots.size()) {
                this.selected = roots.get(index);
                updateStarButton();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ========== 工具 ==========

    private static String stateCn(String state) {
        return switch (state) {
            case "COMPLETED" -> "完成";
            case "FAILED" -> "失败";
            default -> "进行中";
        };
    }

    private static int stateColor(String state) {
        return switch (state) {
            case "COMPLETED" -> 0xFF55FF55;
            case "FAILED" -> 0xFFFF5555;
            default -> 0xFFFFFFFF;
        };
    }
}
