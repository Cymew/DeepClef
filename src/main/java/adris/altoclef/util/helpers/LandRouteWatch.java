package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/** Monitor the next few dry footfalls of the current native path. */
public final class LandRouteWatch {
    private final Map<BlockPos,Double> previous = new HashMap<>();
    private Object path;

    public static double floor(BlockGetter level, BlockPos node) {
        // Baritone nodes may be inside a bottom slab or just above the support block.
        for (BlockPos support : List.of(node,node.below())) {
            var state = level.getBlockState(support);
            if (!state.getFluidState().isEmpty()) continue;
            var shape = state.getCollisionShape(level,support);
            if (shape.isEmpty()) continue;
            double top = Double.NEGATIVE_INFINITY;
            for (var box : shape.toAabbs()) {
                if (box.minX<.8 && box.maxX>.2 && box.minZ<.8 && box.maxZ>.2) top=Math.max(top,box.maxY);
            }
            double height = support.getY()+top;
            if (Double.isFinite(height) && height >= node.getY()-.5 && height < node.getY()+1)
                return height;
        }
        return Double.NaN;
    }

    public static double dryStance(ClientLevel level, BlockPos node) {
        if (!level.hasChunkAt(node) || !level.getFluidState(node).isEmpty()) return Double.NaN;
        double floor = floor(level,node);
        if (!Double.isFinite(floor)) return Double.NaN;
        var body = new AABB(node.getX()+.2,floor+.001,node.getZ()+.2,
                node.getX()+.8,floor+1.8,node.getZ()+.8);
        for (int y=(int)Math.floor(floor-.001); y<=Math.floor(body.maxY); y++) {
            if (ElytraFlightTerrain.hazardous(level.getBlockState(new BlockPos(node.getX(),y,node.getZ()))))
                return Double.NaN;
        }
        return level.getWorldBorder().isWithinBounds(body) && !level.getBlockCollisions(null,body).iterator().hasNext()
                ? floor : Double.NaN;
    }

    public boolean changed(AltoClef mod) {
        var executor = mod.getClientBaritone().getPathingBehavior().getCurrent();
        if (executor == null) { reset(); return false; }
        var route = executor.getPath();
        return changed(route,route.positions(),executor.getPosition(),mod.getPlayer().blockPosition(),
                p -> dryStance(mod.level(),p));
    }

    public boolean changed(Object route,List<? extends BlockPos> nodes,int cursor,BlockPos feet,ToDoubleFunction<BlockPos> stance) {
        if (route != path) { reset(); path = route; }
        Map<BlockPos,Double> next = new HashMap<>();
        boolean changed = false;
        for (int i=Math.max(0,cursor); i<nodes.size() && i<=cursor+4; i++) {
            BlockPos p = nodes.get(i);
            if (p.equals(feet) || Math.abs(p.getY()-feet.getY())>2 || p.distSqr(feet)>16) continue;
            double now = stance.applyAsDouble(p);
            Double before = previous.get(p);
            if (before != null && Double.compare(before,now)!=0) changed = true;
            if (Double.isFinite(now)) next.put(p.immutable(),now);
        }
        previous.clear();
        previous.putAll(next);
        return changed;
    }

    public void reset() { previous.clear(); path = null; }
}
