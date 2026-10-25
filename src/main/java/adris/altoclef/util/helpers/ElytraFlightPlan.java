package adris.altoclef.util.helpers;

/** Trip-level approach gates, shared by the live controller and native-physics replays. */
public final class ElytraFlightPlan {
    private ElytraFlightPlan() { }

    public static double approachDistance(double heightAboveGoal, double horizontalSpeed, int boostTicks) {
        return Math.max(120, Math.min(320, Math.max(Math.max(0, heightAboveGoal) * .75,
                Math.max(0, horizontalSpeed) * (Math.max(0, boostTicks) + 65))));
    }

    public static boolean shouldApproach(double heightAboveGoal, double distance, double horizontalSpeed, int boostTicks) {
        // A short trip may turn toward landing before reaching the high-altitude cruise band.
        return heightAboveGoal >= 24 && distance <= approachDistance(heightAboveGoal, horizontalSpeed, boostTicks);
    }

    public static boolean canStartBoost(double heightAboveGoal, double distance, double horizontalSpeed,
                                        int boostTicks, int rocketMaxTicks) {
        // An attached rocket cannot be switched off. Reserve its whole lifetime before descent.
        double cutoff = approachDistance(heightAboveGoal, horizontalSpeed, boostTicks)
                + Math.max(1.7, horizontalSpeed) * (Math.max(0, rocketMaxTicks) + 12);
        return distance > cutoff;
    }

    public static boolean settlingAfterGround(int tick, int lastGroundTick, double heightAbovePad, double verticalSpeed) {
        // Permit a brief server correction at a verified pad, but never mask a real airborne fall.
        return lastGroundTick != Integer.MIN_VALUE && tick - lastGroundTick <= 6
                && heightAbovePad >= -.15 && heightAbovePad <= .6 && Math.abs(verticalSpeed) <= .2;
    }
}
