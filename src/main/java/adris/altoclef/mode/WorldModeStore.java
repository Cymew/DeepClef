package adris.altoclef.mode;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.net.HostAndPort;
import com.google.common.net.InetAddresses;

import java.io.IOException;
import java.net.IDN;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Locale;

/** One local binding, or one entry in the shared multiplayer address book. */
public record WorldModeStore(Path file, String key) {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);

    public static WorldModeStore save(Path root) {
        return new WorldModeStore(root.resolve("altoclef/mode.json").toAbsolutePath().normalize(), "mode");
    }

    public static WorldModeStore server(Path gameDir, String address) {
        HostAndPort endpoint = HostAndPort.fromString(address.strip()).withDefaultPort(25565);
        String host = endpoint.getHost();
        if (host.isBlank() || endpoint.getPort() == 0) throw new IllegalArgumentException("无效的服务器地址");
        if (host.contains(":")) {
            host = InetAddresses.toAddrString(InetAddresses.forString(host));
        } else {
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            host = IDN.toASCII(host).toLowerCase(Locale.ROOT);
            if (host.isBlank()) throw new IllegalArgumentException("缺少服务器主机名");
        }
        return new WorldModeStore(gameDir.resolve("altoclef/server_modes.json").toAbsolutePath().normalize(),
                HostAndPort.fromParts(host, endpoint.getPort()).toString());
    }

    private static WorldMode decode(JsonNode value) throws IOException {
        for (WorldMode mode : WorldMode.values()) if (mode.id.equals(value.textValue())) return mode;
        throw new IOException("模式值必须为 assistant 或 player");
    }

    private ObjectNode readFile() throws IOException {
        String contents;
        try {
            contents = Files.readString(file);
        } catch (NoSuchFileException missing) {
            return JSON.createObjectNode();
        }
        JsonNode node = JSON.readTree(contents);
        if (!(node instanceof ObjectNode object) || ("mode".equals(key) && !object.has("mode"))) {
            throw new IOException("模式配置格式无效");
        }
        for (JsonNode value : object) decode(value);
        return object;
    }

    public WorldMode read() throws IOException {
        JsonNode value = readFile().get(key);
        return value == null ? null : decode(value);
    }

    /** Re-read under the lock so another instance's first selection or other servers survive. */
    public WorldMode bind(WorldMode selection) throws IOException {
        synchronized (WorldModeStore.class) {
            Files.createDirectories(file.getParent());
            Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.tryLock()) {
                if (lock == null) throw new IOException("另一个客户端正在保存选择，请重进后重试");
                ObjectNode object = readFile();
                if (object.has(key)) return decode(object.get(key));
                object.put(key, selection.id);
                Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
                try {
                    Files.writeString(temporary, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(object) + "\n");
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temporary);
                }
                return selection;
            }
        }
    }
}
