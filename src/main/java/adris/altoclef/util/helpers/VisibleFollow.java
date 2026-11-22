package adris.altoclef.util.helpers;

import net.minecraft.world.phys.Vec3;

/** Keeps only positions supplied by the current visual observation. Time advances while the owner runs. */
public final class VisibleFollow {
    private Vec3 lastSeen, destination;
    private int seenTick, plannedTick;
    private boolean moving;

    public boolean expired(int tick) { return tick - seenTick >= 200; }

    public Vec3 update(Vec3 feet, Vec3 visible, int tick) {
        if (visible != null) {
            lastSeen = visible;
            seenTick = tick;
        }
        if (lastSeen == null || expired(tick)) { moving = false; destination = null; return null; }
        double distance = feet.distanceToSqr(lastSeen);
        if (distance <= 9) moving = false;
        else if (distance > 25) moving = true;
        if (!moving) { destination = null; return null; }
        if (destination == null || tick - plannedTick >= 10 && destination.distanceToSqr(lastSeen) >= 4) {
            destination = lastSeen;
            plannedTick = tick;
        }
        return destination;
    }
}
