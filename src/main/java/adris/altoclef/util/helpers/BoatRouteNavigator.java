package adris.altoclef.util.helpers;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Rolls a checked surface corridor forward while the native boat keeps moving. */
public final class BoatRouteNavigator {
    public record Guidance(Vec3 target, boolean terminal, boolean planning, boolean blocked) { }

    private final LongSupplier clock;
    private final ArrayList<Vec3> route = new ArrayList<>();
    private Goal goal;
    private BoatPathPlanner.Search search;
    private int nextDirectTick, nextSearchTick;

    public BoatRouteNavigator() { this(System::nanoTime); }

    // A deterministic clock lets replays charge for terrain queries.
    BoatRouteNavigator(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }

    public List<Vec3> path() { return List.copyOf(route); }

    public void retry() {
        route.clear(); search = null; nextDirectTick = nextSearchTick = Integer.MIN_VALUE;
    }

    public void reset() { retry(); goal = null; }

    public Guidance update(Vec3 position, Goal destination, BoatPathPlanner.Terrain terrain,
                           int tick, double horizon, long maxNanos) {
        if (!finite(position) || destination == null || terrain == null
                || !Double.isFinite(horizon) || horizon <= 0) {
            reset(); return new Guidance(null, false, false, true);
        }
        if (!Objects.equals(goal, destination)) { retry(); goal = destination; }
        long started = clock.getAsLong();
        trimPassed(position, terrain);
        Vec3 tail = tail(position);

        // Open water and straight ice roads need no graph search. Extend from
        // the already checked frontier, rather than repeatedly stop at it.
        if (search == null && tick >= nextDirectTick && goalPoint(tail) != null) {
            for (int segment = 0; segment < 2 && remaining(position) < horizon
                    && !inGoal(tail) && timeLeft(started, maxNanos) > 0; segment++) {
                Vec3 requested = goalPoint(tail);
                Vec3 delta = requested.subtract(tail).multiply(1, 0, 1);
                double distance = delta.horizontalDistance();
                if (distance < .05) { nextDirectTick = tick + 6; break; }
                Vec3 endpoint = tail.add(delta.scale(Math.min(8, distance) / distance));
                Vec3 next = terrain.surface(endpoint.x, endpoint.z, tail.y);
                if (!finite(next) || !terrain.traversable(tail, next)) {
                    nextDirectTick = tick + 6;
                    if (tick >= nextSearchTick) startSearch(tail);
                    break;
                }
                append(List.of(next)); tail = next;
            }
        }

        // Generic goals use the graph directly. A graph frontier is anchored at
        // the old corridor tail, which stays ahead of the boat while it sails;
        // moving eight blocks is not a reason to discard useful background work.
        if (search == null && !inGoal(tail(position)) && remaining(position) < horizon
                && tick >= nextSearchTick && (goalPoint(position) == null || tick < nextDirectTick)) {
            startSearch(tail(position));
        }
        if (search != null) {
            long left = timeLeft(started, maxNanos);
            if (left > 0) search.advance(terrain, 64, left);
            BoatPathPlanner.Route prefix = search.preview();
            boolean useful = !prefix.points().isEmpty() && (prefix.reachedGoal()
                    || search.start().distanceToSqr(prefix.points().getLast()) >= 64
                    || search.isFinished());
            if (useful) {
                append(prefix.points()); search = null;
                nextDirectTick = nextSearchTick = tick + 1;
            } else if (search.isFinished()) {
                // A failed continuation must never erase a still usable route.
                search = null; nextSearchTick = tick + 15; nextDirectTick = tick + 6;
            }
        }

        if (route.isEmpty()) return new Guidance(null, false, search != null, search == null);
        int selected = BoatPathPlanner.lookAhead(position, route, 0, terrain);
        if (selected < 0) {
            // The current world invalidated the old corridor. Stop within the
            // controller's checked trajectory and repair from the actual boat.
            retry(); startSearch(position);
            return new Guidance(null, false, true, true);
        }
        Vec3 target = route.get(selected);
        if (selected == 0 && route.size() > 1 && position.distanceToSqr(target) < 1
                && !terrain.traversable(target, route.get(1))) {
            // Merely being unable to shortcut a corner is normal. Repair only
            // when the next previously checked edge itself changed underneath
            // us, otherwise its old length could indefinitely hide a dead end.
            retry(); startSearch(position);
            return new Guidance(null, false, true, true);
        }
        return new Guidance(target, inGoal(target), search != null, false);
    }

    private void startSearch(Vec3 position) {
        search = BoatPathPlanner.beginFast(position, goal, 2048, 48, clock);
    }

    private void append(List<Vec3> points) {
        for (Vec3 point : points) {
            if (!route.isEmpty() && route.getLast().distanceToSqr(point) < 1e-8) continue;
            route.add(point);
        }
    }

    private void trimPassed(Vec3 position, BoatPathPlanner.Terrain terrain) {
        while (route.size() > 1) {
            Vec3 first = route.getFirst(), second = route.get(1);
            Vec3 following = second.subtract(first).multiply(1, 0, 1);
            boolean passed = position.subtract(first).multiply(1, 0, 1).dot(following) > 1e-7;
            if (!passed && position.subtract(first).horizontalDistance() >= 1) break;
            if (!terrain.traversable(position, second)) break;
            route.removeFirst();
        }
    }

    private double remaining(Vec3 position) {
        double distance = 0;
        for (Vec3 point : route) { distance += position.distanceTo(point); position = point; }
        return distance;
    }

    private Vec3 tail(Vec3 position) { return route.isEmpty() ? position : route.getLast(); }

    private Vec3 goalPoint(Vec3 position) {
        if (goal instanceof GoalXZ target) return new Vec3(target.getX() + .5, position.y, target.getZ() + .5);
        if (goal instanceof GoalBlock target) return new Vec3(target.x + .5, position.y, target.z + .5);
        return null;
    }

    private boolean inGoal(Vec3 position) { return goal.isInGoal(BlockPos.containing(position)); }

    private long timeLeft(long started, long budget) {
        if (budget <= 0) return 0;
        long elapsed = clock.getAsLong() - started;
        return elapsed >= budget ? 0 : budget - Math.max(0, elapsed);
    }

    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
