package adris.altoclef.util.helpers;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Optional;

/** Checks actual swept volumes and supported landings along a vanilla jump. */
public final class MountedJumpPlanner {
    public interface World {
        Vec3 stand(Vec3 point, double nearY);
        boolean clear(Vec3 from, Vec3 to);
        default Vec3 landingSurface(Vec3 point, double nearY) { return stand(point, nearY); }
    }

    private MountedJumpPlanner() { }

    public static Optional<MountedPathPlanner.JumpEdge> find(Vec3 from, Vec3 direction,
            MountedJumpPhysics.Parameters parameters, double safeFallDistance, World world) {
        if (MountedJumpPhysics.unsupportedReason(parameters).isPresent()
                || Math.abs(parameters.chargeScale() - 1) > 1e-6
                || !Double.isFinite(safeFallDistance) || safeFallDistance <= 0
                || direction.horizontalDistanceSqr() < 0.5) return Optional.empty();
        Vec3 forward = direction.multiply(1, 0, 1).normalize();
        var trajectory = MountedJumpPhysics.simulateToHeight(parameters, -Math.min(3, safeFallDistance));
        var arc = new ArrayList<Vec3>();
        arc.add(from);
        Vec3 previous = from;
        for (int index = 1; index < trajectory.positions().size(); index++) {
            Vec3 relative = trajectory.positions().get(index);
            Vec3 next = from.add(forward.scale(relative.z)).add(0, relative.y, 0);
            if (next.y < previous.y) {
                // Y is resolved before horizontal motion. The previous footprint
                // must already overlap the supporting surface on this tick.
                Vec3 surface = world.landingSurface(previous, previous.y);
                if (surface != null && surface.y <= previous.y + 1e-6 && surface.y >= next.y - 1e-6) {
                    // Landing clips only Y; vanilla still resolves the complete
                    // horizontal movement of this tick instead of interpolating it.
                    Vec3 landing = world.landingSurface(new Vec3(next.x, surface.y, next.z), surface.y);
                    if (landing == null || Math.abs(landing.y - surface.y) > 0.05
                            || from.y + trajectory.apexHeight() - landing.y > safeFallDistance
                            || !usefulLanding(from, landing)
                            || !clearMovement(world, previous, landing)) return Optional.empty();
                    arc.add(landing);
                    return Optional.of(new MountedPathPlanner.JumpEdge(from, landing, arc,
                            parameters.forwardStartTick(), 11));
                }
            }
            if (!clearMovement(world, previous, next)) return Optional.empty();
            arc.add(next);
            previous = next;
        }
        return Optional.empty();
    }

    private static boolean usefulLanding(Vec3 from, Vec3 landing) {
        // A horse can gain a foothold on the edge of a narrow uphill step.
        // Requiring a full horizontal block discards exactly those short hops.
        double minimum = landing.y >= from.y + 0.5 ? 0.35 : 0.9;
        return from.subtract(landing).horizontalDistance() >= minimum;
    }

    private static boolean clearMovement(World world, Vec3 from, Vec3 to) {
        // Entity.collideWithShapes resolves Y before the larger horizontal axis.
        // Sweeping one enclosing box for the diagonal also tests empty space
        // below the rising horse and incorrectly rejects nearby uphill steps.
        Vec3 movement = to.subtract(from);
        Vec3 cursor = from;
        for (Direction.Axis axis : Direction.axisStepOrder(movement)) {
            double distance = movement.get(axis);
            if (distance == 0) continue;
            Vec3 next = cursor.with(axis, cursor.get(axis) + distance);
            if (!world.clear(cursor, next)) return false;
            cursor = next;
        }
        return true;
    }
}
