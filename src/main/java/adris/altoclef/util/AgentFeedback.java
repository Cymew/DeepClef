package adris.altoclef.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.*;

/** Local development reports. Model observations and actual client tool results have distinct fields. */
final class AgentFeedback {
    static final String FILE = "altoclef/diagnostics/agent-feedback.jsonl";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> DIAGNOSTIC_TOOLS = Set.of("altoclef_action","altoclef_execute","altoclef_walk",
            "altoclef_entities","altoclef_block_at","altoclef_look","altoclef_equipment");
    private static final List<String> FIELDS = List.of("summary", "expected", "actual");

    static ObjectNode schema() {
        var root = JSON.createObjectNode().put("type","object").put("additionalProperties",false);
        var required = root.putArray("required");
        var props = root.putObject("properties");
        for (String field : FIELDS) {
            required.add(field);
            props.putObject(field).put("type","string").put("minLength",1).put("maxLength",1000);
        }
        return root;
    }

    static ObjectNode report(JsonNode args, List<DeepSeekApi.ChatMessage> history, String screen, String version) {
        if (!args.isObject() || args.size() != FIELDS.size()) throw new IllegalArgumentException("请填写 summary、expected、actual");
        var record = JSON.createObjectNode().put("id", UUID.randomUUID().toString())
                .put("time",OffsetDateTime.now().toString()).put("source","deepseek").put("mod_version",version);
        var account = record.putObject("model_report");
        for (String field : FIELDS) {
            var value = args.path(field);
            if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 1000)
                throw new IllegalArgumentException(field + " 应为 1..1000 字符");
            account.put(field,value.textValue().strip());
        }
        record.put("client_at_report",screen);
        var recent = new ArrayDeque<ObjectNode>();
        var pending = new HashMap<String, DeepSeekApi.ToolCall>();
        for (var message : history) {
            if ("assistant".equals(message.role()) && message.toolCalls() != null) {
                pending.clear();
                for (var call : message.toolCalls()) if (DIAGNOSTIC_TOOLS.contains(call.name())) pending.put(call.id(),call);
            } else if ("tool".equals(message.role())) {
                var call = pending.remove(message.toolCallId());
                if (call == null || message.content() == null) continue;
                var row = JSON.createObjectNode().put("tool",call.name()).put("call_id",call.id());
                if ("altoclef_action".equals(call.name())) {
                    try {
                        var params = JSON.readTree(call.argumentsJson());
                        if (params != null) row.put("action",params.path("action").asText());
                    } catch (IOException ignored) { }
                }
                var receipt = message.content();
                row.put("receipt",receipt.length() > 600 ? receipt.substring(0,600) + "…" : receipt);
                if (recent.size() == 8) recent.removeFirst();
                recent.addLast(row);
            }
        }
        var evidence = record.putArray("tool_receipts");
        recent.forEach(evidence::add);
        return record;
    }

    static synchronized void append(Path gameDir, ObjectNode record) throws IOException {
        Path file = gameDir.resolve(FILE);
        Files.createDirectories(file.getParent());
        var data = ByteBuffer.wrap((JSON.writeValueAsString(record) + "\n").getBytes(StandardCharsets.UTF_8));
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("反馈文件正被其他游戏实例写入");
            while (data.hasRemaining()) channel.write(data);
        }
    }
}
