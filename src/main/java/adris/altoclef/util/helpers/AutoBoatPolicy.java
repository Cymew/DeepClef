package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Cheap opportunity detection; placement and navigation still validate the actual boat hull. */
public final class AutoBoatPolicy {
    public static final double MIN_WATER_DISTANCE = 32;
    static final int MAX_PATH_NODES = 96;
    static final int MAX_SURFACE_QUERIES = 128;
    static final int MAX_BLOCK_QUERIES = 768;
    private static final double ENTRY_DISTANCE = 6;
    private static final double MAX_HEIGHT_CHANGE = .25;

    public record Opportunity(Vec3 waterPoint, double waterDistance) { }

    @FunctionalInterface
    interface WaterSurface {
        /** Returns the real exposed water surface, or null for unsafe/unknown terrain. */
        Vec3 sample(double x, double z, double nearY);
    }

    private AutoBoatPolicy() { }

    /** The caller throttles this to one check per second of ordinary coordinate travel. */
    public static Optional<Opportunity> find(AltoClef mod, Goal goal) {
        if (mod.getPlayer() == null || mod.level() == null || goal == null) return Optional.empty();
        Vec3 position = mod.getPlayer().position();
        Vec3 target = target(goal, position.y);
        if (target == null || horizontalDistance(position, target) < MIN_WATER_DISTANCE) return Optional.empty();
        List<Vec3> ahead = new ArrayList<>();
        var executor = mod.getClientBaritone().getPathingBehavior().getCurrent();
        if (executor != null) {
            var points = executor.getPath().positions();
            int first = Math.max(0, Math.min(executor.getPosition(), points.size()));
            int end = Math.min(points.size(), first + MAX_PATH_NODES);
            for (int index = first; index < end; index++) {
                BlockPos point = points.get(index);
                ahead.add(new Vec3(point.getX() + .5, point.getY(), point.getZ() + .5));
            }
        }
        return detect(position, target, ahead, 0, new LoadedWater(mod.level()));
    }

    private static Vec3 target(Goal goal, double y) {
        if (goal instanceof GoalXZ xz) return new Vec3(xz.getX() + .5, y, xz.getZ() + .5);
        if (goal instanceof GoalBlock block) {
            BlockPos point = block.getGoalPos();
            return new Vec3(point.getX() + .5, point.getY(), point.getZ() + .5);
        }
        return null;
    }

    /** Pure, bounded policy shared by the real-world adapter and route fixtures. */
    static Optional<Opportunity> detect(Vec3 position, Vec3 target, List<Vec3> path,
                                        int pathOffset, WaterSurface surface) {
        if (!finite(position) || !finite(target) || surface == null
                || horizontalDistance(position, target) < MIN_WATER_DISTANCE) return Optional.empty();
        LimitedSurface bounded = new LimitedSurface(surface);
        int begin = Math.max(0, pathOffset);
        int end = Math.min(path.size(), begin + MAX_PATH_NODES);
        Vec3 entry = null, previous = null;
        double length = 0;
        for (int index = begin; index < end; index++) {
            Vec3 point = path.get(index);
            if (!finite(point)) break;
            Vec3 water = bounded.sample(point.x, point.z, point.y);
            if (water == null || entry != null && Math.abs(water.y - entry.y) > MAX_HEIGHT_CHANGE) {
                // A water run starting farther along the journey is not a reason to stop here.
                if (entry != null) break;
                if (horizontalDistance(position, point) > ENTRY_DISTANCE) break;
                continue;
            }
            if (entry == null) {
                if (!nearEntry(position, water)) break;
                entry = previous = water;
                continue;
            }
            double step = horizontalDistance(previous, water);
            // An executor's neighboring positions are adjacent; gaps cannot manufacture water.
            if (step > 1.75 || Math.abs(water.y - previous.y) > MAX_HEIGHT_CHANGE) break;
            length += step;
            previous = water;
            if (length >= MIN_WATER_DISTANCE) return Optional.of(new Opportunity(entry, length));
        }

        // Before a walking route exists, check only the next 40 blocks toward a known coordinate.
        // Four-block probes are supplemented at their midpoints, so a dry gap is not counted.
        Vec3 direction = target.subtract(position).multiply(1, 0, 1).normalize();
        double remaining = horizontalDistance(position, target);
        entry = previous = null;
        length = 0;
        for (double distance = 0; distance <= Math.min(40, remaining) + 1e-7; distance += 2) {
            Vec3 point = position.add(direction.scale(distance));
            Vec3 water = bounded.sample(point.x, point.z, position.y);
            if (water == null || entry != null && Math.abs(water.y - entry.y) > MAX_HEIGHT_CHANGE) {
                if (entry != null || distance >= ENTRY_DISTANCE) return Optional.empty();
                continue;
            }
            if (entry == null) {
                if (!nearEntry(position, water)) return Optional.empty();
                entry = previous = water;
                continue;
            }
            if (Math.abs(water.y - previous.y) > MAX_HEIGHT_CHANGE) return Optional.empty();
            length += horizontalDistance(previous, water);
            previous = water;
            if (length >= MIN_WATER_DISTANCE) return Optional.of(new Opportunity(entry, length));
        }
        return Optional.empty();
    }

