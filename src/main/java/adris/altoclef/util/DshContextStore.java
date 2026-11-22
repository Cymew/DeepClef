package adris.altoclef.util;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Conversation, tool history, screenshots and usage for a bound world and local profile.
 * Unowned P1 files can be imported explicitly together with their referenced screenshots.
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
        public boolean legacyImported;
        @com.fasterxml.jackson.annotation.JsonIgnore public boolean readOnly;
        public Context snapshot() {
            Context copy=new Context();
            copy.formatVersion=formatVersion;copy.readOnly=readOnly;copy.legacyImported=legacyImported;copy.messages=List.copyOf(messages);
            copy.promptTokens=promptTokens;copy.completionTokens=completionTokens;
            copy.compactions=compactions;copy.summaries=List.copyOf(summaries);copy.turns=turns;
            return copy;
        }
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

    public static Path contextPath(AltoClef mod) {
        var scope=mod.memory().scope();
        return scope==null?null:scope.world().resolve("dsh_context.json");
    }

    public record Legacy(Path json,Path shots) {}
    public static Legacy legacy(AltoClef mod) {
        Path root=singleplayerWorldRoot(mod);
        return legacyPaths(net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath(),root,safeId(mod));
    }
    public static Legacy legacyPaths(Path game,Path root,String id) {
        if(root!=null) return new Legacy(root.resolve("altoclef/dsh_context.json"),root.resolve("altoclef/dsh_shots"));
        Path dir=game.resolve("altoclef/dsh_contexts");
        return new Legacy(dir.resolve(id+".json"),dir.resolve(id+"_shots"));
    }

    private static Context read(Path path) throws java.io.IOException {
        var value=MAPPER.readValue(path.toFile(),Context.class);
        if(value==null || value.messages==null || value.summaries==null || value.messages.contains(null)
                || value.formatVersion!=1) throw new java.io.IOException("未知或损坏的历史格式");
        return value;
    }

    public static synchronized String importLegacy(Path dir,Legacy source) throws java.io.IOException {
        Path target=dir.resolve("dsh_context.json");
        Context current=Files.exists(target)?read(target):new Context();
        if(current.legacyImported) return "本角色已导入过旧记录。";
        if(!Files.isRegularFile(source.json())) return "这个旧文件名下没有对话记录。";
        Context context=read(source.json());
        for(var message:context.messages) {
            String ref=message.imageDataUrl;
            if(ref==null || !ref.startsWith("file:shots/")) continue;
            String name=ref.substring("file:shots/".length());
            if(!validImage(name)) throw new java.io.IOException("旧截图引用无效");
            Path src=source.shots().resolve(name);
            if(!Files.isRegularFile(src)) throw new java.io.IOException("旧截图缺失，保留原件："+name);
            saveImage(dir,name,Files.readAllBytes(src));
        }
        context.messages.addAll(current.messages);
        context.summaries.addAll(current.summaries);
        context.turns+=current.turns;context.compactions+=current.compactions;
        context.promptTokens+=current.promptTokens;context.completionTokens+=current.completionTokens;
        context.legacyImported=true;
        save(dir,context);
        return "已复制旧对话及引用截图。旧文件仍在原处。";
    }
    private static boolean validImage(String name) {return name!=null && name.matches("[a-fA-F0-9]{1,64}\\.png");}
    public static void saveImage(Path dir,String name,byte[] bytes) throws java.io.IOException {
        if(!validImage(name) || !Files.isDirectory(dir)) throw new java.io.IOException("截图路径不可用");
        Path shots=dir.resolve("dsh_shots");
        try {Files.createDirectory(shots);}
        catch(java.nio.file.FileAlreadyExistsException exists) {if(!Files.isDirectory(shots)) throw exists;}
        Path file=shots.resolve(name);
        if(Files.isRegularFile(file) && Files.size(file)==bytes.length) return;
        atomicWrite(file,bytes);
    }
    public static byte[] loadImage(AltoClef mod,String name) {
        var scope=mod.memory().scope();
        if(scope==null || !validImage(name)) return null;
        try {return Files.readAllBytes(scope.world().resolve("dsh_shots").resolve(name));}
        catch(java.io.IOException e) {return null;}
    }
    public static Context load(AltoClef mod) {
        Path path=contextPath(mod);
        if(path==null || Files.notExists(path)) return new Context();
        try {return read(path);}
        catch(Exception e) {
            Debug.logError("对话历史读取失败，已保留原件："+e.getMessage());
            Context empty=new Context(); empty.readOnly=true; return empty;
        }
    }
    private static void atomicWrite(Path file,byte[] bytes) throws java.io.IOException {
        Path temp=Files.createTempFile(file.getParent(),"dsh-",".tmp");
        try {
            Files.write(temp,bytes);
            Files.move(temp,file,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {Files.deleteIfExists(temp);}
    }
    public static synchronized void save(Path dir,Context context) throws java.io.IOException {
        if(!context.readOnly && Files.isDirectory(dir)) atomicWrite(dir.resolve("dsh_context.json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(context));
    }
}
