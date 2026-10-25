package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.BowCombat;
import net.minecraft.world.entity.Entity;

/** Fires one fully drawn, collision-checked arrow; supports the same controller while riding. */
public class ShootArrowSimpleProjectileTask extends Task {
    private final Entity target;
    private boolean finished;
    private int startedTick;

    public ShootArrowSimpleProjectileTask(Entity target) {
        this.target = target;
    }

    @Override
    protected void onStart(AltoClef mod) {
        BowCombat.reset(mod);
        finished = false;
        startedTick = mod.getPlayer().tickCount;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (!BowCombat.canTarget(target) || !BowCombat.canUse(mod)) {
            Debug.logMessage("Bow shot stopped: target or bow ammunition is unavailable.");
            BowCombat.reset(mod);
            finished = true;
            return null;
        }
        if (mod.getPlayer().tickCount - startedTick > 240) {
            Debug.logMessage("Bow shot stopped: no safe firing position was reached.");
            BowCombat.reset(mod);
            finished = true;
            return null;
        }
        setDebugState(BowCombat.isDrawing() ? "Drawing bow and tracking target" : "Finding a safe firing position");
        finished = BowCombat.tick(mod, target);
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        BowCombat.reset(mod);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return finished || target == null || !target.isAlive() || target.isRemoved();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof ShootArrowSimpleProjectileTask task && task.target == target;
    }

    @Override
    protected String toDebugString() {
        return "Shooting arrow at " + (target == null ? "missing target" : target.getType().getDescriptionId());
    }
}
