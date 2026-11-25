package adris.altoclef.util;

import adris.altoclef.AltoClef;
import java.io.IOException;
import java.nio.file.*;
import java.util.Objects;
import net.minecraft.core.BlockPos;

public record HomeArea(int x1, int z1, int x2, int z2) {
    private static volatile HomeArea current;
    private static Path bound;
    private static Path file(AltoClef mod) {
        var scope = mod.memory().scope();
        if (scope == null || mod.level() == null) return null;
        var dim = mod.level().dimension().identifier();
        return scope.world().resolve("home-area-" + dim.toString().replace(':','_').replace('/','_') + ".txt");
    }
    public static void bind(AltoClef mod) {
        Path path = file(mod);
        if (Objects.equals(bound,path)) return;
        bound = path;
        current = null;
        if (path == null || !Files.exists(path)) return;
        try {
            var parts = Files.readString(path).trim().split(" ");
            current = new HomeArea(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),Integer.parseInt(parts[2]),Integer.parseInt(parts[3]));
        } catch (RuntimeException | IOException ex) {
            current = new HomeArea(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE);
            mod.logWarning("禁拆范围读取失败，暂停破坏方块。请重新设置 protecthome");
        }
    }
    public void save(AltoClef mod) throws IOException {
        Path path = file(mod);
        if (path == null) throw new IOException("当前存档未就绪");
        Files.createDirectories(path.getParent());
        Path tmp = Files.createTempFile(path.getParent(),"home-area-", ".tmp");
        try {
            Files.writeString(tmp,x1+" "+z1+" "+x2+" "+z2);
            Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
        bound = path;
        current = this;
    }
    public boolean contains(BlockPos p) {
        return p.getX() >= Math.min(x1,x2) && p.getX() <= Math.max(x1,x2)
                && p.getZ() >= Math.min(z1,z2) && p.getZ() <= Math.max(z1,z2);
    }
    public static HomeArea current() { return current; }
    public boolean atLeast(BlockPos p, int distance) {
        double dx = Math.max(0, Math.max((double)Math.min(x1,x2)-p.getX(), (double)p.getX()-Math.max(x1,x2)));
        double dz = Math.max(0, Math.max((double)Math.min(z1,z2)-p.getZ(), (double)p.getZ()-Math.max(z1,z2)));
        return dx*dx+dz*dz >= (double)distance*distance;
    }
    public static boolean protectedAt(BlockPos p) {
        var area = current;
        return area != null && area.contains(p);
    }
}
