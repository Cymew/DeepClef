package adris.altoclef.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Bounded observations only: player chat is never parsed as a local command. */
public final class MultiplayerChatBuffer {
    public static final int CAPACITY = 128;
    public static final int MAX_CONTENT_CODEPOINTS = 512;
    private static final long DUPLICATE_WINDOW_MS = 2_000;
    private static final long SIGNATURE_WINDOW_MS = 60_000;
    private static final long CONVERSATION_WINDOW_MS = 60_000;
    private static final int SEEN_CAPACITY = 512;
    private final ArrayDeque<Line> history = new ArrayDeque<>();
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>();
    private final Map<UUID, Long> conversationUntil = new HashMap<>();
    private long sequence;

    public record Line(long id, long receivedAt, UUID senderId, String senderName,
                       String content, String channel, String signature) { }

    /** Returns zero for self echoes, empty observations or a duplicate delivery. */
    public long add(UUID senderId, String senderName, String content, String channel,
                    String signature, long now, UUID selfId) {
        if (senderId == null || senderId.equals(selfId) || senderName == null || senderName.isBlank()
                || content == null || content.isBlank()) return 0;
        String name = bounded(senderName.trim(), 64);
        String body = bounded(content, MAX_CONTENT_CODEPOINTS);
        String kind = channel == null ? "public" : bounded(channel, 32);
        String signed = signature == null ? "" : bounded(signature, 1024);
        String textKey = "text:" + senderId + ':' + body;
        String signatureKey = signed.isEmpty() ? null : "signature:" + signed;
        prune(now);
        if (recent(textKey, now, DUPLICATE_WINDOW_MS)
                || signatureKey != null && recent(signatureKey, now, SIGNATURE_WINDOW_MS)) return 0;
        remember(textKey, now);
        if (signatureKey != null) remember(signatureKey, now);
        long id = ++sequence;
        history.addLast(new Line(id, now, senderId, name, body, kind, signed));
        while (history.size() > CAPACITY) history.removeFirst();
        return id;
    }

    public long latestId() { return sequence; }
    public long oldestId() { return history.isEmpty() ? 0 : history.getFirst().id(); }
    public int size() { return history.size(); }
    public boolean hasAfter(long id) { return !history.isEmpty() && sequence > Math.max(0, id); }

    /** Newest bounded observations after a cursor, so high-volume chat stays current. */
    public List<Line> read(long afterId, int limit) {
        int count = Math.max(1, Math.min(CAPACITY, limit));
        List<Line> available = new ArrayList<>();
        for (Line line : history) if (line.id() > Math.max(0, afterId)) available.add(line);
        return List.copyOf(available.subList(Math.max(0, available.size() - count), available.size()));
    }

    public void markConversation(UUID senderId, long now) {
        if (senderId == null) return;
        conversationUntil.entrySet().removeIf(entry -> entry.getValue() < now);
        if (conversationUntil.size() >= 16 && !conversationUntil.containsKey(senderId)) conversationUntil.clear();
        conversationUntil.put(senderId, now + CONVERSATION_WINDOW_MS);
    }

    public boolean isConversationPartner(UUID senderId, long now) {
        Long until = conversationUntil.get(senderId);
        return until != null && now <= until;
    }

    public boolean relevant(Line line, String localName, long now) {
        if (line == null || line.content().stripLeading().toLowerCase(Locale.ROOT).startsWith("[deepclef]")) return false;
        return line.channel().equals("whisper") || mentions(line.content(), localName)
                || isConversationPartner(line.senderId(), now);
    }

    public static boolean mentions(String content, String localName) {
        if (content == null) return false;
        String text = content.toLowerCase(Locale.ROOT);
        return containsName(text, "deepclef") || localName != null && !localName.isBlank()
                && containsName(text, localName.toLowerCase(Locale.ROOT));
    }

    private static boolean containsName(String text, String name) {
        int from = 0, at;
        while ((at = text.indexOf(name, from)) >= 0) {
            int end = at + name.length();
            if ((at == 0 || !asciiWord(text.charAt(at - 1)))
                    && (end == text.length() || !asciiWord(text.charAt(end)))) return true;
            from = at + 1;
        }
        return false;
    }

    private static boolean asciiWord(char character) {
        return character >= 'a' && character <= 'z' || character >= '0' && character <= '9' || character == '_';
    }

    public void clear() {
        history.clear(); seen.clear(); conversationUntil.clear(); sequence = 0;
    }

    private boolean recent(String key, long now, long window) {
        Long previous = seen.get(key);
        return previous != null && now >= previous && now - previous <= window;
    }

    private void remember(String key, long now) {
        seen.remove(key); seen.put(key, now);
        while (seen.size() > SEEN_CAPACITY) seen.remove(seen.keySet().iterator().next());
    }

    private void prune(long now) {
        seen.entrySet().removeIf(entry -> now < entry.getValue() || now - entry.getValue() > SIGNATURE_WINDOW_MS);
        conversationUntil.entrySet().removeIf(entry -> now > entry.getValue());
    }

    private static String bounded(String text, int codepoints) {
        return text.codePointCount(0, text.length()) <= codepoints ? text
                : text.substring(0, text.offsetByCodePoints(0, codepoints));
    }
}
