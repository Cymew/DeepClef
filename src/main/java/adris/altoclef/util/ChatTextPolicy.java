package adris.altoclef.util;

/** Pure validation for text sent through the native signed-chat transport. */
public final class ChatTextPolicy {
    public static final int MAX_LENGTH = 256;
    public static final String PUBLIC_PREFIX = "[DeepClef] ";

    private ChatTextPolicy() { }

    /** Returns final wire text; callers must not add another public prefix. */
    public static String preparePublic(String text, String clientPrefix) {
        String body = prepareWhisper(text);
        String prefix = clientPrefix == null || clientPrefix.isEmpty() ? "@" : clientPrefix;
        if (body.startsWith("/") || body.startsWith(prefix))
            throw new IllegalArgumentException("公开回复必须是普通聊天文本，不能以服务器或客户端指令前缀开头。");
        String prepared = PUBLIC_PREFIX + body;
        if (prepared.startsWith(prefix))
            throw new IllegalArgumentException("公开聊天标识与当前客户端指令前缀冲突，请调整指令前缀。");
        if (prepared.length() > MAX_LENGTH)
            throw new IllegalArgumentException("公开回复连同[DeepClef]标识最多256个UTF-16字符，请缩短后发送。");
        return prepared;
    }

    /** Validates one complete message. It never truncates or splits a surrogate pair. */
    public static String prepareWhisper(String text) {
        if (text == null) throw new IllegalArgumentException("聊天内容不能为空。");
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '\u00a7' || character < 32 || character == 127)
                throw new IllegalArgumentException("聊天内容包含原版不允许的控制字符或格式符。");
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(index + 1)))
                    throw new IllegalArgumentException("聊天内容包含不完整的Unicode字符。");
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("聊天内容包含不完整的Unicode字符。");
            }
        }
        String prepared = text.strip();
        if (prepared.isBlank()) throw new IllegalArgumentException("聊天内容不能为空。");
        if (prepared.length() > MAX_LENGTH)
            throw new IllegalArgumentException("一条聊天消息最多256个UTF-16字符，请缩短后发送。");
        return prepared;
    }
}
