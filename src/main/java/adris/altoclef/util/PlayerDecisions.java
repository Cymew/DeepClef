package adris.altoclef.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

/** Client-thread state. Mail is acknowledged only after a valid decision about that message. */
final class PlayerDecisions {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    // Memory reads and HTTP completions can arrive after a stop, rejoin or dimension change.
    private long generation;
    private boolean planning;
    boolean planning() { return planning; }
    long begin() {
        if (planning) throw new IllegalStateException("another player decision is pending");
        planning = true;
        return ++generation;
    }
    boolean owns(long ticket) { return planning && ticket == generation; }
    void release() { planning = false; }
    private final Deque<String> mail = new ArrayDeque<>();
    private final Deque<Charge> charges = new ArrayDeque<>();
    static final int OUTPUT_LIMIT = 768;
    private static final long HOUR = 3_600_000;
    static final int TOKEN_LIMIT = 1_000_000;

    record Decision(int choice, String reason, String reply, String target, boolean surveyOnly, String project, java.util.List<PlayerProject.Milestone> milestones) {}
    static final class Charge {
        final long time;
        int tokens;
        Charge(long time, int tokens) { this.time = time; this.tokens = tokens; }
    }

    String offer(String message) {
        if (message.isBlank()) return "用法：@ds <给玩家的留言>，或 @ds progress";
        if (message.length() > 512) return "留言超过 512 字符，请分成较短的请求。";
        if (mail.contains(message)) return "这条留言已经在队列中。";
        if (mail.size() >= 8) return "还有八条留言待看，请稍后再发。";
        mail.addLast(message);
        return "已收到留言，当前动作结束后会考虑。";
    }

    int pending() { return mail.size(); }
    String first() { return mail.peekFirst(); }
    void acknowledge(String seen) { if (seen != null && seen.equals(first())) mail.removeFirst(); }
    void clearMail() { mail.clear(); }

    private void expire(long now) {
        while (!charges.isEmpty() && now - charges.getFirst().time >= HOUR) charges.removeFirst();
    }
    int tokens() { return charges.stream().mapToInt(c -> c.tokens).sum(); }
    int requests(long now) { expire(now); return charges.size(); }
    Charge reserve(long now, int inputBytes) {
        return reserve(now, inputBytes, false);
    }
    Charge reserve(long now, int inputBytes, boolean keepRoomForChat) {
        expire(now);
        // UTF-8 byte length plus protocol overhead conservatively reserves the short prompt.
        int allowance = inputBytes + 128 + OUTPUT_LIMIT;
        if (tokens() + allowance > (keepRoomForChat ? TOKEN_LIMIT * 4 / 5 : TOKEN_LIMIT)) return null;
        Charge charge = new Charge(now, allowance);
        charges.addLast(charge);
        return charge;
    }
    long retryBudgetAt(long now) { return (charges.isEmpty() ? now : charges.getFirst().time) + HOUR; }

    static Decision parse(String answer, int choices, boolean needsReply) throws IOException {
        var json = JSON.readTree(answer);
        if (json == null || !json.path("choice").isIntegralNumber() || !json.path("choice").canConvertToInt())
            throw new IOException("缺少有效候选编号");
        int index = json.path("choice").intValue();
        String reason = json.path("reason").asText("").strip();
        String reply = json.path("reply").asText("").strip();
        if (index < 0 || index >= choices || reason.isBlank() || reason.length() > 160 ||
                reply.length() > 256 || needsReply && reply.isBlank()) throw new IOException("选择或回复无效");
        String target = json.path("target").asText("").strip();
        if (target.length() > 128) throw new IOException("查询目标过长");
        if (json.has("survey_only") && !json.path("survey_only").isBoolean()) throw new IOException("查询方式无效");
        var steps = new java.util.ArrayList<PlayerProject.Milestone>();
        var entries = json.path("milestones");
        String title = json.path("project").asText("").strip();
        if (!title.isEmpty() && title.length() <= 80 && entries.isArray() && entries.size() <= 4) {
            for (var entry : entries) {
                String item = entry.path("item").asText("");
                var n = entry.path("count");
                if (!item.matches("explore|logs|planks|[a-z0-9_.-]+:[a-z0-9_./-]+") || !n.isIntegralNumber()
                        || !n.canConvertToInt() || n.intValue() < 1 || n.intValue() > (item.equals("explore") ? 4 : 64)) {
                    steps.clear();
                    break;
                }
                steps.add(new PlayerProject.Milestone(item, n.intValue()));
            }
        }
        boolean survey = json.path("survey_only").asBoolean(false);
        // Autonomous decisions already have a panorama. Use the numbered action if the
        // model asks to stand still and survey it again. A friend's query stays read-only.
        if (survey && !needsReply) target = "";
        return new Decision(index, reason, reply, target, survey && needsReply,
                title, java.util.List.copyOf(steps));
    }
}
