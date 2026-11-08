package example.contentpack.tech.client;

import example.contentpack.tech.TechCost;
import example.contentpack.tech.TechItems;
import example.contentpack.tech.TechNode;
import example.contentpack.tech.TechRequirement;
import example.contentpack.tech.TechRequirementEngine;
import example.contentpack.tech.TechTree;
import example.contentpack.tech.TechTreeLoader;
import example.contentpack.tech.network.TechStateRequestPayload;
import example.contentpack.tech.network.TechSubmitPayload;
import example.contentpack.tech.network.TechSyncPayload;
import example.contentpack.tech.network.TechUnlockPayload;
import example.contentpack.tech.network.TechUpgradePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 科技树界面：任务书式的分页布局。
 *
 * <p>每页是独立的固定网格画布（JSON 的 col/row），节点位置 = 视口原点 + 网格步长 + 拖动偏移；
 * 页签在顶部，右侧是研究台/节点详情面板（含研究台升级按钮），底部是该页的可提交研究项。
 * 节点可见性与「条件是否满足」都由服务端快照判定，客户端只负责展示。
 */
public final class TechTreeScreen extends Screen {

    private static final int PAD = 12;
    private static final int TAB_Y = 26;
    private static final int TAB_H = 22;
    private static final int CONTENT_TOP = TAB_Y + TAB_H + 6;
    private static final int NODE_W = 84;
    private static final int NODE_H = 40;
    private static final int COL_STRIDE = 128;
    private static final int ROW_STRIDE = 64;
    private static final int PANEL_W = 188;
    private static final int MARGIN = 8;
    private static final int RESEARCH_H = 62;
    private static final int FOOTER = 10;
    private static final int RESEARCH_CARD_W = 156;
    private static final int RESEARCH_CARD_H = 54;
    private static final int RESEARCH_CARD_GAP = 6;

    private static final int BACKGROUND = 0xF0121212;
    private static final int TEXT = 0xFFEDEDED;
    private static final int TEXT_DIM = 0xFFB0B0B0;
    private static final int OK_COLOR = 0xFF8FD07A;
    private static final int BAD_COLOR = 0xFFFF7070;
    private static final int MET_COLOR = 0xFFE0C060;

    private final TechTree tree;

    private String currentPage;
    private double panX;
    private double panY;
    private boolean dragging;
    private TechNode selected;
    private boolean centeredAfterSync;
    private int syncTicks;
    /** 右侧面板滚动偏移（像素）；内容高于可视区时出现滚动条。 */
    private int panelScroll;
    private int lastPanelContentHeight;

    public TechTreeScreen() {
        super(Component.literal("Tech Tree"));
        this.tree = TechTreeLoader.load();
        this.currentPage = tree.pages().get(0).id();
        if (Minecraft.getInstance().getConnection() != null) {
            PacketDistributor.sendToServer(new TechStateRequestPayload());
        }
    }

    @Override
    protected void init() {
        resetView();
        selectDefault();
    }

    /** 每秒向服务端要一次快照：背包变化、冷却结束、条件触发都能刷新。 */
    @Override
    public void tick() {
        super.tick();
        if (++syncTicks >= 20) {
            syncTicks = 0;
            if (Minecraft.getInstance().getConnection() != null) {
                PacketDistributor.sendToServer(new TechStateRequestPayload());
            }
        }
    }

    // ==================== 布局 ====================

    private int panelX() {
        return width - PANEL_W - MARGIN;
    }

    private int panelInnerX() {
        return panelX() + 8;
    }

    private int panelInnerW() {
        return PANEL_W - 16;
    }

    private int viewLeft() {
        return PAD;
    }

    private int viewTop() {
        return CONTENT_TOP;
    }

    private int viewRight() {
        return panelX() - MARGIN;
    }

    private int researchTop() {
        return height - FOOTER - RESEARCH_H;
    }

    private int viewBottom() {
        return researchTop() - 6;
    }

    private int viewWidth() {
        return viewRight() - viewLeft();
    }

