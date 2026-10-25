package adris.altoclef.util.helpers;

/** Task-owned mounted travel progress; reads observations and never changes navigation or input. */
public final class MountedTravelProgress {
    public enum Phase { TRAVELLING, BLOCKED, TURNING, CHARGING, AIRBORNE, SETTLING, PAUSED }
    public enum Decision { CONTINUE, REPLAN, WALK }

    private static final double MIN_PROGRESS = 0.5;
    private static final double EPSILON = 1e-9;
    private static final int BLOCKED_LIMIT = 80;
    private static final int EFFORT_LIMIT = 160;
    private static final int PROTECTED_BUDGET = 60;
    private static final int MAX_REPLANS = 2;

    private boolean sampledTick, sampledDistance, walking;
    private int lastTick, blockedTicks, effortTicks, protectedTicks, replans;
    private double bestRemaining;

    /** Use for a new goal/world/mount, rather than ordinary replans or foreground-chain interruptions. */
    public void reset() {
        sampledTick = false;
        sampledDistance = false;
        walking = false;
        lastTick = blockedTicks = effortTicks = protectedTicks = replans = 0;
        bestRemaining = Double.POSITIVE_INFINITY;
    }

    /**
     * remainingDistance must be a stable lower-is-closer goal metric (normally blocks).
     * Only a grounded, non-charging observation can establish useful goal progress.
     * Count at most one active observation per tick, so time spent on another task
     * does not become mounted travel effort. WALK stays latched until reset, but
     * both action decisions are deferred while paused, charging or off the ground.
     */
    public Decision update(int tick, double remainingDistance, boolean onGround, Phase phase) {
        int elapsed = sampledTick ? tick - lastTick : 0;
        lastTick = tick;
        sampledTick = true;
        int activeTick = elapsed > 0 ? 1 : 0;
        if (phase == null || phase == Phase.PAUSED || !Double.isFinite(remainingDistance)) return Decision.CONTINUE;

        boolean canAct = onGround && phase != Phase.CHARGING && phase != Phase.AIRBORNE;
        if (walking) return canAct ? Decision.WALK : Decision.CONTINUE;
        if (canAct && (!sampledDistance || remainingDistance <= bestRemaining - MIN_PROGRESS + EPSILON)) {
            bestRemaining = remainingDistance;
            sampledDistance = true;
            blockedTicks = effortTicks = protectedTicks = replans = 0;
            return Decision.CONTINUE;
        }
        // A task first observed in flight needs a grounded baseline, not a transient jump-apex estimate.
        if (!sampledDistance) return Decision.CONTINUE;

        boolean protectedAction = !onGround || phase == Phase.TURNING || phase == Phase.CHARGING
                || phase == Phase.AIRBORNE || phase == Phase.SETTLING;
        if (activeTick > 0) {
            if (protectedAction && protectedTicks < PROTECTED_BUDGET) {
                protectedTicks++;
            } else {
                effortTicks = Math.min(EFFORT_LIMIT, effortTicks + 1);
                if (phase == Phase.BLOCKED) blockedTicks = Math.min(BLOCKED_LIMIT, blockedTicks + 1);
            }
        }
        if (!canAct || blockedTicks < BLOCKED_LIMIT && effortTicks < EFFORT_LIMIT) return Decision.CONTINUE;
        blockedTicks = effortTicks = 0;
        if (replans < MAX_REPLANS) {
            replans++;
            return Decision.REPLAN;
        }
        walking = true;
        return Decision.WALK;
    }
}
