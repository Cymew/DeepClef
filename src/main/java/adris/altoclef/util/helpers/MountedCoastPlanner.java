package adris.altoclef.util.helpers;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Predicts one grounded forward input sample followed by unpowered coasting. */
public final class MountedCoastPlanner {
    public interface World extends MountedJumpPlanner.World {
        /** Effective ground friction times horizontal air drag at this point. */
        double groundDrag(Vec3 point);
        /** Physical foot contact; override padded navigation landing probes with the full mount footprint. */
        default Vec3 coastLandingSurface(Vec3 point, double nearY) { return landingSurface(point, nearY); }
    }

    private static final double MOTION_EPSILON = 0.003;
    private static final double CONTACT_EPSILON = 1e-6;

    private MountedCoastPlanner() { }

    /**
     * The caller supplies the actual grounded mount state and joint horse/rider
     * clearance callbacks. This method changes no entity, input or world state.
     * A missing floor is allowed in flight, but never at the final stopped point.
     */
    public static Optional<List<Vec3>> predict(Vec3 origin, Vec3 velocity, Vec3 forward,
            double initialGroundAcceleration, double gravity, double airDrag, double verticalDrag,
            double maxDrop, int maxTicks, World world) {
        if (!finite(origin) || !finite(velocity) || !finite(forward) || world == null
                || !Double.isFinite(initialGroundAcceleration) || initialGroundAcceleration < 0
                || !Float.isFinite((float) initialGroundAcceleration)
                || !Double.isFinite(gravity) || gravity <= 0
                || !unit(airDrag) || !unit(verticalDrag)
                || !Double.isFinite(maxDrop) || maxDrop < 0 || maxTicks < 1 || maxTicks > 1000
                || forward.horizontalDistanceSqr() < 1e-7 || !world.clear(origin, origin)) return Optional.empty();
        // Initial onGround comes from the real mount. Collision-tolerant support
        // probes can miss a thin rear overlap and report the next lower ledge;
        // requiring equal probe height here would reject that valid starting
        // state. Flight and the final stopped contact are still fully checked.
        Vec3 direction = forward.multiply(1, 0, 1).normalize();
        Vec3 motion = velocity;
        Vec3 position = origin;
        double highestY = origin.y;
        boolean grounded = true;
        List<Vec3> points = new ArrayList<>();
        points.add(origin);
        for (int tick = 0; tick < maxTicks; tick++) {
            // LivingEntity removes tiny velocity components before this tick's
            // acceleration. Preserve lateral motion rather than projecting it.
            motion = new Vec3(snap(motion.x), snap(motion.y), snap(motion.z));
            boolean startedGrounded = grounded;
            double drag = startedGrounded ? world.groundDrag(position) : airDrag;
            if (!unit(drag)) return Optional.empty();
            if (tick == 0) motion = motion.add(direction.scale((double) 0.98f * (float) initialGroundAcceleration));

            double nextY = position.y + motion.y;
            grounded = false;
            if (motion.y < 0) {
                // Entity resolves Y at the old X/Z footprint. It can only land
                // on a surface already underneath that footprint this tick.
                Vec3 support = world.coastLandingSurface(position, position.y);
                if (support != null && !finite(support)) return Optional.empty();
                if (support != null && support.y <= position.y + CONTACT_EPSILON
                        && support.y >= nextY - CONTACT_EPSILON) {
                    nextY = support.y;
                    grounded = true;
                }
            }
            Vec3 next = new Vec3(position.x + motion.x, nextY, position.z + motion.z);
            highestY = Math.max(highestY, next.y);
            if (!finite(next) || highestY - next.y > maxDrop + CONTACT_EPSILON
                    || !clearMovement(world, position, next)) return Optional.empty();
            points.add(next);
            position = next;

            // Floor collision cancels vertical motion before gravity/drag.
            // Ground friction belongs to the tick's starting grounded flag,
            // even if this tick lands or its later X/Z motion leaves the edge.
            double vy = grounded ? 0 : motion.y;
            motion = new Vec3(motion.x * drag, (vy - gravity) * verticalDrag, motion.z * drag);
            if (grounded && Math.abs(motion.x) < MOTION_EPSILON && Math.abs(motion.z) < MOTION_EPSILON
                    && sameHeight(world.coastLandingSurface(position, position.y), position.y)
                    && world.clear(position, position)) return Optional.of(List.copyOf(points));
        }
        return Optional.empty();
    }

    private static boolean clearMovement(World world, Vec3 from, Vec3 to) {
        Vec3 movement = to.subtract(from), cursor = from;
        for (Direction.Axis axis : Direction.axisStepOrder(movement)) {
            double amount = movement.get(axis);
            if (amount == 0) continue;
            Vec3 next = cursor.with(axis, cursor.get(axis) + amount);
            if (!world.clear(cursor, next)) return false;
            cursor = next;
        }
        return true;
    }

    private static double snap(double value) { return Math.abs(value) < MOTION_EPSILON ? 0 : value; }

    private static boolean sameHeight(Vec3 surface, double height) {
        return finite(surface) && Math.abs(surface.y - height) <= CONTACT_EPSILON;
    }

    private static boolean unit(double value) { return Double.isFinite(value) && value >= 0 && value <= 1; }

    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
