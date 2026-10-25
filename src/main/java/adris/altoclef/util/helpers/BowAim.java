package adris.altoclef.util.helpers;

import net.minecraft.world.item.BowItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/** Airborne arrow prediction: move first, then air drag, then gravity, in blocks/tick. */
public final class BowAim {
    public static final double AIR_DRAG = (double) 0.99f;
    public static final double GRAVITY = 0.05;
    public static final int FULL_DRAW_TICKS = BowItem.MAX_DRAW_DURATION;
    public static final double MAX_FLIGHT_TICKS = 80;

    public record Solution(float yaw, float pitch, Vec3 direction, Vec3 initialVelocity,
                           double flightTicks, Vec3 intercept) { }

    private BowAim() { }

    public static Optional<Solution> solve(Vec3 origin, AABB target, Vec3 targetMotion,
                                           Vec3 inheritedMotion, int drawTicks) {
        return solve(origin, target.getCenter(), targetMotion, inheritedMotion, drawTicks);
    }

    public static Optional<Solution> solve(Vec3 origin, Vec3 targetPoint, Vec3 targetMotion,
                                           Vec3 inheritedMotion, int drawTicks) {
        if (!finite(origin) || !finite(targetPoint) || !finite(targetMotion) || !finite(inheritedMotion)) {
            return Optional.empty();
        }
        double speed = BowItem.getPowerForTime(Math.max(0, drawTicks)) * 3.0;
        if (speed < 0.3 || origin.distanceToSqr(targetPoint) < 1.0e-8) return Optional.empty();
        double previous = 0.01;
        double previousError = speedError(origin, targetPoint, targetMotion, inheritedMotion, previous, speed);
        // The first crossing gives the practical, shorter flight instead of the high lob.
        for (double time = 0.25; time <= MAX_FLIGHT_TICKS; time += 0.25) {
            double error = speedError(origin, targetPoint, targetMotion, inheritedMotion, time, speed);
            if (error <= 0 && previousError > 0) {
                double low = previous;
                double high = time;
                for (int i = 0; i < 36; i++) {
                    double middle = (low + high) * 0.5;
                    if (speedError(origin, targetPoint, targetMotion, inheritedMotion, middle, speed) > 0) low = middle;
                    else high = middle;
                }
                double flight = (low + high) * 0.5;
                Vec3 intercept = targetPoint.add(targetMotion.scale(flight));
                Vec3 launch = requiredVelocity(origin, intercept, flight).subtract(inheritedMotion);
                Vec3 direction = launch.normalize();
                Vec3 initial = direction.scale(speed).add(inheritedMotion);
                float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
                float pitch = (float) -Math.toDegrees(Math.atan2(direction.y, direction.horizontalDistance()));
                if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) return Optional.empty();
                return Optional.of(new Solution(yaw, pitch, direction, initial, flight, intercept));
            }
            previous = time;
            previousError = error;
        }
        return Optional.empty();
    }

    private static double speedError(Vec3 origin, Vec3 target, Vec3 motion, Vec3 inherited,
                                    double time, double speed) {
        Vec3 needed = requiredVelocity(origin, target.add(motion.scale(time)), time).subtract(inherited);
        return needed.lengthSqr() - speed * speed;
    }

    private static Vec3 requiredVelocity(Vec3 origin, Vec3 target, double time) {
        return target.subtract(origin).add(0, gravityDrop(time), 0).scale(1.0 / travelFactor(time));
    }

    /** Exact straight segment interpolation within each discrete arrow tick. */
    public static Vec3 positionAt(Vec3 origin, Vec3 initialVelocity, double time) {
        double clamped = Math.max(0, time);
        return origin.add(initialVelocity.scale(travelFactor(clamped))).add(0, -gravityDrop(clamped), 0);
    }

    public static Vec3 velocityAt(Vec3 initialVelocity, int ticks) {
        double drag = Math.pow(AIR_DRAG, Math.max(0, ticks));
        return initialVelocity.scale(drag).add(0, -GRAVITY * (1 - drag) / (1 - AIR_DRAG), 0);
    }

    private static double travelFactor(double time) {
        int fullTicks = (int) Math.floor(time);
        double drag = Math.pow(AIR_DRAG, fullTicks);
        return (1 - drag) / (1 - AIR_DRAG) + (time - fullTicks) * drag;
    }

    private static double gravityDrop(double time) {
        int fullTicks = (int) Math.floor(time);
        double drag = Math.pow(AIR_DRAG, fullTicks);
        double sum = (1 - drag) / (1 - AIR_DRAG);
        return GRAVITY / (1 - AIR_DRAG)
                * (fullTicks - sum + (time - fullTicks) * (1 - drag));
    }

    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}
