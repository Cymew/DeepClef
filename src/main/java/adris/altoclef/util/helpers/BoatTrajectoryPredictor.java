package adris.altoclef.util.helpers;

import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Predicts a native paddle continuation and an actively braked, verified stopping tail. */
public final class BoatTrajectoryPredictor {
    public static final int MAX_CANDIDATE_STEPS = 320;

    public interface Dynamics {
        /** Retention for the next native tick, including water-entry or material transitions. */
        double retention(BoatSteering.State current);
        /** Apply native floating/control/movement and its complete body sweep; null means unverified. */
        BoatSteering.State step(BoatSteering.State current, BoatSteering.Input input);
        /** Verify the remaining unpowered drift from a low-speed state through known terrain. */
        boolean safeCoast(BoatSteering.State current);
        /** A checked road continues beyond the braking/latency reserve. */
        default boolean rollingCorridor(BoatSteering.State current) { return false; }
    }

    public record Guidance(Vec3 target, double speedLimit, boolean terminal, boolean rollingAllowed) {
        public Guidance(Vec3 target,double speedLimit,boolean terminal) { this(target,speedLimit,terminal,true); }
    }
    public interface Guide { Guidance at(BoatSteering.State state); }

    /** Shared by every candidate of one live navigation frame, never renewed by a failed forecast. */
    public static final class Budget {
        private final int maximumSteps;
        private final long maximumNanos, started;
        private final LongSupplier clock;
        private int steps;

        public Budget(int maximumSteps) { this(maximumSteps, Long.MAX_VALUE); }
        public Budget(int maximumSteps, long maximumNanos) { this(maximumSteps, maximumNanos, System::nanoTime); }
        Budget(int maximumSteps, long maximumNanos, LongSupplier clock) {
            this.maximumSteps = Math.max(0, maximumSteps);
            this.maximumNanos = Math.max(0, maximumNanos);
            this.clock = Objects.requireNonNull(clock);
            started = clock.getAsLong();
        }
        public int usedSteps() { return steps; }
        public boolean exhausted() { return steps >= maximumSteps || !hasTime(); }
        boolean hasTime() { return maximumNanos > 0 && clock.getAsLong() - started < maximumNanos; }
        boolean claim() {
            if (exhausted()) return false;
            steps++;
            return true;
        }
    }

    private BoatTrajectoryPredictor() { }

    public static boolean predict(BoatSteering.State initial, BoatSteering.Input first, Vec3 target,
                                  double speedLimit, BoatManeuverPlanner.Mode mode, int horizon,
                                  Dynamics dynamics, Budget budget) {
        if (!finite(target) || !Double.isFinite(speedLimit) || speedLimit < 0) return false;
        Guidance fixed = new Guidance(target, speedLimit, true);
        return predict(initial, first, state -> fixed, mode, horizon, dynamics, budget);
    }

    /** Follow the rolling curve throughout the forecast instead of stopping at a local pursuit point. */
    public static boolean predict(BoatSteering.State initial, BoatSteering.Input first, Guide guide,
                                  BoatManeuverPlanner.Mode mode, int horizon, Dynamics dynamics, Budget budget) {
        return predict(initial, first, guide, mode, horizon, dynamics, budget, false);
    }

    public static boolean predictRoad(BoatSteering.State initial, BoatSteering.Input first, Guide guide,
                                  BoatManeuverPlanner.Mode mode, int horizon, Dynamics dynamics, Budget budget) {
        return predict(initial, first, guide, mode, horizon, dynamics, budget, true);
    }

    private static boolean predict(BoatSteering.State initial, BoatSteering.Input first, Guide guide,
                                  BoatManeuverPlanner.Mode mode, int horizon, Dynamics dynamics, Budget budget, boolean road) {
        if (!valid(initial) || first == null || guide == null
                || mode == null || horizon < 1 || horizon >= MAX_CANDIDATE_STEPS
                || dynamics == null || budget == null) return false;
        BoatSteering.State current = initial;
        Guidance entry=road && mode==BoatManeuverPlanner.Mode.DRIVE?guide.at(initial):null;
        boolean rolling=entry!=null && !entry.terminal() && entry.rollingAllowed();
        if(rolling) horizon=Math.max(horizon,32);
        boolean braking=false;
        for (int tick = 0; tick < MAX_CANDIDATE_STEPS; tick++) {
            if(rolling && tick==horizon && budget.hasTime()) {
                Guidance future=guide.at(current);
                if(future!=null && finite(future.target()) && Double.isFinite(future.speedLimit())
                        && future.speedLimit()>=0 && !future.terminal() && dynamics.rollingCorridor(current)) return budget.hasTime();
            }
            if (tick >= horizon && nearlyStopped(current)) {
                if (!budget.hasTime()) return false;
                // Failing to coast over a nearby edge is not a reason to give up
                // an active brake which can remove the final residual velocity.
                if (dynamics.safeCoast(current)) return budget.hasTime();
            }
            if (!budget.claim()) return false;
            double retention = dynamics.retention(current);
            if (!Double.isFinite(retention) || retention < 0 || retention > 1) return false;
            Guidance guidance = guide.at(current);
            if (guidance == null || !finite(guidance.target()) || !Double.isFinite(guidance.speedLimit())
                    || guidance.speedLimit() < 0) return false;
            if(tick>=horizon) braking=true;
            BoatManeuverPlanner.Mode continuation=braking?BoatManeuverPlanner.Mode.BRAKE:mode;
            BoatSteering.Input input = tick == 0 ? first
                    : road ? BoatManeuverPlanner.continuationRoad(continuation, current, guidance.target(), retention,
                        guidance.speedLimit(), guidance.terminal())
                    : BoatManeuverPlanner.continuation(continuation, current, guidance.target(), retention,
                        guidance.speedLimit(), guidance.terminal());
            BoatSteering.State next = dynamics.step(current, input);
            if (!valid(next) || !budget.hasTime()) return false;
            current = next;
        }
        return nearlyStopped(current) && budget.hasTime() && dynamics.safeCoast(current) && budget.hasTime();
    }

    private static boolean nearlyStopped(BoatSteering.State state) {
        return state.velocity().horizontalDistance() <= .015 && Math.abs(state.angularVelocity()) <= .2;
    }
    private static boolean valid(BoatSteering.State state) {
        return state != null && finite(state.position()) && finite(state.velocity())
                && Float.isFinite(state.yaw()) && Float.isFinite(state.angularVelocity());
    }
    private static boolean finite(Vec3 point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }
}
