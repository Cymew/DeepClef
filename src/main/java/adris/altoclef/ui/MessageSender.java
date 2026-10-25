package adris.altoclef.ui;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.util.ChatTextPolicy;
import adris.altoclef.util.time.BaseTimer;
import adris.altoclef.util.time.TimerReal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;

import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Consumer;

/** Shared, rate-limited native chat transport for Butler and model replies. */
public class MessageSender {
    private static final int FAST_LIMIT = 6, SLOW_LIMIT = 3, QUEUE_LIMIT = 128;
    private final PriorityQueue<BaseMessage> _whisperQueue = new PriorityQueue<>(
            Comparator.comparingInt((BaseMessage message) -> message.priority.getImportance()).reversed()
                    .thenComparingLong(message -> message.index));
    private final BaseTimer _fastSendTimer = new TimerReal(.3);
    private final BaseTimer _bigSendTimer = new TimerReal(3.5);
    private final BaseTimer _bigBigSendTimer = new TimerReal(10);
    private long _messageCounter;
    private int _fastCount, _slowCount;

    public void tick() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client == null ? null : client.player;
        // Cancellation callbacks may enqueue more messages; inspect only the
        // queue which existed at the start of this frame.
        int remaining = _whisperQueue.size();
        while (remaining-- > 0 && !_whisperQueue.isEmpty()) {
            BaseMessage message = _whisperQueue.peek();
            if (player == null || message.connection != player.connection || message.world != client.level) {
                _whisperQueue.poll();
                complete(message, "聊天发送失败：连接或世界已变化，排队消息已取消。");
                continue;
            }
            if (!canSendMessage()) return;
            _whisperQueue.poll();
            sendChatUpdateTimers(message, player);
            return;
        }
    }

    public void enqueueWhisper(String username, String message, MessagePriority priority) {
        enqueueWhisper(username, message, priority, null);
    }

    public void enqueueChat(String message, MessagePriority priority) {
        enqueueChat(message, priority, null);
    }

    /** Accepts final wire text, already labelled by ChatTextPolicy.preparePublic for model replies. */
    public void enqueueChat(String message, MessagePriority priority, Consumer<String> completion) {
        enqueue(null, message, priority, completion);
    }

    public void enqueueWhisper(String username, String message, MessagePriority priority, Consumer<String> completion) {
        if (username == null || !username.matches("[A-Za-z0-9_]{1,16}")) {
            notifyResult(completion, "聊天发送失败：私聊需要真实玩家名，不能使用选择器或命令。");
            return;
        }
        enqueue(username, message, priority, completion);
    }

    /** Cancels model callbacks without deleting existing Butler messages. */
    public void cancelPendingCallbacks(String reason) {
        List<BaseMessage> cancelled = _whisperQueue.stream().filter(message -> message.completion != null).toList();
        _whisperQueue.removeAll(cancelled);
        String detail = reason == null || reason.isBlank() ? "操作已停止" : reason;
        for (BaseMessage message : cancelled) complete(message, "聊天发送失败：" + detail + "，排队消息已取消。");
    }

    private void enqueue(String recipient, String message, MessagePriority priority, Consumer<String> completion) {
        final String prepared;
        try {
            prepared = ChatTextPolicy.prepareWhisper(message);
            if (recipient == null) checkPublicText(prepared);
        } catch (IllegalArgumentException invalid) {
            notifyResult(completion, "聊天发送失败：" + invalid.getMessage());
            return;
        }
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client == null ? null : client.player;
        if (player == null || client.level == null || player.connection == null) {
            notifyResult(completion, "聊天发送失败：未连接到游戏世界。");
            return;
        }
        if (_whisperQueue.size() >= QUEUE_LIMIT) {
            notifyResult(completion, "聊天发送失败：发送队列已满，请稍后再试。");
            return;
        }
        _whisperQueue.add(new BaseMessage(recipient, prepared, priority == null ? MessagePriority.OPTIONAL : priority,
                _messageCounter++, player.connection, client.level, completion));
    }

    private static void checkPublicText(String message) {
        var executor = AltoClef.getCommandExecutor();
        String prefix = executor == null ? "@" : executor.getCommandPrefix();
        if (message.startsWith("/") || message.startsWith(prefix))
            throw new IllegalArgumentException("公开聊天不能以服务器或客户端指令前缀开头。");
    }

    private boolean canSendMessage() {
        return _bigBigSendTimer.elapsed() && _bigSendTimer.elapsed() && _fastSendTimer.elapsed();
    }

    private void sendChatUpdateTimers(BaseMessage message, LocalPlayer player) {
        final String result;
        try {
            if (message.recipient == null ? !player.chatAbilities().canSendMessages()
                    : !player.chatAbilities().canSendCommands()) {
                complete(message, "聊天发送失败：当前账号、服务器或聊天设置不允许发送。");
                return;
            }
            // Re-check the current command prefix: settings may have changed
            // while this message was waiting in the common rate-limited queue.
            if (message.recipient == null) {
                checkPublicText(message.text);
                player.connection.sendChat(message.text);
                result = "已提交服务器（公开聊天）：" + message.text;
            } else {
                player.connection.sendCommand("msg " + message.recipient + " " + message.text);
                result = "已提交服务器（私聊 " + message.recipient + "）：" + message.text;
            }
        } catch (Throwable failure) {
            complete(message, "聊天发送失败：" + failure.getMessage());
            return;
        }
        _fastSendTimer.reset();
        if (++_fastCount >= FAST_LIMIT) {
            _bigSendTimer.reset(); _fastCount = 0;
            if (++_slowCount >= SLOW_LIMIT) { _bigBigSendTimer.reset(); _slowCount = 0; }
        }
        complete(message, result);
    }

    private static void complete(BaseMessage message, String result) { notifyResult(message.completion, result); }

    private static void notifyResult(Consumer<String> completion, String result) {
        if (completion == null) {
            if (result.startsWith("聊天发送失败：")) Debug.logWarning(result);
            return;
        }
        try { completion.accept(result); }
        catch (Throwable failure) { Debug.logError("Chat completion callback failed: " + failure.getMessage()); }
    }

    private record BaseMessage(String recipient, String text, MessagePriority priority, long index,
                               ClientPacketListener connection, ClientLevel world, Consumer<String> completion) { }
}
