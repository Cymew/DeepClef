package adris.altoclef.ui;

import adris.altoclef.mode.WorldMode;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

public final class WorldModeScreen extends Screen {
    private final Consumer<WorldMode> select;

    public WorldModeScreen(Consumer<WorldMode> select) {
        super(Component.literal("选择这个世界的 DeepClef 模式"));
        this.select = select;
    }

    @Override
    protected void init() {
        int top = height / 2;
        int buttonWidth = Math.min(width / 2 - 12, 108);
        int left = width / 2 - buttonWidth - 3;
        for (WorldMode mode : WorldMode.values()) {
            addRenderableWidget(Button.builder(Component.literal(mode.label), b -> {
                onClose();
                select.accept(mode);
            }).bounds(left, top, buttonWidth, 20).build());
            left += buttonWidth + 6;
        }
        addRenderableWidget(Button.builder(Component.literal("本次手动游玩"), b -> onClose())
                .bounds(width / 2 - 64, top + 28, 128, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        String[] lines = {getTitle().getString(), "助手等候指令。玩家模式将逐步加入自主游戏。",
                "选定后绑定此存档或服务器。改选需手动编辑配置后重进。"};
        int y = height / 2 - 56;
        for (String line : lines) {
            g.text(font, Component.literal(line).getVisualOrderText(), (width - font.width(line)) / 2, y, 0xFFFFFFFF);
            y += 16;
        }
    }
}
