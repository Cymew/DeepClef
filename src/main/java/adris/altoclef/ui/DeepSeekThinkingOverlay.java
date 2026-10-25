package adris.altoclef.ui;

import adris.altoclef.Debug;
import adris.altoclef.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * Right-side semi-transparent overlay showing the DeepSeek deep thinking
 * stream ({@code reasoning_content}) while an {@code @ds} request runs.
 * Font scale is configurable via {@code @dssize} (Settings.deepseekOverlayScale);
 * the panel hugs the right screen edge.
 */
public class DeepSeekThinkingOverlay {

    private static final DeepSeekThinkingOverlay INSTANCE = new DeepSeekThinkingOverlay();
    private static final int MAX_LINES = 24;
    private static final int LOGICAL_PANEL_WIDTH = 340;

    private final List<String> rawLines = new ArrayList<>();
    private String thinkingBuffer = "";
    private String answerBuffer = "";
    private String status = "";

    private DeepSeekThinkingOverlay() {
    }

    public static DeepSeekThinkingOverlay getInstance() {
        return INSTANCE;
    }

    public synchronized void clear() {
        rawLines.clear();
        thinkingBuffer = "";
        answerBuffer = "";
        status = "";
    }

    public synchronized void appendLine(String line) {
        for (String part : line.split("\n", -1)) {
            rawLines.add(part);
        }
        while (rawLines.size() > MAX_LINES) rawLines.remove(0);
    }

    public synchronized void appendThinking(String delta) {
        thinkingBuffer += delta;
    }

    public synchronized void appendAnswer(String delta) {
        answerBuffer += delta;
    }

    public synchronized void setStatus(String status) {
        this.status = status;
    }

    /**
     * Freeze the streamed answer into the log lines (called when the request ends).
     */
    public synchronized void finalizeAnswer(String answer) {
        if (!answer.isBlank()) {
            appendLine("回复: " + answer);
        }
        thinkingBuffer = "";
        answerBuffer = "";
    }

    public synchronized boolean hasContent() {
        return !rawLines.isEmpty() || !thinkingBuffer.isEmpty() || !answerBuffer.isEmpty() || !status.isEmpty();
    }

    public void render(GuiGraphicsExtractor graphics) {
        float scale;
        List<String> lines;
        String statusLine;
        synchronized (this) {
            if (!hasContent()) return;
            scale = overlayScale();
            lines = new ArrayList<>(rawLines);
            statusLine = status;
            if (!answerBuffer.isEmpty()) {
                lines.add("回复(流式): " + answerBuffer);
            }
            if (!thinkingBuffer.isEmpty()) {
                lines.add("思考: " + thinkingBuffer);
            }
        }

        Font font = Minecraft.getInstance().font;
        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int lineHeight = font.lineHeight + 2;
        // Panel scales with the font size (original behavior): width = 340 * scale
        // screen pixels. Text is scaled inside, so the wrap limit must be converted
        // to unscaled font pixels: (340 - 12/scale) so lines always fit the panel.
        int panelWidth = (int) (LOGICAL_PANEL_WIDTH * scale);
        int x = screenWidth - panelWidth - 22;
        int y = 20;

        int maxUnscaledWidth = (int) (LOGICAL_PANEL_WIDTH - 12.0 / scale);
        List<String> wrapped = new ArrayList<>();
        for (String line : lines) {
            wrapped.addAll(wrap(font, line, maxUnscaledWidth));
        }
        while (wrapped.size() > MAX_LINES) wrapped.remove(0);
        if (!statusLine.isEmpty()) wrapped.add(0, statusLine);

        int panelHeight = (int) (wrapped.size() * lineHeight * scale) + 8;
        graphics.fill(x - 4, y - 4, x + panelWidth + 4, y + panelHeight, 0x40000000);

        // Scale the text via the public pose stack (same trick as the map renderer).
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y + 4);
        graphics.pose().scale(scale, scale);
        int textColor = 0x80E0E0E0;
        int lineY = 0;
        for (String line : wrapped) {
            graphics.text(font, line, 0, lineY, textColor, false);
            lineY += lineHeight;
        }
        graphics.pose().popMatrix();
    }

    private static float overlayScale() {
        try {
            if (Debug.jankModInstance != null && Debug.jankModInstance.getModSettings() != null) {
                float s = Debug.jankModInstance.getModSettings().getDeepSeekOverlayScale();
                if (s >= 0.4f && s <= 2.0f) return s;
            }
        } catch (Throwable ignored) {
        }
        return 0.75f;
    }

    private static List<String> wrap(Font font, String text, int maxWidth) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (char c : text.toCharArray()) {
            String candidate = current.toString() + c;
            if (font.width(candidate) > maxWidth && current.length() > 0) {
                result.add(current.toString());
                current.setLength(0);
            }
            current.append(c);
        }
        result.add(current.toString());
        return result;
    }
}
