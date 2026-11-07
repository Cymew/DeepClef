package adris.altoclef.tasks.resources;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Walking around a target or restarting the same swing does not renew the attempt. */
public final class DirtProgress {
    private BlockPos target;
    private double bestDistance, bestDamage;
    private int idleTicks;

    public boolean stalled(BlockPos block, Vec3 position, double damage) {
        double distance = position.distanceTo(Vec3.atCenterOf(block));
        if (!block.equals(target)) {
            target = block;
            bestDistance = distance;
            bestDamage = damage;
            idleTicks = 0;
        } else if (distance < bestDistance - .5 || damage > bestDamage + .001) {
            bestDistance = Math.min(bestDistance, distance);
            bestDamage = Math.max(bestDamage, damage);
            idleTicks = 0;
        } else {
            idleTicks++;
        }
        return idleTicks >= 160;
    }

    public void reset() { target = null; }
}
