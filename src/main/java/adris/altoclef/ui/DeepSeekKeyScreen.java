package adris.altoclef.ui;

import adris.altoclef.Debug;
import adris.altoclef.Settings;
import adris.altoclef.util.DeepSeekApi;
import adris.altoclef.util.helpers.ConfigHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * First-launch screen asking for a DeepSeek API key. The key is validated
 * against the configured endpoint and saved into {@code altoclef_settings.json}
 * inside the game folder.
 */
public class DeepSeekKeyScreen extends Screen {

    private final Settings settings;
    private EditBox keyField;
    private Button saveButton;
    private Component status = Component.literal("");
    private int statusColor = 0xFFFFFF;
    private boolean validating = false;

    public DeepSeekKeyScreen(Settings settings) {
        super(Component.literal("配置 DeepSeek API Key（DeepClef @ds）"));
        this.settings = settings != null ? settings : fallbackSettings();
        Debug.logMessage("[DeepClef @ds] Key screen created (settings=" + (this.settings != null) + ")");
    }

    public DeepSeekKeyScreen() {
        this(null);
    }

    private static Settings fallbackSettings() {
        if (Debug.jankModInstance == null) return null;
        return Debug.jankModInstance.getModSettings();
    }

    @Override
    protected void init() {
        this.keyField = new EditBox(this.font, this.width / 2 - 150, this.height / 2 - 40, 300, 20,
                Component.literal("DeepSeek API Key"));
        this.keyField.setMaxLength(200);
        this.keyField.setValue(this.settings == null ? "" : this.settings.getDeepSeekApiKey());
        this.addRenderableWidget(this.keyField);

        this.saveButton = this.addRenderableWidget(Button.builder(Component.literal("保存并校验"), b -> {
            Debug.logMessage("[DeepClef @ds] 保存并校验 button pressed");
            this.onSave();
        }).bounds(this.width / 2 - 150, this.height / 2 - 10, 148, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("稍后再说"), b -> this.onClose())
                .bounds(this.width / 2 + 2, this.height / 2 - 10, 148, 20).build());
    }

    @Override
    protected void setInitialFocus() {
        this.setInitialFocus(this.keyField);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isConfirmation() && !this.validating) {
            this.onSave();
            return true;
        }
        if (event.isEscape()) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    private void onSave() {
        if (this.validating) return;
        try {
            String key = this.keyField.getValue().trim();
            if (key.isEmpty()) {
                this.status = Component.literal("请粘贴你的 DeepSeek API Key（sk-...）");
                this.statusColor = 0xFF5555;
                Debug.logMessage("[DeepClef @ds] save: key field is empty");
                return;
            }
            if (this.settings == null) {
                this.status = Component.literal("✘ 内部错误：模组尚未初始化，请退出世界重进后再试");
                this.statusColor = 0xFF5555;
                Debug.logError("[DeepClef @ds] save: settings is null");
                return;
            }
            this.settings.setDeepSeekApiKey(key);
            ConfigHelper.saveConfig(Settings.SETTINGS_PATH, this.settings);
            Debug.logMessage("[DeepClef @ds] key saved to config, starting validation");

            this.validating = true;
            this.saveButton.active = false;
            this.status = Component.literal("正在校验 API Key…");
            this.statusColor = 0xFFFF55;
            DeepSeekApi.validateKey(this.settings).thenAccept(result -> Minecraft.getInstance().execute(() -> {
                this.validating = false;
                this.saveButton.active = true;
                if (result.isEmpty()) {
                    this.status = Component.literal("✔ 校验通过！Key 已保存到 altoclef/altoclef_settings.json");
                    this.statusColor = 0x55FF55;
                    Debug.logMessage("[DeepClef @ds] validation SUCCESS");
                } else {
                    this.status = Component.literal("✘ " + result);
                    this.statusColor = 0xFF5555;
                    Debug.logMessage("[DeepClef @ds] validation FAILED: " + result);
                }
            }));
        } catch (Throwable t) {
            this.status = Component.literal("✘ 内部错误: " + t);
            this.statusColor = 0xFF5555;
            Debug.logError("[DeepClef @ds] save threw: " + t);
            t.printStackTrace();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int x = this.width / 2 - 150;
        graphics.text(this.font, Component.literal("首次使用 @ds 需要配置 DeepSeek API Key").getVisualOrderText(),
                x, this.height / 2 - 62, 0xCCCCCC);
        graphics.text(this.font, Component.literal("Key 只保存在本地游戏文件夹（altoclef/altoclef_settings.json）").getVisualOrderText(),
                x, this.height / 2 - 76, 0x888888);
        graphics.text(this.font, this.status.getVisualOrderText(), x, this.height / 2 + 24, this.statusColor);
        graphics.text(this.font, Component.literal("回车 = 保存并校验，Esc = 稍后再说").getVisualOrderText(),
                x, this.height / 2 + 48, 0x888888);
    }
}
