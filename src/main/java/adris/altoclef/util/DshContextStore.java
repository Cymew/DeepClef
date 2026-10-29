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
     * Stable context id for multiplayer servers only. Singleplayer uses the real
     * save folder (see {@link #singleplayerWorldRoot(AltoClef)}) instead of a name.
     */
    public static String contextId(AltoClef mod) {
        try {
            // Singleplayer: the save folder name is the stable, human readable id.
            Path worldRoot = singleplayerWorldRoot(mod);
            if (worldRoot != null && worldRoot.getFileName() != null) {
                String id = worldRoot.getFileName().toString();
                if (!id.isBlank()) return id;
            }
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            var server = minecraft.getCurrentServer();
            if (server != null) {
                // Two servers may share a display name; the address disambiguates.
                String name = server.name == null || server.name.isBlank() ? "server" : server.name;
                String ip = server.ip == null ? "" : server.ip;
                return ip.isBlank() ? name : name + "_" + ip;
            }
        } catch (Throwable t) {
            Debug.logError("[DeepClef @ds] contextId failed: " + t.getMessage());
        }
        return "unknown";
    }

    /**
     * The save folder of the world we are currently in, or null when not in a
     * singleplayer world.
     *
     * <p>NOTE: {@code IntegratedServer#getServerDirectory()} returns the *game*
     * directory (e.g. {@code versions/26.3-Fabric}), so using it as the world id
     * made every save share one context file. {@code getWorldPath(ROOT)} is the
     * per-save folder ({@code saves/<save>}).
     */
    public static Path singleplayerWorldRoot(AltoClef mod) {
        try {
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            var server = minecraft.getSingleplayerServer();
            if (server == null) return null;
            Path root = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            return root == null ? null : root.toAbsolutePath();
        } catch (Throwable t) {
            Debug.logError("[DeepClef @ds] world root failed: " + t.getMessage());
            return null;
        }
    }

    private static String safeId(AltoClef mod) {
        String safe = contextId(mod).replaceAll("[\\\\/:*?\"<>|]", "_");
        if (safe.length() > 60) safe = safe.substring(0, 60);
        return safe;
    }

    /**
     * Path cache used only while the current world is being torn down, when the
     * integrated server is already gone but we still want the final save to land
     * in the world we just played. Keyed by the level instance so a different
     * world can never reuse it.
     */
    private static Object _cachedLevel;
    private static Path _cachedContextPath;
    private static Path _cachedImageDir;

    /**
     * Singleplayer: the context lives INSIDE the save folder
     * ({@code saves/<存档文件夹>/altoclef/dsh_context.json}) so deleting a save
     * deletes its context and no two saves can ever share one. The folder path —
     * never the display name — identifies the save, so recreating a world with
     * the same name gets a fresh folder and therefore a fresh context.
     *
     * <p>Multiplayer: {@code altoclef/dsh_contexts/<server_name_ip>.json} in the
     * game folder (a folder path does not exist for remote servers).
     *
     * @return the path, or null when it cannot be resolved (not in a world).
     */
    public static Path contextPath(AltoClef mod) {
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        Path worldRoot = singleplayerWorldRoot(mod);
        if (worldRoot != null) {
            Path path = worldRoot.resolve("altoclef").resolve("dsh_context.json");
            _cachedLevel = mod.level();
            _cachedContextPath = path;
            return path;
        }
        if (minecraft.getCurrentServer() != null) {
            return minecraft.gameDirectory.toPath().resolve("altoclef").resolve("dsh_contexts")
                    .resolve(safeId(mod) + ".json");
        }
        // World closing: only reuse the cache for the SAME level instance.
        if (mod.level() != null && mod.level() == _cachedLevel) return _cachedContextPath;
        return null; // unknown context: never write anywhere
    }

    /** Directory holding screenshot images referenced by the context file. */
    public static Path imageDirPath(AltoClef mod) {
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        Path worldRoot = singleplayerWorldRoot(mod);
        if (worldRoot != null) {
            Path dir = worldRoot.resolve("altoclef").resolve("dsh_shots");
            _cachedLevel = mod.level();
            _cachedImageDir = dir;
            return dir;
        }
        if (minecraft.getCurrentServer() != null) {
            return minecraft.gameDirectory.toPath().resolve("altoclef").resolve("dsh_contexts")
                    .resolve(safeId(mod) + "_shots");
        }
        if (mod.level() != null && mod.level() == _cachedLevel) return _cachedImageDir;
        return null;
    }

    public static void saveImage(AltoClef mod, String fileName, byte[] bytes) {
        try {
            Path dir = imageDirPath(mod);
            if (dir == null) return;
            Files.createDirectories(dir);
            Files.write(dir.resolve(fileName), bytes);
        } catch (Throwable t) {
            Debug.logError("[DeepClef @ds] image save failed: " + t.getMessage());
        }
    }

    public static byte[] loadImage(AltoClef mod, String fileName) {
        try {
            Path dir = imageDirPath(mod);
            if (dir == null) return null;
            Path file = dir.resolve(fileName);
            if (!Files.exists(file)) return null;
            return Files.readAllBytes(file);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Context load(AltoClef mod) {
        try {
            Path path = contextPath(mod);
            if (path == null || !Files.exists(path)) return new Context();
            Context context = MAPPER.readValue(path.toFile(), Context.class);
            Debug.logMessage("[DeepClef @ds] context loaded: " + path + " ("
                    + (context.messages == null ? 0 : context.messages.size()) + " messages)");
            return context;
        } catch (Throwable t) {
            Debug.logError("[DeepClef @ds] context load failed: " + t.getMessage());
            return new Context();
        }
    }

    private static Path _lastSavedPath;

    public static void save(AltoClef mod, Context context) {
        try {
            Path path = contextPath(mod);
            if (path == null) {
                Debug.logWarning("[DeepClef @ds] context not saved: no world context available");
                return;
            }
            Files.createDirectories(path.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), context);
            if (!path.equals(_lastSavedPath)) {
                _lastSavedPath = path;
                Debug.logMessage("[DeepClef @ds] context saved to: " + path);
            }
        } catch (Throwable t) {
            Debug.logError("[DeepClef @ds] context save failed: " + t.getMessage());
        }
    }
}
