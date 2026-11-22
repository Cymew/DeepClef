package adris.altoclef.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** A bounded inbox for visible multiplayer speech. Audience is captured on receipt, before the roster can change. */
final class PlayerConversation {
    private static final ObjectMapper JSON = new ObjectMapper();
    record Message(long id, long time, String sender, String text, String channel, UUID author, int online) {
        boolean direct() { return online == 2 || channel.equals("whisper"); }
        // Until a native team-send path exists, answer team messages privately to their author.
        String recipient() { return channel.equals("public") ? "" : sender; }
        String thread() { return channel.equals("public") ? "public" : channel + "/" + author; }
    }
    record Batch(List<Message> lines, String context) {
        Message first() { return lines.getFirst(); }
        long lastId() { return lines.getLast().id(); }
        String prompt(String self, String activity) {
            var root = JSON.createObjectNode();
            root.put("你的名字", self);
            root.put("游戏状态", activity);
            root.put("同频道近期对话", context);
            var messages = root.putArray("新消息");
            for (Message line : lines) {
                var m = messages.addObject();
                m.put("玩家", line.sender);
                m.put("内容", line.text);
                m.put("频道", line.channel);
                m.put("收到时在线人数", line.online);
                m.put("发言对象", line.direct() ? "正在对你说" : "根据内容和上下文判断");
            }
            return root.toString();
        }
    }

    private String lastExchange = "";
    private final ArrayDeque<Message> pending = new ArrayDeque<>();
    private final LinkedHashMap<String,ArrayDeque<String>> history = new LinkedHashMap<>();

    void add(MultiplayerChatBuffer.Line line, int online) {
        if (pending.size() >= MultiplayerChatBuffer.CAPACITY) pending.removeFirst();
        pending.addLast(new Message(line.id(), line.receivedAt(), line.senderName(), line.content(), line.channel(), line.senderId(), online));
    }

    boolean due(long now) { return !pending.isEmpty() && now - pending.getFirst().time >= 600; }
    Batch ready(long now) {
        Message head = pending.peekFirst();
        if (head == null || now - head.time < 600) return null;
        var lines = new ArrayList<Message>();
        for (Message line : pending) {
            if (lines.size() >= 8 || !head.thread().equals(line.thread())) break;
            lines.add(line);
        }
        return new Batch(List.copyOf(lines), String.join("\n", history.getOrDefault(head.thread(), new ArrayDeque<>())));
    }

    void considered(Batch batch, String submitted) {
        while (!pending.isEmpty() && pending.getFirst().id <= batch.lastId()) pending.removeFirst();
        String key = batch.first().thread();
        if (history.size() >= 16 && !history.containsKey(key)) history.remove(history.keySet().iterator().next());
        var turns = history.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        for (var line : batch.lines) turns.addLast(line.sender + "：" + line.text);
        if (!submitted.isEmpty()) turns.addLast("我：" + submitted);
        while (turns.size() > 8) turns.removeFirst();
        lastExchange = String.join("\n", turns.stream().skip(Math.max(0, turns.size() - 3)).toList());
    }

    String lastExchange() {
        return lastExchange;
    }
    int count() { return pending.size(); }
    void clear() { pending.clear(); history.clear(); lastExchange = ""; }
}
