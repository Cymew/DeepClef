package adris.altoclef.memory;

import adris.altoclef.mode.WorldModeStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Captured on join. A display name and a server-assigned UUID are never storage keys. */
public record MemoryScope(Path root, Path world, Path role) {
    public static MemoryScope resolve(Path game, Path save, String address, UUID profile) throws IOException {
        if (profile == null) throw new IOException("缺少本地角色身份");
        Path home = game.toAbsolutePath().normalize().resolve("altoclef");
        Path role = home.resolve("memory").resolve(profile.toString());
        Path root = save != null ? save.toAbsolutePath().normalize() : game.toAbsolutePath().normalize();
        Path context;
        if (save != null) {
            context = root.resolve("altoclef");
        } else {
            String key = WorldModeStore.server(game, address).key();
            try {
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(key.getBytes(StandardCharsets.UTF_8)));
                context = home.resolve("server_contexts").resolve(hash);
            } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
            Path seasonFile = context.resolve("season.txt");
            String season = Files.notExists(seasonFile) ? "1" : Files.readString(seasonFile).strip();
            if (!season.matches("[a-zA-Z0-9_-]{1,40}")) throw new IOException("无效的服务器周目 season.txt");
            context = context.resolve("seasons").resolve(season);
        }
        return new MemoryScope(root, context.resolve("actors").resolve(profile.toString()), role);
    }

    public void prepare() throws IOException {
        if (!Files.isDirectory(root)) throw new IOException("原存档目录已关闭或移除");
        Path parent=root;
        for(Path component:root.relativize(world)) {
            parent=parent.resolve(component);
            try { Files.createDirectory(parent); }
            catch(java.nio.file.FileAlreadyExistsException exists) {
                if(!Files.isDirectory(parent)) throw exists;
            }
        }
        Files.createDirectories(role);
    }
}
