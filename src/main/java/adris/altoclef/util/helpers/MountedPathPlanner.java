package adris.altoclef.util.helpers;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Bounded, deterministic surface search. Terrain owns mount/rider collision checks. */
public final class MountedPathPlanner {
    public interface Terrain {
        Vec3 stand(int x, int z, double nearY);
        boolean traversable(Vec3 from, Vec3 to);
        default Vec3 standAt(double x, double z, double nearY) { return null; }
        default List<JumpEdge> jumps(Vec3 from) { return List.of(); }
    }
    public record JumpEdge(Vec3 launch, Vec3 target, List<Vec3> arc, int forwardStartTick, int chargeTicks) {
        public JumpEdge { arc = List.copyOf(arc); }
    }
    public record Route(List<Vec3> points, boolean reachedGoal, Map<Integer, JumpEdge> jumps) {
        public Route {
            points = List.copyOf(points);
            jumps = Map.copyOf(jumps);
        }
        public Route(List<Vec3> points, boolean reachedGoal) { this(points, reachedGoal, Map.of()); }
    }
    private record Key(int x, int height, int z) {
        static Key at(Vec3 pos) { return new Key((int)Math.round(pos.x * 4), (int)Math.round(pos.y * 16), (int)Math.round(pos.z * 4)); }
    }
    private record Node(Vec3 pos, double cost, double estimate, Node parent, JumpEdge parentJump, long order) { }
    private static final int[][] DIRECTIONS = {
            {1, 0}, {0, 1}, {-1, 0}, {0, -1},
            {1, 1}, {-1, 1}, {-1, -1}, {1, -1}
    };

    private MountedPathPlanner() { }

    public static Route plan(Vec3 start, Goal goal, Terrain terrain, int budget, int radius) {
        return plan(start, goal, terrain, budget, radius, false);
    }

    public static Route plan(Vec3 start, Goal goal, Terrain terrain, int budget, int radius, boolean allowJumps) {
        if (goal == null || budget <= 0 || radius <= 0) return new Route(List.of(), false);
        var open = new PriorityQueue<Node>(Comparator.comparingDouble((Node node) -> node.cost + node.estimate)
                .thenComparingDouble(Node::estimate).thenComparingLong(Node::order));
        Map<Key, Double> costs = new HashMap<>();
        long order = 0;
        Node first = new Node(start, 0, estimate(goal, start), null, null, order++);
        Node best = first;
        open.add(first);
        costs.put(Key.at(start), 0.0);
        int expanded = 0;
        while (!open.isEmpty() && expanded++ < budget) {
            Node current = open.poll();
            if (current.cost > costs.getOrDefault(Key.at(current.pos), Double.POSITIVE_INFINITY) + 1e-6) continue;
            if (goal.isInGoal(BlockPos.containing(current.pos))) return route(current, true);
            if (current.estimate < best.estimate - 1e-6) best = current;
            BlockPos base = BlockPos.containing(current.pos);
            for (int[] direction : DIRECTIONS) {
                int x = base.getX() + direction[0], z = base.getZ() + direction[1];
                if (Math.abs(x + 0.5 - start.x) > radius || Math.abs(z + 0.5 - start.z) > radius) continue;
                Vec3 next = terrain.stand(x, z, current.pos.y);
                if (next == null || Math.abs(next.y - start.y) > 8 || !terrain.traversable(current.pos, next)) continue;
                if (direction[0] != 0 && direction[1] != 0
                        && !cornerOption(terrain, current.pos, next, x, base.getZ())
                        && !cornerOption(terrain, current.pos, next, base.getX(), z)) continue;
                double cost = current.cost + current.pos.distanceTo(next) + Math.abs(next.y - current.pos.y) * 0.5;
                Key key = Key.at(next);
                if (cost + 1e-6 >= costs.getOrDefault(key, Double.POSITIVE_INFINITY)) continue;
                costs.put(key, cost);
                open.add(new Node(next, cost, estimate(goal, next), current, null, order++));
            }
            if (allowJumps) {
                // A full-width horse can stand on a narrow uphill ledge while
                // neither neighboring block center has enough clearance. Keep
                // small launch-alignment steps local to the actual plan origin.
                for (int[] direction : DIRECTIONS) {
                    double x = current.pos.x + direction[0] * 0.25;
                    double z = current.pos.z + direction[1] * 0.25;
                    if (Math.hypot(x - start.x, z - start.z) > 1.5) continue;
                    Vec3 next = terrain.standAt(x, z, current.pos.y);
                    if (next == null || Math.abs(next.y - start.y) > 8
                            || !terrain.traversable(current.pos, next)) continue;
                    if (direction[0] != 0 && direction[1] != 0
                            && !fineCornerOption(terrain, current.pos, next, x, current.pos.z)
                            && !fineCornerOption(terrain, current.pos, next, current.pos.x, z)) continue;
                    double cost = current.cost + current.pos.distanceTo(next) + Math.abs(next.y - current.pos.y) * 0.5;
                    Key key = Key.at(next);
                    if (cost + 1e-6 >= costs.getOrDefault(key, Double.POSITIVE_INFINITY)) continue;
                    costs.put(key, cost);
                    open.add(new Node(next, cost, estimate(goal, next), current, null, order++));
                }
                // Terrain supplies only jump arcs that it has already validated
                // against this mount's abilities and the complete occupied volume.
                for (JumpEdge jump : terrain.jumps(current.pos)) {
                    Vec3 next = jump.target;
                    if (!Double.isFinite(next.x) || !Double.isFinite(next.y) || !Double.isFinite(next.z)
                            || Math.abs(next.x - start.x) > radius || Math.abs(next.z - start.z) > radius
                            || Math.abs(next.y - start.y) > 8) continue;
                    double cost = current.cost + current.pos.subtract(next).horizontalDistance()
                            + Math.abs(next.y - current.pos.y) * 1.5 + 5 + Math.max(0, jump.chargeTicks) * 0.05;
                    Key key = Key.at(next);
                    if (cost + 1e-6 >= costs.getOrDefault(key, Double.POSITIVE_INFINITY)) continue;
                    costs.put(key, cost);
                    open.add(new Node(next, cost, estimate(goal, next), current, jump, order++));
                }
            }
        }
        // A far goal can be reached over several loaded local windows. Never
        // manufacture random movement when the search made no useful progress.
        return best != first && best.pos.distanceToSqr(start) >= 1 ? route(best, false) : new Route(List.of(), false);
    }

