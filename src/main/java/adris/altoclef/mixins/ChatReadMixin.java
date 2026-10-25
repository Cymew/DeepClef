package adris.altoclef.mixins;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ChatMessageEvent;
import adris.altoclef.eventbus.events.VisiblePlayerChatEvent;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.time.Instant;


@Mixin(ChatListener.class)
public final class ChatReadMixin {
    @Inject(
            method = "handlePlayerChatMessage",
            at = @At("HEAD")
    )
    private void handlePlayerChatMessage(PlayerChatMessage message, GameProfile sender, ChatType.Bound params, CallbackInfo ci) {
        ChatMessageEvent evt = new ChatMessageEvent(message, sender, params);
        EventBus.publish(evt);
    }

    /** The legacy HEAD event remains for Butler; the agent observes only accepted chat. */
    @Inject(method = "showMessageToPlayer", at = @At("RETURN"))
    private void deepclef$onVisiblePlayerChat(ChatType.Bound type, PlayerChatMessage message,
                                             Component decoratedMessage, GameProfile sender,
                                             boolean onlyShowSecure, Instant received,
                                             CallbackInfoReturnable<Boolean> callback) {
        if (!Boolean.TRUE.equals(callback.getReturnValue()) || message == null || sender == null
                || message.isSystem() || !message.sender().equals(sender.id())) return;
        if (type.chatType().is(ChatType.MSG_COMMAND_OUTGOING)
                || type.chatType().is(ChatType.TEAM_MSG_COMMAND_OUTGOING)) return;
        Component body = onlyShowSecure ? Component.literal(message.signedContent()) : message.decoratedContent();
        if (!message.filterMask().isEmpty()) body = message.filterMask().applyWithFormatting(message.signedContent());
        if (body == null || body.getString().isBlank()) return;
        String channel = type.chatType().is(ChatType.MSG_COMMAND_INCOMING) ? "whisper"
                : type.chatType().is(ChatType.TEAM_MSG_COMMAND_INCOMING) ? "team" : "public";
        EventBus.publish(new VisiblePlayerChatEvent(message.sender(), sender.name(), body.getString(), channel,
                message.signature() == null ? null : message.signature().toString()));
    }
}