    private static boolean nearEntry(Vec3 player, Vec3 water) {
        return horizontalDistance(player, water) <= ENTRY_DISTANCE
                && Math.abs(player.y - water.y) <= 1.6;
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    private static boolean finite(Vec3 point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }

    private static final class LimitedSurface implements WaterSurface {
        private final WaterSurface source;
        private int queries;
        private LimitedSurface(WaterSurface source) { this.source = source; }
        @Override public Vec3 sample(double x, double z, double nearY) {
            if (queries++ >= MAX_SURFACE_QUERIES) return null;
            Vec3 result = source.sample(x, z, nearY);
            return finite(result) ? result : null;
        }
    }

    static boolean ordinaryWater(BlockState state) {
        // A waterlogged solid is not an open boat launch, nor is a bubble column.
        return state != null && state.is(Blocks.WATER) && state.getFluidState().isSource();
    }

    static boolean openHead(BlockState state) { return state != null && state.isAir(); }

    private static final class LoadedWater implements WaterSurface {
        private record Column(int x, int z, int nearY) { }
        private static final int[] HEIGHTS = {0, -1, 1, -2, 2};
        private final Level level;
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();
        private final Map<Long, Boolean> chunks = new HashMap<>();
        private final Map<Column, Vec3> columns = new HashMap<>();

        private LoadedWater(Level level) { this.level = level; }

        @Override public Vec3 sample(double x, double z, double nearY) {
            BlockPos center = BlockPos.containing(x, nearY, z);
            Column key = new Column(center.getX(), center.getZ(), center.getY());
            if (columns.containsKey(key)) {
                Vec3 cached = columns.get(key);
                return cached == null ? null : new Vec3(x, cached.y, z);
            }
            Vec3 found = null;
            for (int offset : HEIGHTS) {
                BlockPos at = center.offset(0, offset, 0);
                BlockState water = block(at);
                if (!ordinaryWater(water)) continue;
                if (!openHead(block(at.above())) || !openHead(block(at.above(2)))) continue;
                // With air above, own height is the exact exposed fluid surface and needs no world read.
                found = new Vec3(x, at.getY() + water.getFluidState().getOwnHeight(), z);
                break;
            }
            columns.put(key, found);
            return found;
        }

        private BlockState block(BlockPos position) {
            if (blocks.containsKey(position)) return blocks.get(position);
            if (blocks.size() >= MAX_BLOCK_QUERIES) return null;
            long chunk = ((long)(position.getX() >> 4) << 32) ^ ((position.getZ() >> 4) & 0xffffffffL);
            boolean loaded = chunks.computeIfAbsent(chunk, ignored -> level.hasChunkAt(position));
            if (!loaded) return null;
            BlockState state = level.getBlockState(position);
            blocks.put(position.immutable(), state);
            return state;
        }
    }
}
