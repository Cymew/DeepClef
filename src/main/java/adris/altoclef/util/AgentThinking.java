package adris.altoclef.util;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Local effort selection for the foreground agent. A bare resume may consult prior user instructions in the same conversation. */
public enum AgentThinking {
    PROVIDER("服务端默认"), QUICK("快速"), LOW("轻量"), HIGH("深入");

    private static final Set<String> MODELS = Set.of(
            "deepseek-flash", "deepseek-v4-pro", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp");
    private static final Pattern SIMPLE_QUERY = Pattern.compile(
            "(?:请问|看一下|看看|告诉我)?(?:现在|当前)?(?:几点(?:了|钟)?|(?:是)?什么(?:时间|天气)|天气(?:如何|怎么样)?|"
            + "下雨(?:了)?吗|(?:我|你)?在哪(?:里)?|坐标(?:是多少)?|血量(?:是多少)?|背包(?:里有(?:什么|啥))?|状态如何)[呢吗啊？?！!。.]*"
            + "|(?:what time is it|what(?:'s| is) the weather|where am i|check (?:my )?(?:health|inventory))[?.!]*");
    private static final Pattern PLANNING = Pattern.compile(
            "然后|之后|接着|再去|[，,]\\s*再|先.{1,80}再|如果|否则|规划|设计|比较|权衡|排查|自动化|流水线|分类系统|红石装置|从零|末影龙|跨维度|"
            + "开门后|打开.{0,8}门后|门[，,、]?进(?:去|屋)|并(?:去|进入|寻找|收集)|\\b(?:then|plan|compare|diagnose|if|ender dragon)\\b");

    private static final Pattern ROUTINE_ACTION = Pattern.compile(
            "(?:请)?(?:帮我)?(?:合成|制作|收割|搬运|采集|收集)[\\p{IsHan}\\w ]{1,24}[。！!]*"
            + "|(?:请)?(?:去|前往|走到|到达|移动到|goto|go to)\\s*(?:坐标)?\\s*[(（]?\\s*-?\\d+\\s*[,， ]\\s*-?\\d+\\s*[)）]?[。！!]*");

    private static final Pattern REPEAT_CRAFT = Pattern.compile(
            "(?:请)?(?:继续(?:合成|制作)(?:剩下的|剩余的|剩下|剩余)?(?:材料|部分)?|把(?:剩下|剩余)的(?:材料)?(?:按刚才的配方)?(?:合成|制作|做完))[。！!]*");
    private static final Pattern RESUME = Pattern.compile("(?:请)?(?:继续|接着做|继续做|continue|resume)[。！!.]*");

    final String label;
    AgentThinking(String label) { this.label = label; }

    public static AgentThinking forInstruction(String instruction) {
        String text = instruction.strip().toLowerCase(Locale.ROOT);
        if (SIMPLE_QUERY.matcher(text).matches() || REPEAT_CRAFT.matcher(text).matches()) return QUICK;
        if (PLANNING.matcher(text).find()) return HIGH;
        return ROUTINE_ACTION.matcher(text).matches() ? QUICK : LOW;
    }

    public static AgentThinking forInstruction(String instruction, List<DeepSeekApi.ChatMessage> messages) {
        String prior = resumedInstruction(instruction, messages);
        if (prior != null) {
            var previous = forInstruction(prior);
            if (previous == QUICK || (previous != HIGH && (prior.contains("合成") || prior.contains("制作")))) return QUICK;
        }
        return forInstruction(instruction);
    }

    static String resumedInstruction(String instruction, List<DeepSeekApi.ChatMessage> messages) {
        if (!RESUME.matcher(instruction.strip().toLowerCase(Locale.ROOT)).matches()) return null;
        // Read only actual instruction envelopes. Ignore inventory text, model speculation and other players' chat.
        for (int i = messages.size() - 1; i >= 0; i--) {
            var entry = messages.get(i);
            String body = entry.content();
            if (!"user".equals(entry.role()) || body == null) continue;
            String prior;
            String marker = "\n用户指令: ";
            int boundary = body.lastIndexOf(marker);
            if (body.startsWith("当前机器人状态:\n") && boundary >= 0) {
                prior = body.substring(boundary + marker.length());
            } else if (body.startsWith("【用户插话】")) {
                int end = body.indexOf("\n插话时视角：");
                prior = body.substring("【用户插话】".length(), end < 0 ? body.length() : end);
            } else continue;
            if (RESUME.matcher(prior.strip().toLowerCase(Locale.ROOT)).matches()) continue;
            return prior;
        }
        return null;
    }

    /** Pick the effort for the next decision using a single tool's own receipt. */
    public static AgentThinking afterReceipt(AgentThinking base, String tool, String receipt) {
        if (tool.equals("altoclef_walk")) {
            if (receipt.startsWith("未到达目标 XZ")) return HIGH;
            if (receipt.startsWith("已到达目标 XZ") && !receipt.contains("未全程保持") && !receipt.contains("未观测到")) return QUICK;
        }
        if (tool.equals("altoclef_execute") && receipt.startsWith("命令完成: ")) return QUICK;
        if (tool.equals("altoclef_action")) {
            if (receipt.startsWith("observed:") || receipt.startsWith("submitted: 以下为当前客户端状态")) return QUICK;
            if (base == QUICK && (receipt.startsWith("failed:") || receipt.startsWith("unknown:"))) return LOW;
        }
        return base;
    }

    public AgentThinking forEndpoint(String endpoint, String model) {
        try {
            if (model != null && MODELS.contains(model) && "api.deepseek.com".equalsIgnoreCase(URI.create(endpoint).getHost())) return this;
        } catch (IllegalArgumentException ignored) {
            // The HTTP client reports invalid configured URLs through its normal error path.
        }
        return PROVIDER;
    }

    public void applyTo(ObjectNode body) {
        if (this == PROVIDER) return;
        body.putObject("thinking").put("type", this == QUICK ? "disabled" : "enabled");
        if (this != QUICK) body.put("reasoning_effort", this == LOW ? "low" : "high");
    }
}
