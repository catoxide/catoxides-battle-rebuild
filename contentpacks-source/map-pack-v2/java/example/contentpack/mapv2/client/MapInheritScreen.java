package example.contentpack.mapv2.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 新世界发现「同 seed + generator」的已有地图时的一次性询问。
 *
 * <p>只列出候选，并说明会把地形复制进当前世界；选择后不再询问。
 */
public final class MapInheritScreen extends Screen {

    private static final int MAX_SHOWN = 6;
    private static final int BUTTON_WIDTH = 260;
    private static final int BUTTON_HEIGHT = 20;

    private final List<MapWorldMeta> candidates;

    public MapInheritScreen(List<MapWorldMeta> candidates) {
        super(Component.translatable("catoxidesbattlerebuild.map.inherit.title"));
        this.candidates = candidates;
    }

    @Override
    protected void init() {
        int shown = Math.min(MAX_SHOWN, candidates.size());
        int startY = this.height / 2 - shown * (BUTTON_HEIGHT + 4) / 2;
        for (int i = 0; i < shown; i++) {
            MapWorldMeta candidate = candidates.get(i);
            String label = candidate.name() + "  (" + candidate.seed() + ")";
            int index = i;
            this.addRenderableWidget(Button.builder(Component.literal(label), button -> {
                        MapWorld.inheritFrom(candidates.get(index).worldId());
                        Minecraft.getInstance().setScreen(null);
                    })
                    .bounds(this.width / 2 - BUTTON_WIDTH / 2, startY + i * (BUTTON_HEIGHT + 4),
                            BUTTON_WIDTH, BUTTON_HEIGHT)
                    .build());
        }
        this.addRenderableWidget(Button.builder(
                        Component.translatable("catoxidesbattlerebuild.map.inherit.skip"),
                        button -> {
                            MapWorld.skipInheritance();
                            Minecraft.getInstance().setScreen(null);
                        })
                .bounds(this.width / 2 - BUTTON_WIDTH / 2, this.height - 40,
                        BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 留空：原版会在地图界面后面糊一层世界，这里不要
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xD0101010);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 4, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.translatable("catoxidesbattlerebuild.map.inherit.hint"),
                this.width / 2, this.height / 4 + 14, 0xFFAAAAAA);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        MapWorld.skipInheritance();
        super.onClose();
    }
}
