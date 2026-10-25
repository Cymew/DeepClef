package adris.altoclef.eventbus.events;

import java.util.UUID;

/** A native player-chat body which has passed the client's display rules. */
public record VisiblePlayerChatEvent(UUID senderId, String senderName, String content,
                                     String channel, String signature) { }
