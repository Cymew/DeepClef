package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.Settings;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.ui.DeepSeekKeyScreen;
import adris.altoclef.util.DeepSeekApi;
import adris.altoclef.util.DshContextStore;
import net.minecraft.client.Minecraft;

/**
 * {@code @dsh} — shows the DeepSeek (@ds) configuration, token usage, context
 * occupancy and conversation history for this save.
 */
public class DshCommand extends Command {

    public DshCommand() {
        super("dsh", "查看 DeepSeek(@ds) 配置、用量与历史");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        Settings s = mod.getModSettings();
        mod.log("==== DeepSeek (@ds) 状态 ====");
        if (s.hasDeepSeekApiKey()) {
            mod.log("API Key: " + DeepSeekApi.maskKey(s.getDeepSeekApiKey()) + "（已配置）");
        } else {
            mod.log("API Key: 未配置（首次进世界会自动弹窗，或输入 @ds 唤起）");
            Minecraft.getInstance().execute(() -> Minecraft.getInstance().gui.setScreen(new DeepSeekKeyScreen(s)));
        }
        mod.log("Endpoint: " + s.getDeepSeekBaseUrl());
        mod.log("Model: " + s.getDeepSeekModel() + "（上下文窗口 1,000,000 tokens）");
        mod.log("MaxTokens/请求: " + s.getDeepSeekMaxTokens()
                + " | 超时: " + s.getDeepSeekTimeoutSeconds() + "s"
                + " | 压缩阈值: " + Math.round(s.getDeepSeekCompactionThreshold() * 100) + "%");

        DshContextStore.Context ctx = DshContextStore.load(mod);
        mod.log("本存档（" + DshContextStore.contextId(mod) + "）:");
        mod.log("- 对话轮次: " + ctx.turns + " | 历史消息: " + ctx.messages.size() + " 条");
        mod.log("- 累计 tokens: " + ctx.promptTokens + " 输入 + " + ctx.completionTokens + " 输出");
        long estimated = estimateContextTokens(ctx);
        long pct = Math.round(estimated * 1000.0 / 1_000_000L);
        mod.log("- 上下文占用估算: ~" + estimated + " / 1,000,000 tokens (" + (pct / 10.0) + "%)");
        mod.log("- 已压缩: " + ctx.compactions + " 次"
                + (ctx.summaries.isEmpty() ? "" : "（最近摘要: " + shortSummary(ctx) + "）"));
        mod.log("- 存储位置: " + DshContextStore.contextPath(mod));
        finish();
    }

    private static long estimateContextTokens(DshContextStore.Context ctx) {
        double tokens = 0;
        for (DshContextStore.StoredMessage message : ctx.messages) {
            tokens += estimate(message.content);
            if (message.toolCallsJson != null) tokens += estimate(message.toolCallsJson);
        }
        return (long) Math.ceil(tokens);
    }

    private static double estimate(String text) {
        if (text == null) return 0;
        double tokens = 0;
        for (char c : text.toCharArray()) {
            tokens += (c >= 0x2E80) ? 1.0 : 0.25;
        }
        return tokens;
    }

    private static String shortSummary(DshContextStore.Context ctx) {
        String last = ctx.summaries.get(ctx.summaries.size() - 1);
        if (last == null) return "";
        last = last.replaceAll("\\s+", " ").trim();
        return last.length() > 80 ? last.substring(0, 80) + "…" : last;
    }
}