    private int viewHeight() {
        return viewBottom() - viewTop();
    }

    private int upgradeButtonY() {
        return CONTENT_TOP + 32;
    }

    private int panelDividerY() {
        return upgradeButtonY() + 27;
    }

    private int panelContentTop() {
        return panelDividerY() + 4;
    }

    private int panelContentBottom() {
        return height - FOOTER - 28;
    }

    private int nodeX(TechNode node) {
        return (int) Math.round(viewLeft() + node.col() * COL_STRIDE + panX);
    }

    private int nodeY(TechNode node) {
        return (int) Math.round(viewTop() + node.row() * ROW_STRIDE + panY);
    }

    private boolean onPage(TechNode node) {
        return node.page().equals(currentPage);
    }

    private List<TechNode> pageNodes() {
        List<TechNode> nodes = new ArrayList<>();
        for (TechNode node : tree.nodes()) {
            if (onPage(node) && TechClientState.isVisible(node.id())) {
                nodes.add(node);
            }
        }
        return nodes;
    }

    private List<TechTree.Research> visibleResearch() {
        List<TechTree.Research> entries = new ArrayList<>();
        for (TechTree.Research entry : tree.research()) {
            if (!entry.page().equals(currentPage)) {
                continue;
            }
            TechSyncPayload.ResearchState state = TechClientState.researchState(entry.id());
            if (!TechClientState.synced() || (state != null && state.visible())) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private void resetView() {
        int minCol = Integer.MAX_VALUE;
        int maxCol = Integer.MIN_VALUE;
        int minRow = Integer.MAX_VALUE;
        int maxRow = Integer.MIN_VALUE;
        boolean any = false;
        for (TechNode node : pageNodes()) {
            any = true;
            minCol = Math.min(minCol, node.col());
            maxCol = Math.max(maxCol, node.col());
            minRow = Math.min(minRow, node.row());
            maxRow = Math.max(maxRow, node.row());
        }
        if (!any) {
            panX = viewWidth() / 2.0 - NODE_W / 2.0;
            panY = viewHeight() / 2.0 - NODE_H / 2.0;
            return;
        }
        int contentW = (maxCol - minCol) * COL_STRIDE + NODE_W;
        int contentH = (maxRow - minRow) * ROW_STRIDE + NODE_H;
        panX = (viewWidth() - contentW) / 2.0 - minCol * COL_STRIDE;
        panY = (viewHeight() - contentH) / 2.0 - minRow * ROW_STRIDE;
    }

    private void selectDefault() {
        if (selected != null && onPage(selected) && TechClientState.isVisible(selected.id())) {
            return;
        }
        List<TechNode> nodes = pageNodes();
        selected = nodes.isEmpty() ? null : nodes.get(0);
    }

    private void setPage(String pageId) {
        if (pageId == null || pageId.equals(currentPage)) {
            return;
        }
        currentPage = pageId;
        selected = null;
        panelScroll = 0;
        resetView();
        selectDefault();
    }

    // ==================== 渲染 ====================

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (TechClientState.synced() && !centeredAfterSync) {
            centeredAfterSync = true;
            resetView();
            selectDefault();
        }
        graphics.fill(0, 0, width, height, BACKGROUND);
        drawHeader(graphics);

        graphics.fill(viewLeft() - 1, viewTop() - 1, viewRight() + 1, viewBottom() + 1, 0xFF000000);
        graphics.enableScissor(viewLeft(), viewTop(), viewRight(), viewBottom());
        drawEdges(graphics);
        for (TechNode node : pageNodes()) {
            drawNode(graphics, node, mouseX, mouseY);
        }
        graphics.disableScissor();

        drawResearchStrip(graphics);
        drawPanel(graphics);
    }

    private void drawHeader(GuiGraphics graphics) {
        graphics.drawString(font, tree.title(), PAD, 8, TEXT, false);
        String hint = tr("tech_tree_pack.ui.hint");
        graphics.drawString(font, hint, viewRight() - font.width(hint), 8, TEXT_DIM, false);
        drawTabs(graphics);
    }

    private void drawTabs(GuiGraphics graphics) {
        int x = PAD;
        for (TechTree.Page page : tree.pages()) {
            int tabW = tabWidth(page);
            boolean active = page.id().equals(currentPage);
            int fill = active ? 0xFF3A3A3A : 0xFF1E1E1E;
            int border = active ? 0xFFD0D0D0 : 0xFF3F3F3F;
            graphics.fill(x, TAB_Y, x + tabW, TAB_Y + TAB_H, fill);
            graphics.fill(x, TAB_Y, x + tabW, TAB_Y + 1, border);
            graphics.fill(x, TAB_Y + TAB_H - 1, x + tabW, TAB_Y + TAB_H, border);
            graphics.fill(x, TAB_Y, x + 1, TAB_Y + TAB_H, border);
            graphics.fill(x + tabW - 1, TAB_Y, x + tabW, TAB_Y + TAB_H, border);
            Item icon = TechItems.resolve(page.icon());
            if (icon != null) {
                graphics.renderItem(new ItemStack(icon), x + 3, TAB_Y + 3);
            }
            int color = active ? TEXT : TEXT_DIM;
            graphics.drawString(font, font.plainSubstrByWidth(page.title(), tabW - 24),
                    x + 22, TAB_Y + 6, color, false);
            x += tabW + 4;
        }
    }

    private int tabWidth(TechTree.Page page) {
        return Math.max(54, 24 + font.width(page.title()) + 8);
    }

    private void drawEdges(GuiGraphics graphics) {
        for (TechNode node : pageNodes()) {
            for (String require : prereqIds(node)) {
                TechNode from = tree.node(require);
                if (from == null || !onPage(from) || !TechClientState.isVisible(from.id())) {
                    continue;
                }
                int x1 = nodeX(from) + NODE_W;
                int y1 = nodeY(from) + NODE_H / 2;
                int x2 = nodeX(node);
                int y2 = nodeY(node) + NODE_H / 2;
                int mid = (x1 + x2) / 2;
                int color = TechClientState.isUnlocked(require) ? 0xFF6FBF5A : 0xFF4A4A4A;
                graphics.fill(x1, y1 - 1, mid, y1 + 1, color);
                graphics.fill(mid - 1, Math.min(y1, y2), mid + 1, Math.max(y1, y2), color);
                graphics.fill(mid, y2 - 1, x2, y2 + 1, color);
            }
        }
    }

    private void drawNode(GuiGraphics graphics, TechNode node, int mouseX, int mouseY) {
        int x = nodeX(node);
        int y = nodeY(node);
        boolean unlocked = TechClientState.isUnlocked(node.id());
        boolean met = TechClientState.isMet(node.id());

        int fill = unlocked ? 0xFF27401F : met ? 0xFF43391C : 0xFF232323;
        int border = selected == node ? 0xFFFFFFFF
                : unlocked ? 0xFF6FBF5A : met ? 0xFFC9A227 : 0xFF555555;
        graphics.fill(x, y, x + NODE_W, y + NODE_H, fill);
        graphics.fill(x, y, x + NODE_W, y + 1, border);
        graphics.fill(x, y + NODE_H - 1, x + NODE_W, y + NODE_H, border);
        graphics.fill(x, y, x + 1, y + NODE_H, border);
        graphics.fill(x + NODE_W - 1, y, x + NODE_W, y + NODE_H, border);

        Item icon = TechItems.resolve(node.icon());
        if (icon != null) {
            graphics.renderItem(new ItemStack(icon), x + 5, y + 6);
        }
        drawNodeTitle(graphics, node, x, y, mouseX, mouseY);

        String state = unlocked ? tr("tech_tree_pack.ui.unlocked")
                : met ? tr("tech_tree_pack.ui.available") : tr("tech_tree_pack.ui.locked");
        int stateColor = unlocked ? OK_COLOR : met ? MET_COLOR : 0xFF808080;
        graphics.drawString(font, font.plainSubstrByWidth(state, NODE_W - 10),
                x + 5, y + 26, stateColor, false);
    }

    /** 标题超出卡片宽度时，悬停或选中后横向滚动（与可提交研究按钮同一套策略）。 */
    private void drawNodeTitle(GuiGraphics graphics, TechNode node, int x, int y, int mouseX, int mouseY) {
        int maxW = NODE_W - 28;
        String title = node.title();
        int textW = font.width(title);
        boolean hovered = mouseX >= x && mouseX < x + NODE_W && mouseY >= y && mouseY < y + NODE_H;
        if (textW <= maxW || (!hovered && !node.equals(selected))) {
            graphics.drawString(font, font.plainSubstrByWidth(title, maxW), x + 25, y + 7, TEXT, false);
            return;
        }
        int span = textW + 24;
        int phase = (int) ((System.currentTimeMillis() / 30L) % span);
        int offset = phase < 12 ? 0 : phase - 12;
        graphics.enableScissor(x + 25, y + 4, x + NODE_W - 3, y + 20);
        graphics.drawString(font, title, x + 25 - offset, y + 7, TEXT, false);
        graphics.disableScissor();
    }

    private void drawResearchStrip(GuiGraphics graphics) {
        int y = researchTop();
        graphics.fill(viewLeft(), y, viewRight(), y + RESEARCH_H, 0xFF161616);
        graphics.fill(viewLeft(), y, viewRight(), y + 1, 0xFF3A3A3A);
        graphics.drawString(font, tr("tech_tree_pack.ui.research"), viewLeft() + 4, y + 3, TEXT_DIM, false);

        List<TechTree.Research> entries = visibleResearch();
        if (entries.isEmpty()) {
            graphics.drawString(font, tr("tech_tree_pack.ui.no_research"),
                    viewLeft() + 4, y + 24, TEXT_DIM, false);
            return;
        }
        int x = viewLeft();
        int cardY = y + 14;
        for (TechTree.Research entry : entries) {
            if (x + RESEARCH_CARD_W > viewRight()) {
                break;
            }
            drawResearchCard(graphics, entry, x, cardY);
            x += RESEARCH_CARD_W + RESEARCH_CARD_GAP;
        }
    }

    private void drawResearchCard(GuiGraphics graphics, TechTree.Research entry, int x, int y) {
        TechSyncPayload.ResearchState state = TechClientState.researchState(entry.id());
        boolean ready = state == null || state.ready();
        long remaining = state == null || state.readyAtMillis() <= 0
                ? 0L : state.readyAtMillis() - System.currentTimeMillis();
        boolean affordable = canAfford(entry.costs());
        boolean enabled = ready && affordable && TechClientState.boundPos() != null;

        graphics.fill(x, y, x + RESEARCH_CARD_W, y + RESEARCH_CARD_H, 0xFF1E1E1E);
        graphics.fill(x, y, x + RESEARCH_CARD_W, y + 1, 0xFF3F3F3F);
        graphics.fill(x, y + RESEARCH_CARD_H - 1, x + RESEARCH_CARD_W, y + RESEARCH_CARD_H, 0xFF3F3F3F);

        Item icon = TechItems.resolve(entry.icon());
        if (icon != null) {
            graphics.renderItem(new ItemStack(icon), x + 4, y + 5);
        }
        graphics.drawString(font, font.plainSubstrByWidth(entry.title(), RESEARCH_CARD_W - 26),
                x + 23, y + 6, TEXT, false);
        graphics.drawString(font, font.plainSubstrByWidth(describeCosts(entry.costs()), RESEARCH_CARD_W - 10),
                x + 5, y + 20, affordable ? OK_COLOR : BAD_COLOR, false);
        graphics.drawString(font, tr("tech_tree_pack.ui.points_reward", entry.points()),
                x + 5, y + 30, MET_COLOR, false);

        int bx = x + 4;
        int by = y + RESEARCH_CARD_H - 20;
        int bw = RESEARCH_CARD_W - 8;
        String label;
        int color;
        if (!ready) {
            label = tr("tech_tree_pack.ui.cooldown", (remaining + 999) / 1000);
            color = 0xFF3A3220;
        } else if (!affordable) {
            label = tr("tech_tree_pack.ui.insufficient");
            color = 0xFF402222;
        } else {
            label = tr("tech_tree_pack.ui.submit");
            color = enabled ? 0xFF365322 : 0xFF2A2A2A;
        }
        graphics.fill(bx, by, bx + bw, by + 16, color);
        graphics.fill(bx, by, bx + bw, by + 1, 0xFF777777);
        drawButtonLabel(graphics, label, bx + 2, by + 4, bw - 4, 0xFFFFFFFF);
    }

    private void drawPanel(GuiGraphics graphics) {
        int px = panelX();
        int py = CONTENT_TOP;
        int ph = height - FOOTER - CONTENT_TOP;
        graphics.fill(px, py, px + PANEL_W, py + ph, 0xFF161616);
        graphics.fill(px, py, px, py + ph, 0xFF3A3A3A);

        int ix = panelInnerX();
        int innerW = panelInnerW();
        int iy = CONTENT_TOP + 8;
        graphics.drawString(font, tr("tech_tree_pack.ui.tech_points", TechClientState.points()),
                ix, iy, TEXT_DIM, false);
        iy += 12;
        graphics.drawString(font, tr("tech_tree_pack.ui.table", roman(TechClientState.boundTier())),
                ix, iy, TEXT_DIM, false);

        drawUpgradeButton(graphics, ix, upgradeButtonY(), innerW);
        graphics.fill(ix, panelDividerY(), ix + innerW, panelDividerY() + 1, 0xFF3A3A3A);

        int contentTop = panelContentTop();
        int contentBottom = panelContentBottom();
        int viewH = contentBottom - contentTop;
        int y = contentTop - panelScroll;
        int contentStart = y;

        graphics.enableScissor(px + 1, contentTop, px + PANEL_W - 1, contentBottom);

        TechTree.TableSpec.Upgrade upgrade = nextUpgrade();
        if (upgrade != null) {
            graphics.drawString(font, tr("tech_tree_pack.ui.upgrade_costs", roman(upgrade.to())),
                    ix, y, TEXT, false);
            y += 12;
            for (TechCost cost : upgrade.costs()) {
                y = drawCostRow(graphics, cost, ix, y, innerW);
            }
            y += 4;
        }

        if (selected == null) {
            graphics.drawString(font, tr("tech_tree_pack.ui.no_selection"),
                    ix, y, TEXT_DIM, false);
            y += 12;
        } else {
            graphics.drawString(font, font.plainSubstrByWidth(selected.title(), innerW),
                    ix, y, TEXT, false);
            y += 14;
            for (FormattedCharSequence line : font.split(Component.literal(selected.desc()), innerW)) {
                graphics.drawString(font, line, ix, y, TEXT_DIM, false);
                y += 10;
            }
            y += 4;

            if (!selected.requirements().isEmpty()) {
                graphics.drawString(font, tr("tech_tree_pack.ui.requirements"), ix, y, TEXT, false);
                y += 12;
                boolean met = TechClientState.isMet(selected.id());
                for (TechRequirement requirement : selected.requirements()) {
                    y = drawRequirementRow(graphics, requirement, met, ix, y, innerW);
                }
            }

            if (!selected.costs().isEmpty()) {
                y += 2;
                graphics.drawString(font, tr("tech_tree_pack.ui.costs"), ix, y, TEXT, false);
                y += 12;
                for (TechCost cost : selected.costs()) {
                    y = drawCostRow(graphics, cost, ix, y, innerW);
                }
            }
        }

        graphics.disableScissor();

        lastPanelContentHeight = y - contentStart;
        int maxScroll = Math.max(0, lastPanelContentHeight - viewH);
        if (panelScroll > maxScroll) {
            panelScroll = maxScroll;
        }
        drawScrollbar(graphics, contentTop, contentBottom, viewH);
        drawUnlockButton(graphics);
    }

    private void drawUpgradeButton(GuiGraphics graphics, int x, int y, int w) {
        int bh = 18;
        TechTree.TableSpec.Upgrade upgrade = nextUpgrade();
        String label;
        int color;
        boolean enabled = false;
        if (upgrade == null) {
            label = tr("tech_tree_pack.ui.upgrade_max");
            color = 0xFF2A2A2A;
        } else {
            boolean ok = canAfford(upgrade.costs());
            label = tr("tech_tree_pack.ui.upgrade_table", roman(upgrade.to()));
            color = ok ? 0xFF365322 : 0xFF402222;
            enabled = ok && TechClientState.boundPos() != null;
        }
        graphics.fill(x, y, x + w, y + bh, color);
        graphics.fill(x, y, x + w, y + 1, 0xFF777777);
        graphics.fill(x, y + bh - 1, x + w, y + bh, 0xFF000000);
        drawButtonLabel(graphics, label, x + 2, y + 5, w - 4, enabled ? 0xFFFFFFFF : 0xFFB0B0B0);
    }

    /** 按钮文字：放得下就居中，放不下就自动横向滚动（marquee）。 */
    private void drawButtonLabel(GuiGraphics graphics, String label, int x, int y, int w, int color) {
        graphics.enableScissor(x, y - 1, x + w, y + 10);
        int textW = font.width(label);
        if (textW <= w) {
            graphics.drawString(font, label, x + (w - textW) / 2, y, color, false);
        } else {
            int gap = 24;
            int span = textW + gap;
            int offset = (int) ((System.currentTimeMillis() / 40) % span);
            graphics.drawString(font, label, x - offset, y, color, false);
            graphics.drawString(font, label, x - offset + span, y, color, false);
        }
        graphics.disableScissor();
    }

    private int drawCostRow(GuiGraphics graphics, TechCost cost, int x, int y, int w) {
        boolean ok = canPay(cost);
        int color = ok ? OK_COLOR : BAD_COLOR;
        if (cost instanceof TechCost.ItemCost itemCost) {
            Item item = TechItems.resolve(itemCost.itemId());
            if (item != null) {
                ItemStack stack = new ItemStack(item);
                graphics.renderItem(stack, x, y);
                String name = stack.getHoverName().getString();
                graphics.drawString(font, font.plainSubstrByWidth(name, w - 48),
                        x + 18, y + 4, color, false);
                graphics.drawString(font, "×" + cost.amount(), x + w - 26, y + 4, color, false);
                return y + 18;
            }
        }
        String line = (ok ? "[OK] " : "[X] ") + cost.describePlain();
        graphics.drawString(font, font.plainSubstrByWidth(line, w), x, y + 3, color, false);
        return y + 12;
    }

    private int drawRequirementRow(GuiGraphics graphics, TechRequirement requirement,
                                   boolean met, int x, int y, int w) {
        int color = met ? OK_COLOR : BAD_COLOR;
        if ("item".equals(requirement.type())) {
            Item item = TechItems.resolve(ResourceLocation.tryParse(requirement.string("item", "")));
            if (item != null) {
                ItemStack stack = new ItemStack(item);
                graphics.renderItem(stack, x, y);
                String suffix = requirement.consumesItem()
                        ? " ×" + requirement.integer("count", 1) : "";
                String name = stack.getHoverName().getString() + suffix;
                graphics.drawString(font, font.plainSubstrByWidth(name, w - 18),
                        x + 18, y + 4, color, false);
                return y + 18;
            }
        }
        String line = (met ? "[OK] " : "[X] ")
                + TechRequirementEngine.describe(requirement).getString();
        graphics.drawString(font, font.plainSubstrByWidth(line, w), x, y + 3, color, false);
        return y + 12;
    }

    private void drawScrollbar(GuiGraphics graphics, int top, int bottom, int viewH) {
        if (lastPanelContentHeight <= viewH) {
            return;
        }
        int x = panelX() + PANEL_W - 4;
        graphics.fill(x, top, x + 2, bottom, 0xFF2A2A2A);
        int thumbH = Math.max(16, viewH * viewH / lastPanelContentHeight);
        int maxScroll = lastPanelContentHeight - viewH;
        int thumbY = top + (int) ((long) (viewH - thumbH) * panelScroll / maxScroll);
        graphics.fill(x, thumbY, x + 2, thumbY + thumbH, 0xFF8A8A8A);
    }

    private void drawUnlockButton(GuiGraphics graphics) {
        int bx = panelInnerX();
        int by = height - FOOTER - 24;
        int bw = panelInnerW();
        boolean unlocked = TechClientState.isUnlocked(selected.id());
        boolean met = TechClientState.isMet(selected.id());
        boolean affordable = canAfford(selected);

        String label;
        int color;
        if (unlocked) {
            label = tr("tech_tree_pack.ui.unlocked");
            color = 0xFF2C3F26;
        } else if (!met) {
            label = tr("tech_tree_pack.ui.locked");
            color = 0xFF2A2A2A;
        } else if (!affordable) {
            label = tr("tech_tree_pack.ui.insufficient");
            color = 0xFF402222;
        } else {
            label = tr("tech_tree_pack.ui.unlock");
            color = 0xFF365322;
        }
        graphics.fill(bx, by, bx + bw, by + 20, color);
        graphics.fill(bx, by, bx + bw, by + 1, 0xFF777777);
        graphics.fill(bx, by + 19, bx + bw, by + 20, 0xFF000000);
        drawButtonLabel(graphics, label, bx + 2, by + 6, bw - 4, 0xFFFFFFFF);
    }

    // ==================== 交互 ====================

    private boolean insideView(double mouseX, double mouseY) {
        return mouseX >= viewLeft() && mouseX < viewRight()
                && mouseY >= viewTop() && mouseY < viewBottom();
    }

    private TechTree.Page tabAt(double mouseX, double mouseY) {
        if (mouseY < TAB_Y || mouseY >= TAB_Y + TAB_H) {
            return null;
        }
        int x = PAD;
        for (TechTree.Page page : tree.pages()) {
            int tabW = tabWidth(page);
            if (mouseX >= x && mouseX < x + tabW) {
                return page;
            }
            x += tabW + 4;
        }
        return null;
    }

    private TechNode nodeAt(double mouseX, double mouseY) {
        for (TechNode node : pageNodes()) {
            int x = nodeX(node);
            int y = nodeY(node);
            if (mouseX >= x && mouseX < x + NODE_W && mouseY >= y && mouseY < y + NODE_H) {
                return node;
            }
        }
        return null;
    }

    private TechTree.Research researchButtonAt(double mouseX, double mouseY) {
        int y = researchTop() + 14;
        int x = viewLeft();
        for (TechTree.Research entry : visibleResearch()) {
            if (x + RESEARCH_CARD_W > viewRight()) {
                break;
            }
            int by = y + RESEARCH_CARD_H - 20;
            if (mouseX >= x + 4 && mouseX < x + RESEARCH_CARD_W - 4
                    && mouseY >= by && mouseY < by + 16) {
                return entry;
            }
            x += RESEARCH_CARD_W + RESEARCH_CARD_GAP;
        }
        return null;
    }

    private boolean insideUpgradeButton(double mouseX, double mouseY) {
        return mouseX >= panelInnerX() && mouseX < panelInnerX() + panelInnerW()
                && mouseY >= upgradeButtonY() && mouseY < upgradeButtonY() + 18;
    }

    private boolean insideUnlockButton(double mouseX, double mouseY) {
        return mouseX >= panelInnerX() && mouseX < panelInnerX() + panelInnerW()
                && mouseY >= height - FOOTER - 24 && mouseY < height - FOOTER - 4;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        TechTree.Page tab = tabAt(mouseX, mouseY);
        if (tab != null) {
            setPage(tab.id());
            return true;
        }
        if (insideUpgradeButton(mouseX, mouseY)) {
            if (canUpgrade()) {
                PacketDistributor.sendToServer(new TechUpgradePayload(TechClientState.boundPos()));
            }
            return true;
        }
        TechTree.Research researchEntry = researchButtonAt(mouseX, mouseY);
        if (researchEntry != null) {
            if (canSubmit(researchEntry)) {
                PacketDistributor.sendToServer(new TechSubmitPayload(
                        researchEntry.id(), TechClientState.boundPos()));
            }
            return true;
        }
        if (selected != null && insideUnlockButton(mouseX, mouseY)) {
            if (canUnlock(selected)) {
                PacketDistributor.sendToServer(new TechUnlockPayload(
                        selected.id(), TechClientState.boundPos()));
            }
            return true;
        }
        TechNode hit = nodeAt(mouseX, mouseY);
        if (hit != null) {
            selected = hit;
            panelScroll = 0;
            return true;
        }
        if (insideView(mouseX, mouseY)) {
            dragging = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double dragX, double dragY) {
        if (dragging) {
            panX += dragX;
            panY += dragY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= panelX() && mouseX < panelX() + PANEL_W
                && mouseY >= panelContentTop() && mouseY < panelContentBottom()) {
            int viewH = panelContentBottom() - panelContentTop();
            int maxScroll = Math.max(0, lastPanelContentHeight - viewH);
            panelScroll = (int) Math.max(0, Math.min(maxScroll, panelScroll - scrollY * 16));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ==================== 判定 ====================

    private boolean canUnlock(TechNode node) {
        return TechClientState.isMet(node.id()) && canAfford(node) && TechClientState.boundPos() != null;
    }

    private boolean canSubmit(TechTree.Research entry) {
        if (TechClientState.boundPos() == null) {
            return false;
        }
        TechSyncPayload.ResearchState state = TechClientState.researchState(entry.id());
        if (state != null && !state.ready()) {
            return false;
        }
        return canAfford(entry.costs());
    }

    private boolean canAfford(TechNode node) {
        return canAfford(node.costs());
    }

    private static boolean canAfford(List<TechCost> costs) {
        for (TechCost cost : costs) {
            if (!canPay(cost)) {
                return false;
            }
        }
        return true;
    }

    /** 科技点是同步下来的虚拟余额，不能像物品那样查客户端背包。 */
    private static boolean canPay(TechCost cost) {
        if (cost instanceof TechCost.TechPointCost techPoints) {
            return TechClientState.points() >= techPoints.amount();
        }
        LocalPlayer player = player();
        return player != null && cost.canPay(player);
    }

    private boolean canUpgrade() {
        TechTree.TableSpec.Upgrade upgrade = nextUpgrade();
        return upgrade != null
                && TechClientState.boundPos() != null
                && canAfford(upgrade.costs());
    }

    private TechTree.TableSpec.Upgrade nextUpgrade() {
        return tree.table().upgradeFrom(TechClientState.boundTier());
    }

    private static List<String> prereqIds(TechNode node) {
        List<String> out = new ArrayList<>();
        for (TechRequirement requirement : node.requirements()) {
            collectTechIds(requirement, out);
        }
        return out;
    }

    private static void collectTechIds(TechRequirement requirement, List<String> out) {
        if ("tech".equals(requirement.type())) {
            out.addAll(requirement.strings("nodes"));
        } else if ("all".equals(requirement.type()) || "any".equals(requirement.type())) {
            for (TechRequirement child : requirement.children()) {
                collectTechIds(child, out);
            }
        }
    }

    private static String describeCosts(List<TechCost> costs) {
        StringBuilder builder = new StringBuilder();
        for (TechCost cost : costs) {
            if (builder.length() > 0) {
                builder.append(" · ");
            }
            builder.append(cost.describePlain());
        }
        return builder.toString();
    }

    private static String roman(int tier) {
        return switch (tier) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            default -> String.valueOf(tier);
        };
    }

    private static LocalPlayer player() {
        return Minecraft.getInstance().player;
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }
}
