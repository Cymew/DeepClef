package adris.altoclef.util.helpers;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BiPredicate;

/** Selects native paddle strategies whose complete continuation has been checked by terrain. */
public final class BoatManeuverPlanner {
    public enum Mode { DRIVE, ALIGN, BRAKE }
    public record Plan(BoatSteering.Input input, Mode mode) { }

    private BoatManeuverPlanner() { }

    /**
     * safeForecast must replay continuation(mode, ...) for the whole trajectory,
     * not replace only the initial input and then resume DRIVE unconditionally.
     * The terrain owner also checks a safe stopping tail whenever required.
     */
    public static Optional<Plan> choose(BoatSteering.State state, Vec3 target, double retention,
                                        double speedLimit, BiPredicate<Mode, BoatSteering.Input> safeForecast) {
        return choose(state, target, retention, speedLimit, true, safeForecast);
    }

    public static Optional<Plan> choose(BoatSteering.State state, Vec3 target, double retention,
                                        double speedLimit, boolean stopAtTarget,
                                        BiPredicate<Mode, BoatSteering.Input> safeForecast) {
        return choose(state, target, retention, speedLimit, stopAtTarget, false, safeForecast);
    }

    public static Optional<Plan> chooseRoad(BoatSteering.State state, Vec3 target, double retention,
                                        double speedLimit, boolean stopAtTarget,
                                        BiPredicate<Mode, BoatSteering.Input> safeForecast) {
        return choose(state, target, retention, speedLimit, stopAtTarget, true, safeForecast);
    }

    private static Optional<Plan> choose(BoatSteering.State state, Vec3 target, double retention,
                                        double speedLimit, boolean stopAtTarget, boolean road,
                                        BiPredicate<Mode, BoatSteering.Input> safeForecast) {
        if (state == null || !finite(state.position()) || !finite(state.velocity()) || !finite(target)
                || !Float.isFinite(state.yaw()) || !Float.isFinite(state.angularVelocity())
                || safeForecast == null || !Double.isFinite(retention)
                || retention < 0 || retention > 1 || !Double.isFinite(speedLimit) || speedLimit < 0) return Optional.empty();
        BoatSteering.Input preferred = road ? continuationRoad(Mode.DRIVE, state, target, retention, speedLimit, stopAtTarget)
                : continuation(Mode.DRIVE, state, target, retention, speedLimit, stopAtTarget);
        if (safeForecast.test(Mode.DRIVE, preferred)) return Optional.of(new Plan(preferred, Mode.DRIVE));

        // Retain a verified stop before secondary candidates can spend the shared
        // forecast budget. A failed alignment cannot erase this safe fallback.
        BoatSteering.Input brake = road ? continuationRoad(Mode.BRAKE, state, target, retention, speedLimit, true)
                : continuation(Mode.BRAKE, state, target, retention, speedLimit);
        boolean safeBrake = road && safeForecast.test(Mode.BRAKE, brake);
        BoatSteering.Input align = road ? continuationRoad(Mode.ALIGN, state, target, retention, speedLimit, stopAtTarget)
                : continuation(Mode.ALIGN, state, target, retention, speedLimit, stopAtTarget);
        if (safeForecast.test(Mode.ALIGN, align)) return Optional.of(new Plan(align, Mode.ALIGN));
        // Equal-magnitude .005 impulses can prefer different sides of a narrow
        // bend. Check the other first pulse with the same low-drift continuation.
        var alternate = new BoatSteering.Input(align.left(), align.right(), false, !align.back());
        if (safeForecast.test(Mode.ALIGN, alternate)) return Optional.of(new Plan(alternate, Mode.ALIGN));

        List<BoatSteering.Input> candidates = new ArrayList<>();
        for (int turn = -1; turn <= 1; turn++) for (int throttle = -1; throttle <= 1; throttle++) {
            var candidate = new BoatSteering.Input(turn < 0, turn > 0, throttle > 0, throttle < 0);
            if (!candidate.equals(preferred)) candidates.add(candidate);
        }
        candidates.sort(Comparator.comparingDouble(input -> score(state, target, retention, speedLimit, input)));
        for (BoatSteering.Input candidate : candidates) {
            if (safeForecast.test(Mode.DRIVE, candidate)) return Optional.of(new Plan(candidate, Mode.DRIVE));
        }

        return safeBrake || !road && safeForecast.test(Mode.BRAKE, brake)
                ? Optional.of(new Plan(brake, Mode.BRAKE)) : Optional.empty();
    }

    public static BoatSteering.Input continuation(Mode mode, BoatSteering.State state, Vec3 target,
                                                  double retention, double speedLimit) {
        return continuation(mode, state, target, retention, speedLimit, true);
    }

    public static BoatSteering.Input continuation(Mode mode, BoatSteering.State state, Vec3 target,
                                                  double retention, double speedLimit, boolean stopAtTarget) {
        return switch (mode) {
            case DRIVE -> BoatSteering.steer(state, target, retention, speedLimit, stopAtTarget);
            case BRAKE -> BoatSteering.steer(state, state.position(), retention, 0, true);
            case ALIGN -> align(state, target, retention, !stopAtTarget);
        };
    }

    public static BoatSteering.Input continuationRoad(Mode mode, BoatSteering.State state, Vec3 target,
                                                  double retention, double speedLimit, boolean terminal) {
        return switch (mode) {
            case DRIVE -> BoatSteering.steerRoad(state, target, retention, speedLimit, terminal);
            case ALIGN -> align(state, target, retention, true);
            case BRAKE -> BoatSteering.brakeRoad(state, retention);
        };
    }

    /** Turn toward the route while actively minimizing linear drift, never pressing W. */
    private static BoatSteering.Input align(BoatSteering.State state, Vec3 target, double retention, boolean curve) {
        Vec3 delta = target.subtract(state.position()).multiply(1, 0, 1);
        float desired = delta.horizontalDistance() < 1e-7 ? state.yaw() : BoatSteering.yawOf(delta);
        float error = Mth.wrapDegrees(desired - state.yaw());
        double torque = curve ? Mth.clamp(error * .12, -3, 3) - state.angularVelocity() * retention
                : error * .30 - state.angularVelocity() * retention * 1.15;
        boolean left = torque < -.5, right = torque > .5;
        var free = new BoatSteering.Input(left, right, false, false);
        var reverse = new BoatSteering.Input(left, right, false, true);
        double freeSpeed = BoatSteering.step(state, free, retention).velocity().horizontalDistanceSqr();
        double reverseSpeed = BoatSteering.step(state, reverse, retention).velocity().horizontalDistanceSqr();
        return reverseSpeed <= freeSpeed + 1e-12 ? reverse : free;
    }

    private static double score(BoatSteering.State state, Vec3 target, double retention, double speedLimit,
                                BoatSteering.Input input) {
        BoatSteering.State next = BoatSteering.step(state, input, retention);
        Vec3 delta = target.subtract(next.position()).multiply(1, 0, 1);
        double heading = delta.horizontalDistance() < 1e-7 ? 0
                : Math.abs(Mth.wrapDegrees(BoatSteering.yawOf(delta) - next.yaw()));
        double excess = Math.max(0, next.velocity().horizontalDistance() - speedLimit);
        return delta.horizontalDistance() + heading * .003 + excess * 2;
    }

    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
