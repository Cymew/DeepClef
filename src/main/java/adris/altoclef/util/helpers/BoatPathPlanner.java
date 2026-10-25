package adris.altoclef.util.helpers;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.function.LongSupplier;

/** Bounded surface graph for boats. No horse stepping, jumping or body assumptions. */
public final class BoatPathPlanner {
    public interface Terrain {
        /** Actual boat-foot position at the requested XZ, or null if not safely navigable. */
        Vec3 surface(double x, double z, double nearY);
        /** Continuously check both support and complete boat/passenger swept volume. */
        boolean traversable(Vec3 from, Vec3 to);
    }
    public record Route(List<Vec3> points, boolean reachedGoal, int expanded) {
        public Route { points = List.copyOf(points); }
        public Route(List<Vec3> points, boolean reachedGoal) { this(points, reachedGoal, 0); }
    }
    private record Key(int x, int y, int z) {
        static Key at(Vec3 point) { return new Key((int) Math.round(point.x * 2), (int) Math.round(point.y * 16), (int) Math.round(point.z * 2)); }
    }
    private record Node(Vec3 point, double cost, double estimate, Node parent, long order) { }
    private static final int[][] DIRECTIONS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1},
            {1, 1}, {-1, 1}, {-1, -1}, {1, -1}};

    private BoatPathPlanner() { }

    public static Route plan(Vec3 start, Goal goal, Terrain terrain, int budget, int radius) {
        if (terrain == null) return new Route(List.of(), false, 0);
        Search search = begin(start, goal, budget, radius);
        search.advance(terrain, Integer.MAX_VALUE, Long.MAX_VALUE);
        return search.result();
    }

    /** Retains only graph geometry. Each advance uses the caller's current terrain snapshot. */
    public static Search begin(Vec3 start, Goal goal, int budget, int radius) {
        return new Search(start, goal, budget, radius, 1, System::nanoTime);
    }

    /** Prefer useful forward progress to proving a shortest route for a moving boat. */
    public static Search beginFast(Vec3 start, Goal goal, int budget, int radius) {
        return new Search(start, goal, budget, radius, 2, System::nanoTime);
    }

    // An injected monotonic clock makes deadline regression checks deterministic.
    static Search begin(Vec3 start, Goal goal, int budget, int radius, LongSupplier clock) {
        return new Search(start, goal, budget, radius, 1, clock);
    }

    static Search beginFast(Vec3 start, Goal goal, int budget, int radius, LongSupplier clock) {
        return new Search(start, goal, budget, radius, 2, clock);
    }

    /** A* that can yield between neighbor queries instead of blocking a game tick. */
    public static final class Search {
        private final Vec3 start;
        private final Goal goal;
        private final int budget, radius;
        private final LongSupplier clock;
        private final PriorityQueue<Node> open;
        private final Map<Key, Double> costs = new HashMap<>();
        private Node first, best, current;
        private long order;
        private int expanded, direction;
        private boolean finished;
        private Route result;

        private Search(Vec3 start, Goal goal, int budget, int radius, double weight, LongSupplier clock) {
            this.start = start; this.goal = goal; this.budget = budget; this.radius = radius;
            this.clock = Objects.requireNonNull(clock);
            open = new PriorityQueue<>(Comparator.comparingDouble((Node n) -> n.cost + n.estimate * weight)
                    .thenComparingDouble(Node::estimate).thenComparingLong(Node::order));
            if (!finite(start) || goal == null || budget <= 0 || radius <= 0) {
                finish(false); return;
            }
            first = best = new Node(start, 0, estimate(goal, start), null, order++);
            open.add(first); costs.put(Key.at(start), 0.0);
        }

        public Vec3 start() { return start; }
        public boolean isFinished() { return finished; }
        public int expanded() { return expanded; }

        /** Unfinished searches never publish a partially checked active edge. */
        public Route result() {
            return finished ? result : new Route(List.of(), false, expanded);
        }

        /**
         * A usable prefix contains only nodes whose incoming edge was completely
         * checked. A partially expanded node may be its endpoint, never an
         * unqueried outgoing edge. The ordinary result contract stays unchanged.
         */
        public Route preview() {
            if (finished) return result;
            return best != null && best != first ? route(best,
                    goal.isInGoal(BlockPos.containing(best.point)), expanded)
                    : new Route(List.of(), false, expanded);
        }

        /**
         * Bounds newly expanded nodes and elapsed time independently. One terrain
         * query is indivisible; the deadline is checked before every neighbor.
         * A half-processed node resumes at its next edge on the following tick.
         */
        public boolean advance(Terrain terrain, int maxNodes, long maxNanos) {
            if (finished || terrain == null || maxNodes <= 0 || maxNanos <= 0) return finished;
            long started = clock.getAsLong();
            long nodeLimit = (long) expanded + maxNodes;
            while (true) {
                if (clock.getAsLong() - started >= maxNanos) return false;
                if (current == null) {
                    if (open.isEmpty() || expanded >= budget) { finish(false); return true; }
                    if (expanded >= nodeLimit) return false;
                    Node candidate = open.poll();
                    if (candidate.cost > costs.getOrDefault(Key.at(candidate.point), Double.POSITIVE_INFINITY) + 1e-7) continue;
                    current = candidate; direction = 0; expanded++;
                    if (goal.isInGoal(BlockPos.containing(current.point))) { finish(true); return true; }
                    if (current.estimate < best.estimate - 1e-7) best = current;
                }
                if (direction >= DIRECTIONS.length) { current = null; continue; }
                int[] delta = DIRECTIONS[direction++];
                double x = Math.rint(current.point.x * 2) * .5 + delta[0] * .5;
                double z = Math.rint(current.point.z * 2) * .5 + delta[1] * .5;
                if (Math.abs(x - start.x) > radius || Math.abs(z - start.z) > radius) continue;
                Vec3 next = terrain.surface(x, z, start.y);
                if (!finite(next) || Math.abs(next.x - x) > 1e-5 || Math.abs(next.z - z) > 1e-5
                        || Math.abs(next.y - start.y) > 2) continue;
                double length = current.point.distanceTo(next);
                if (length < 1e-7) continue;
                double cost = current.cost + length;
                Key key = Key.at(next);
                // Collision/fluid sweeps dominate planning. An already cheaper
                // graph edge cannot improve this route regardless of clearance.
                if (cost >= costs.getOrDefault(key, Double.POSITIVE_INFINITY) - 1e-7) continue;
                if (!terrain.traversable(current.point, next)) continue;
                costs.put(key, cost);
                open.add(new Node(next, cost, estimate(goal, next), current, order++));
            }
        }

        private void finish(boolean reached) {
            finished = true;
            Node end = reached ? current : best;
            result = end != null && (reached || end != first && end.point.distanceToSqr(start) > .0625)
                    ? route(end, reached, expanded) : new Route(List.of(), false, expanded);
            current = null;
        }
    }

    /**
     * Every returned shortcut has a complete terrain sweep. An open long route
     * needs one sweep rather than scanning all increasingly long prefixes.
     * Blocked endpoints fall back to at most six tested bisection candidates.
     */
    public static int lookAhead(Vec3 start, List<Vec3> points, int cursor, Terrain terrain) {
        if (!finite(start) || points == null || terrain == null || cursor < 0 || cursor >= points.size()) return -1;
        int last = points.size() - 1;
        if (terrain.traversable(start, points.get(last))) return last;
        if (last == cursor || !terrain.traversable(start, points.get(cursor))) return -1;
        int best = cursor, low = cursor + 1, high = last - 1;
        for (int query = 2; query < 8 && low <= high; query++) {
            int middle = low + (high - low) / 2;
            if (terrain.traversable(start, points.get(middle))) {
                best = middle; low = middle + 1;
            } else high = middle - 1;
        }
        return best;
    }

    private static double estimate(Goal goal, Vec3 point) {
        if (goal instanceof GoalXZ block) {
            // Boat edges are measured in world distance. Baritone's XZ
            // heuristic includes configurable walking costs and client state;
            // neither belongs in this independent surface graph.
            double dx = Math.max(0, Math.abs(point.x - (block.getX() + .5)) - .5);
            double dz = Math.max(0, Math.abs(point.z - (block.getZ() + .5)) - .5);
            return Math.hypot(dx, dz);
        }
        if (goal instanceof GoalBlock block) {
            double dx = Math.max(0, Math.abs(point.x - (block.x + .5)) - .5);
            double dy = Math.max(0, Math.max(block.y - point.y, point.y - block.y - 1));
            double dz = Math.max(0, Math.abs(point.z - (block.z + .5)) - .5);
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        double value = goal.heuristic(BlockPos.containing(point));
        return Double.isFinite(value) ? Math.max(0, value / 4) : 1e9;
    }

    private static Route route(Node end, boolean reached, int expanded) {
        List<Vec3> points = new ArrayList<>();
        for (Node node = end; node.parent != null; node = node.parent) points.add(node.point);
        Collections.reverse(points);
        return new Route(points, reached, expanded);
    }

    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
