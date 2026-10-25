package adris.altoclef.util.helpers;

import java.util.Locale;

/** One-native-tick diagnostics; this monitor never selects or applies controls. */
public final class BoatPredictionMonitor {
    private boolean pending, measured, observed;
    private int submittedTick, observedTick, measurementTick;
    private BoatSteering.State before, expected;
    private double positionError, velocityError, yawError, angularError, verticalDisplacement;
    private String unknown = "no submission";

    public void reset() {
        pending = measured = observed = false;
        before = expected = null;
        unknown = "reset";
    }

    /**
     * Supply the boat's native state immediately before its next physics tick,
     * and the four paddle bits actually submitted after all overrides. Repeated
     * submissions in that same native tick replace the earlier expectation.
     */
    public void submitted(int boatTick, BoatSteering.State state, BoatSteering.Input applied, double retention) {
        if (!valid(state) || applied == null || !Double.isFinite(retention) || retention < 0 || retention > 1) {
            pending = false;
            markUnknown("invalid submission");
            return;
        }
        BoatSteering.State prediction = BoatSteering.step(state, applied, retention);
        if (!valid(prediction)) {
            pending = false;
            markUnknown("invalid prediction");
            return;
        }
        if (pending && boatTick != submittedTick && (!observed || observedTick != boatTick))
            markUnknown("missed observation");
        before = state;
        expected = prediction;
        submittedTick = boatTick;
        pending = true;
        // Keep the most recent completed comparison visible while the next
        // input waits for physics. Submitting an input is not an observation.
        if (!measured) unknown = "waiting for next tick";
    }

    /** Observe the boat, never its rider or the interpolated player camera. */
    public void observed(int boatTick, BoatSteering.State actual) {
        if (!valid(actual)) {
            pending = false;
            observed = false;
            markUnknown("invalid observation");
            return;
        }
        // More than one caller can sample the same completed native tick.
        // Do not erase its comparison after an input for the next tick arrives.
        if (observed && observedTick == boatTick) return;
        observed = true;
        observedTick = boatTick;
        if (!pending) {
            markUnknown("no submission");
            return;
        }
        if (boatTick == submittedTick) {
            markUnknown("waiting for next tick");
            return;
        }
        // Java's int increment preserves the native counter's wrap at MAX_VALUE.
        if (boatTick != submittedTick + 1) {
            pending = false;
            markUnknown("tick gap");
            return;
        }
        double dx = actual.position().x - expected.position().x;
        double dz = actual.position().z - expected.position().z;
        positionError = Math.hypot(dx, dz);
        double vx = actual.velocity().x - expected.velocity().x;
        double vz = actual.velocity().z - expected.velocity().z;
        velocityError = Math.hypot(vx, vz);
        yawError = Math.abs(wrap((double)actual.yaw() - expected.yaw()));
        angularError = Math.abs((double)actual.angularVelocity() - expected.angularVelocity());
        // The horizontal predictor does not integrate gravity or buoyancy.
        // Report native boat height change separately, without treating it as
        // horizontal model error or using a seated player's vertical motion.
        verticalDisplacement = actual.position().y - before.position().y;
        measurementTick = boatTick;
        measured = true;
        unknown = null;
    }

    public String summary() {
        if (!measured) return "pred=unknown(" + unknown + "); posXZ=? velXZ=? yaw=? angular=? dy=?";
        return String.format(Locale.ROOT, "pred[t=%d] posXZ=%.4f velXZ=%.5f yaw=%.3f angular=%.3f dy=%+.4f",
                measurementTick, positionError, velocityError, yawError, angularError, verticalDisplacement);
    }

    private void markUnknown(String reason) {
        measured = false;
        unknown = reason;
    }

    private static double wrap(double degrees) {
        degrees %= 360;
        if (degrees >= 180) degrees -= 360;
        if (degrees < -180) degrees += 360;
        return degrees;
    }

    private static boolean valid(BoatSteering.State state) {
        return state != null && state.position() != null && state.velocity() != null
                && finite(state.position().x, state.position().y, state.position().z)
                && finite(state.velocity().x, state.velocity().y, state.velocity().z)
                && Float.isFinite(state.yaw()) && Float.isFinite(state.angularVelocity());
    }

    private static boolean finite(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }
}