    private static double estimate(Goal goal, Vec3 point) {
        Goal resolved = goal instanceof MountedBlockGoal block ? block.resolved() : goal;
        // Distance to the complete goal cell admits necessary uphill detours,
        // while still rewarding descent when arriving above the destination.
        // Completion continues to use the original precise XYZ predicate.
        if (resolved instanceof GoalBlock block) {
            double dx = Math.max(0, Math.abs(point.x - (block.x + 0.5)) - 0.5);
            double dz = Math.max(0, Math.abs(point.z - (block.z + 0.5)) - 0.5);
            double dy = Math.max(0, Math.max(block.y - point.y, point.y - (block.y + 1)));
            double unitCost = block.heuristic(block.x + 1, block.y, block.z) / 4.0;
            double value = Math.sqrt(dx * dx + dy * dy + dz * dz) * unitCost;
            return Double.isFinite(value) ? value : 1e9;
        }
        double value = resolved.heuristic(BlockPos.containing(point));
        return Double.isFinite(value) ? value / 4.0 : 1e9;
    }

    private static boolean cornerOption(Terrain terrain, Vec3 from, Vec3 to, int sideX, int sideZ) {
        Vec3 side = terrain.stand(sideX, sideZ, from.y);
        // Like Baritone's diagonal movement, one clear side is required as well
        // as the direct swept edge above. Side support can be at either end's
        // height on a slope; requiring identical heights would reject safe steps.
        return side != null && side.y >= Math.min(from.y, to.y) - 0.01
                && side.y <= Math.max(from.y, to.y) + 0.01
                && terrain.traversable(from, side) && terrain.traversable(side, to);
    }

    private static boolean fineCornerOption(Terrain terrain, Vec3 from, Vec3 to, double sideX, double sideZ) {
        Vec3 side = terrain.standAt(sideX, sideZ, from.y);
        return side != null && side.y >= Math.min(from.y, to.y) - 0.01
                && side.y <= Math.max(from.y, to.y) + 0.01
                && terrain.traversable(from, side) && terrain.traversable(side, to);
    }

    private static Route route(Node end, boolean reached) {
        List<Node> nodes = new ArrayList<>();
        for (Node node = end; node.parent != null; node = node.parent) nodes.add(node);
        Collections.reverse(nodes);
        List<Vec3> result = new ArrayList<>();
        Map<Integer, JumpEdge> jumps = new LinkedHashMap<>();
        for (int index = 0; index < nodes.size(); index++) {
            Node node = nodes.get(index);
            result.add(node.pos);
            if (node.parentJump != null) jumps.put(index, node.parentJump);
        }
        return new Route(result, reached, jumps);
    }

    public static float steeringYaw(float current, float desired) {
        return current + net.minecraft.util.Mth.clamp(net.minecraft.util.Mth.wrapDegrees(desired - current), -35, 35);
    }

    public static double stoppingDistance(double horizontalSpeed) {
        return Math.max(0.45, Math.max(0, horizontalSpeed) * 3.5 + 0.2);
    }

    public static boolean canAdvance(float yawError, double speed, double remaining, boolean brakingPoint) {
        if (Math.abs(net.minecraft.util.Mth.wrapDegrees(yawError)) > 22) return false;
        return !brakingPoint || remaining > stoppingDistance(speed) || speed < 0.035 && remaining > 0.35;
    }
}
