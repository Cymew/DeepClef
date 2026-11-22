package adris.altoclef.util;

import java.util.Locale;
import java.util.regex.Pattern;

/** Recognize complete local control messages so a compound gameplay instruction reaches the planner. */
public enum AgentTaskControl {
    PROGRESS, PAUSE, RESUME, INSTRUCTION;

    private static final Pattern PROGRESS_QUESTION = Pattern.compile(
            "(?:请)?(?:你)?(?:告诉我|说一下|看看)?(?:现在|目前|当前)?(?:是)?"
            + "(?:哪里卡住了|卡在哪里了?|卡在哪(?:里|一步)?了?|做到哪一步了?|进行到哪一步了?|"
            + "任务进度(?:怎么样|如何)?了?|进度(?:怎么样|如何)了?|在做什么|还在合成吗)");

    public static AgentTaskControl parse(String instruction, boolean running, boolean paused) {
        String text = instruction.strip().toLowerCase(Locale.ROOT).replaceFirst("[。！？!?]+$", "").strip();
        return switch (text) {
            case "status", "progress", "进度", "查看进度" -> PROGRESS;
            case "pause", "暂停", "暂停任务", "我来接管" -> PAUSE;
            case "resume", "恢复任务" -> RESUME;
            case "继续", "继续任务" -> paused ? RESUME : INSTRUCTION;
            default -> running && PROGRESS_QUESTION.matcher(text).matches() ? PROGRESS : INSTRUCTION;
        };
    }
}
