package adris.altoclef.control;

/** Keeps a reachable mining stance using native jump input, without changing velocity. */
public final class MiningWaterControl {
    private boolean active;
    private double height;
    private int lostReachTicks;

    public void update(boolean inWater, boolean reachable, double y) {
        if (!inWater) {
            reset();
        } else if (reachable) {
            if (!active) height = y;
            active = true;
            lostReachTicks = 0;
        } else if (active && ++lostReachTicks > 20) {
            reset();
        }
    }

    public boolean isActive() { return active; }

    public boolean shouldJump(double y, double velocityY) {
        // Anticipate inertia instead of alternating between sinking and full ascent.
        return active && y + velocityY * 3 < height + 0.02;
    }

    public void reset() {
        active = false;
        lostReachTicks = 0;
    }

    public static boolean needsAir(boolean mining, boolean refilling, int air, int maxAir) {
        // Once an escape starts, finish refilling before resuming the interrupted block.
        return air < maxAir && (refilling || !mining || air <= Math.max(1, maxAir * 3 / 10));
    }
}
