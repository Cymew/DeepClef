package adris.altoclef.util;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-save DeepSeek conversation context: messages, token usage, compaction
 * summaries and turn count. Stored in the game folder under
 * {@code altoclef/dsh_contexts/<世界名或服务器名>.json} — one independent context
 * per Minecraft save/server.
 */
public final class DshContextStore {

    /** One persisted message. Tool calls are stored as a JSON array string. */
    public static class StoredMessage {
        public String role;
        public String content;
        public String toolCallId;
        public String toolCallsJson;
        public String imageDataUrl;
        /** Nullable in older contexts; never folded into the visible assistant content. */
        public String reasoningContent;

        public StoredMessage() {
        }

        public StoredMessage(String role, String content, String toolCallId, String toolCallsJson, String imageDataUrl) {
            this(role, content, toolCallId, toolCallsJson, imageDataUrl, null);
        }

        public StoredMessage(String role, String content, String toolCallId, String toolCallsJson) {
            this(role, content, toolCallId, toolCallsJson, null, null);
        }

        public StoredMessage(String role, String content, String toolCallId, String toolCallsJson,
                             String imageDataUrl, String reasoningContent) {
            this.role = role;
            this.content = content;
            this.toolCallId = toolCallId;
            this.toolCallsJson = toolCallsJson;
            this.imageDataUrl = imageDataUrl;
            this.reasoningContent = reasoningContent;
        }
    }

    /** The whole per-save context file. */
    public static class Context {
        public int formatVersion = 1;
        public List<StoredMessage> messages = new ArrayList<>();
        public long promptTokens = 0;
        public long completionTokens = 0;
        public int compactions = 0;
        public List<String> summaries = new ArrayList<>();
        public int turns = 0;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DshContextStore() {
    }

    /**
     * Stable context id: level name in singleplayer, server name in multiplayer.
     */
    public static String contextId(AltoClef mod) {
        try {
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft.isLocalServer() && minecraft.getSingleplayerServer() != null) {
                java.nio.file.Path dir = minecraft.getSingleplayerServer().getServerDirectory();
                if (dir != null && dir.getFileName() != null) {
                    String id = dir.getFileName().toString();
                    if (!id.isBlank()) return id;
                }
            }
            if (minecraft.getCurrentServer() != null) {
                return minecraft.getCurrentServer().name;
            }
        } catch (Throwable t) {
            Debug.logError("[DeepClef @ds] contextId failed: " + t.getMessage());
        }
        return "unknown";
    }

    private static String safeId(AltoClef mod) {
        String safe = contextId(mod).replaceAll("[\\\\/:*?\"<>|]", "_");
        if (safe.length() > 60) safe = safe.substring(0, 60);
        return safe;
    }

    /**
     * Singleplayer: the context lives INSIDE the save folder
     * ({@code saves/<存档文件夹>/altoclef/dsh_context.json}) so deleting a save
     * deletes its context and no two saves can ever share one.
     * Multiplayer: {@code altoclef/dsh_contexts/<服务器>.json} in the game folder.
     */
    public static Path contextPath(AltoClef mod) {
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.isLocalServer()) {
            return minecraft.gameDirectory.toPath().resolve("saves").resolve(safeId(mod))
                    .resolve("altoclef").resolve("dsh_context.json");
        }
        return minecraft.gameDirectory.toPath().resolve("altoclef").resolve("dsh_contexts")
                .resolve(safeId(mod) + ".json");
    }

    /** Directory holding screenshot images referenced by the context file. */
    public static Path imageDirPath(AltoClef mod) {
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.isLocalServer()) {
            return minecraft.gameDirectory.toPath().resolve("saves").resolve(safeId(mod))
                    .resolve("altoclef").resolve("dsh_shots");
        }
        return minecraft.gameDirectory.toPath().resolve("altoclef").resolve("dsh_contexts")
                .resolve(safeId(mod) + "_shots");
    }

    public static void saveImage(AltoClef mod, String fileName, byte[] bytes) {
        try {
            Path dir = imageDirPath(mod);
            Files.createDirectories(dir);
            Files.write(dir.resolve(fileName), bytes);
        } catch (Throwable t) {
            Debug.logError("[DeepClef @ds] image save failed: " + t.getMessage());
        }
    }

    public static byte[] loadImage(AltoClef mod, String fileName) {
        try {
            Path file = imageDirPath(mod).resolve(fileName);
            if (!Files.exists(file)) return null;
            return Files.readAllBytes(file);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Context load(AltoClef mod) {
        try {
            Path path = contextPath(mod);
            if (!Files.exists(path)) return new Context();
            return MAPPER.readValue(path.toFile(), Context.class);
        } catch (Throwable t) {
            Debug.logError("[DeepClef @ds] context load failed: " + t.getMessage());
            return new Context();
        }
    }

    public static void save(AltoClef mod, Context context) {
        try {
            Path path = contextPath(mod);
            Files.createDirectories(path.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), context);
        } catch (Throwable t) {
            Debug.logError("[DeepClef @ds] context save failed: " + t.getMessage());
        }
    }
}
