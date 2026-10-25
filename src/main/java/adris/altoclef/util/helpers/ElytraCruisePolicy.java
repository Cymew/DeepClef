package adris.altoclef.util.helpers;

/**
 * Trades a rocket's climbing energy for a long, almost level coast. This only selects
 * guidance: native rocket acknowledgements, collision clearance and landing own use.
 */
public final class ElytraCruisePolicy {
    public enum Mode { CLIMB, BOOST_CLIMB, COAST, RECOVER, APPROACH }
    public record Decision(float targetPitch, boolean allowRocket, Mode mode) { }

    private static final double ALTITUDE_BAND = 32;
    private static final int MIN_COAST_TICKS = 44;
    private static final int BOOST_CLIMB_TICKS = 16;
    private static final double MIN_ROCKET_DISTANCE = 96;
    private int lastTick = Integer.MIN_VALUE;
    private int lastBoostTick = Integer.MIN_VALUE;
    private int boostStartedTick = Integer.MIN_VALUE;
    private boolean newBoostRequested;
    private Decision previous;

    public void reset() {
        lastTick = lastBoostTick = Integer.MIN_VALUE;
        boostStartedTick = Integer.MIN_VALUE;
        newBoostRequested = false;
        previous = null;
    }

    /**
     * One update per native tick; {@code boostTicks} is the owner's conservative
     * non-overlap budget, including an already attached rocket's remaining lifetime.
     * {@code availableRocket} includes the owner's inventory, ACK and landing gates.
     * An eligible candidate still requires the planner's full swept-clearance check.
     * The caller supplies a terrain-safe cruise altitude and may veto any rocket.
     */
    public Decision update(int tick, double altitude, double horizontalSpeed, double verticalSpeed,
                           double cruiseAltitude, double remainingDistance, int boostTicks,
                           boolean phaseClimb, boolean availableRocket) {
        if (tick == lastTick && previous != null) return previous;
        if (tick < lastTick) reset();
        if (!Double.isFinite(altitude) || !Double.isFinite(horizontalSpeed)
                || !Double.isFinite(verticalSpeed) || !Double.isFinite(cruiseAltitude)
                || !Double.isFinite(remainingDistance) || horizontalSpeed < 0) {
            return remember(tick, new Decision(0, false, Mode.APPROACH));
        }
        boolean boosted = boostTicks > 0;
        if (boosted) {
            // ACK may extend an old budget (even after a gap); only a new eligible
            // rocket request begins another climbing pulse.
            if (boostStartedTick == Integer.MIN_VALUE || newBoostRequested) boostStartedTick = tick;
            newBoostRequested = false;
            lastBoostTick = tick;
        }
        if (phaseClimb) {
            // Once the approach gate closes, an unpowered climb must retain forward speed.
            if (availableRocket && !boosted) newBoostRequested = true;
            return remember(tick, new Decision(boosted ? climbPitch(altitude, horizontalSpeed, verticalSpeed,
                    cruiseAltitude) : availableRocket ? -35 : 0, availableRocket && !boosted, Mode.CLIMB));
        }
        if (remainingDistance <= MIN_ROCKET_DISTANCE)
            return remember(tick, new Decision(0, false, Mode.APPROACH));

        double low = cruiseAltitude - ALTITUDE_BAND;
        if (boosted) {
            // Level early enough to dissipate upward momentum below the top of the band.
            // In cruise, spend the rest of this same rocket rebuilding forward speed.
            return remember(tick, new Decision((long) tick - boostStartedTick >= BOOST_CLIMB_TICKS ? 0
                    : climbPitch(altitude, horizontalSpeed, verticalSpeed,
                    cruiseAltitude), false, Mode.BOOST_CLIMB));
        }

        // Level native glide retains roughly 30 m/s; a continuous upward coast would stall.
        // Forecast descent, rather than waiting until the lower edge is already crossed.
        boolean lowEnergy = altitude + Math.min(0, verticalSpeed) * 32 <= low
                || horizontalSpeed < .8;
        boolean coasted = lastBoostTick == Integer.MIN_VALUE
                || (long) tick - lastBoostTick >= MIN_COAST_TICKS;
        boolean rocket = availableRocket && coasted && lowEnergy;
        if (rocket) newBoostRequested = true;
        return remember(tick, new Decision(rocket ? -30 : 0, rocket,
                lowEnergy ? Mode.RECOVER : Mode.COAST));
    }

    private static float climbPitch(double altitude, double horizontalSpeed, double verticalSpeed, double high) {
        double rise = Math.max(0, verticalSpeed);
        double apexAllowance = rise * (10 + rise * 12);
        // The conservative lifetime budget can outlive the real rocket. A continued
        // unpowered pull-up would spend forward speed and climb above the intended band.
        if (rise > 1 || horizontalSpeed < 1.2 && rise > .6) return 0;
        return altitude + apexAllowance < high - 2 ? -30 : 0;
    }

    private Decision remember(int tick, Decision decision) {
        lastTick = tick;
        previous = decision;
        return decision;
    }
}
