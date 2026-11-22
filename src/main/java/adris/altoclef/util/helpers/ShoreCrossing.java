package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/** Short, body-relative steps used to cross the boundary between walking and surface swimming. */
public final class ShoreCrossing {
    public interface Terrain {
        Vec3 waterSurface(int x, int z, double feetY);
        boolean deep(Vec3 water);
        Vec3 landing(int x, int z, double feetY);
        boolean swept(Vec3 start, Vec3 end);
    }

    private ShoreCrossing() {}

    private static Vec3 heading(Vec3 feet, BlockPos goal) {
        return new Vec3(goal.getX() + .5 - feet.x, 0, goal.getZ() + .5 - feet.z).normalize();
    }

    /** Require water on the line of travel before considering a neighbour as an alternative entrance. */
    public static Vec3 entrance(Terrain terrain, Vec3 feet, BlockPos goal, Set<BlockPos> rejected) {
        Vec3 direction = heading(feet, goal);
        Vec3 forward = feet.add(direction.scale(1.65));
        if (terrain.waterSurface((int)Math.floor(forward.x), (int)Math.floor(forward.z), feet.y) == null) return null;
        Vec3 best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        BlockPos origin = BlockPos.containing(feet);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            double dot = dx * direction.x + dz * direction.z;
            if (dot < .45 || (dx == 0 && dz == 0)) continue;
            Vec3 water = terrain.waterSurface(origin.getX() + dx, origin.getZ() + dz, feet.y);
            if (water == null || rejected.contains(BlockPos.containing(water)) || !canStep(terrain, feet, water)) continue;
            Vec3 beyond = water.add(direction);
            Vec3 next = terrain.waterSurface((int)Math.floor(beyond.x), (int)Math.floor(beyond.z), feet.y);
            if (next == null || (!terrain.deep(water) && !terrain.deep(next)) || !terrain.swept(water,next)) continue;
            // Do not take a water step past a destination on the near bank.
            double progress = distance(feet,goal) - distance(water,goal);
            double score = progress / Math.max(.25,feet.distanceTo(water)) + .1 * progress;
            if (progress > .1 && score > bestScore) { best = water; bestScore = score; }
        }
        return best;
    }

    public static Vec3 nextSurface(Terrain terrain, Vec3 feet, BlockPos goal) {
        if (distance(feet, goal) <= 2) {
            Vec3 endpoint = terrain.waterSurface(goal.getX(), goal.getZ(), feet.y);
            if (endpoint != null && terrain.swept(feet, endpoint)) return endpoint;
        }
        Vec3 direction=heading(feet,goal);
        double angle=Math.atan2(direction.z,direction.x);
        for(int offset:new int[]{0,25,-25,50,-50,75,-75}) {
            double a=angle+Math.toRadians(offset);
            Vec3 sample=feet.add(1.55*Math.cos(a),0,1.55*Math.sin(a));
            Vec3 next=terrain.waterSurface((int)Math.floor(sample.x),(int)Math.floor(sample.z),feet.y);
            if(next!=null && terrain.swept(feet,next) && distance(next,goal)<distance(feet,goal)) return next;
        }
        return null;
    }

    public static Vec3 exit(Terrain terrain, Vec3 feet, BlockPos goal, Set<BlockPos> rejected) {
        Vec3 ahead = feet.add(heading(feet, goal).scale(Math.min(1.6, distance(feet, goal))));
        if (terrain.waterSurface((int)Math.floor(ahead.x), (int)Math.floor(ahead.z), feet.y) != null) return null;
        BlockPos origin = BlockPos.containing(feet);
        Vec3 best = null;
        double bestProgress = .18;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            double progress = distance(feet,goal) - Math.hypot(origin.getX()+dx-goal.getX(),origin.getZ()+dz-goal.getZ());
            if (progress <= bestProgress) continue;
            Vec3 shore = terrain.landing(origin.getX()+dx,origin.getZ()+dz,feet.y);
            if (shore != null && !rejected.contains(BlockPos.containing(shore)) && canStep(terrain,feet,shore)) { best = shore; bestProgress = progress; }
        }
        return best;
    }

    /** A flat sweep plus a vertical sweep allows a real step off/up a ledge without clipping through its face. */
    static boolean canStep(Terrain terrain, Vec3 from, Vec3 to) {
        if (Math.abs(to.y-from.y)>1.65 || from.subtract(to).horizontalDistance()>2.2) return false;
        Vec3 corner = to.y>from.y ? new Vec3(from.x,to.y,from.z) : new Vec3(to.x,from.y,to.z);
        return terrain.swept(from,corner) && terrain.swept(corner,to);
    }

    static double distance(Vec3 from, BlockPos to) {
        return Math.hypot(from.x-to.getX()-.5,from.z-to.getZ()-.5);
    }

    /** No elapsed wall time is counted while another task owns the player's inputs. */
    static final class Progress {
        private double best = Double.POSITIVE_INFINITY;
        private int idle;

        boolean stalled(double remaining) {
            if (remaining < best-.08) { best = remaining; idle = 0; }
            return ++idle >= 35;
        }
        void reset() { best = Double.POSITIVE_INFINITY; idle = 0; }
    }
}
