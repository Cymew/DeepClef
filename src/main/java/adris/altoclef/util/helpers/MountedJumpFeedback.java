package adris.altoclef.util.helpers;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;

/** Observed jump completion and bounded retry memory, independent of entities or a client world. */
public final class MountedJumpFeedback {
    private static final double HORIZONTAL_TOLERANCE = 0.8;
    private static final double VERTICAL_TOLERANCE = 0.65;
    private static final double SAME_DIRECTION_DOT = 0.95;
    private static final double EPSILON = 1e-9;
    private static final int COOLDOWN_TICKS = 100;
    private static final int MAX_FAILURES = 24;
    private final Deque<Failure> failures = new ArrayDeque<>();

    /** Landing requires the planned elevation and destination, plus actual forward progress. */
    public static boolean landedAt(MountedPathPlanner.JumpEdge edge, Vec3 actual) {
        Vec3 direction = direction(edge);
        if (direction == null || !finite(actual)) return false;
        Vec3 error = actual.subtract(edge.target());
        if (error.horizontalDistanceSqr() > HORIZONTAL_TOLERANCE * HORIZONTAL_TOLERANCE + EPSILON
                || Math.abs(error.y) > VERTICAL_TOLERANCE + EPSILON) return false;
        double plannedRise = edge.target().y - edge.launch().y;
        double actualRise = actual.y - edge.launch().y;
        if (plannedRise >= 0.5 && actualRise + EPSILON < Math.min(0.4, plannedRise - 0.1)) return false;
        double plannedDistance = edge.target().subtract(edge.launch()).horizontalDistance();
        double progress = actual.subtract(edge.launch()).dot(direction);
        return progress + EPSILON >= Math.min(0.6, plannedDistance * 0.5);
    }

    /** Refreshes an equivalent failed attempt without retaining its potentially large trajectory. */
    public void recordFailure(MountedPathPlanner.JumpEdge edge, int tick) {
        Vec3 direction = direction(edge);
        if (direction == null) return;
        discardExpired(tick);
        failures.removeIf(failure -> failure.matches(edge, direction));
        while (failures.size() >= MAX_FAILURES) failures.removeFirst();
        failures.addLast(new Failure(edge.launch(), direction, edge.forwardStartTick(), tick));
    }

    /** Different takeoff positions, directions and forward delays remain available to the planner. */
    public boolean allows(MountedPathPlanner.JumpEdge edge, int tick) {
        Vec3 direction = direction(edge);
        if (direction == null) return false;
        discardExpired(tick);
        for (Failure failure : failures) {
            if (failure.matches(edge, direction)) return false;
        }
        return true;
    }

    /** Clear after observed useful progress or changing worlds, rather than ordinary task ownership. */
    public void clear() {
        failures.clear();
    }

    private void discardExpired(int tick) {
        // Subtraction in tick space also preserves short cooldowns across integer wraparound.
        failures.removeIf(failure -> {
            int elapsed = tick - failure.tick;
            return elapsed < 0 || elapsed >= COOLDOWN_TICKS;
        });
    }

    private record Failure(Vec3 launch, Vec3 direction, int forwardDelay, int tick) {
        private boolean matches(MountedPathPlanner.JumpEdge edge, Vec3 candidateDirection) {
            Vec3 offset = edge.launch().subtract(launch);
            return edge.forwardStartTick() == forwardDelay
                    && offset.horizontalDistanceSqr() <= HORIZONTAL_TOLERANCE * HORIZONTAL_TOLERANCE + EPSILON
                    && Math.abs(offset.y) <= VERTICAL_TOLERANCE + EPSILON
                    && direction.dot(candidateDirection) > SAME_DIRECTION_DOT;
        }
    }

    private static Vec3 direction(MountedPathPlanner.JumpEdge edge) {
        if (edge == null || !finite(edge.launch()) || !finite(edge.target())) return null;
        Vec3 delta = edge.target().subtract(edge.launch()).multiply(1, 0, 1);
        double lengthSquared = delta.horizontalDistanceSqr();
        if (!finite(delta) || !Double.isFinite(lengthSquared) || lengthSquared <= 1e-8) return null;
        return delta.normalize();
    }

    private static boolean finite(Vec3 point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }
}
