package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.entity.KillEntityTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.Items;

/** Obtains bottles through a real, non-raid patrol leader kill, with normal resource pickup first. */
public final class OminousBottleAcquisitionTask extends ResourceTask {
    private final int count;
    private final Task explore = new TimeoutWanderTask(64, true);
    public OminousBottleAcquisitionTask(int count) { super(Items.OMINOUS_BOTTLE, count); this.count = count; }
    @Override protected void onResourceStart(AltoClef mod) { mod.getBehaviour().addProtectedItems(Items.OMINOUS_BOTTLE); }
    @Override protected Task onResourceTick(AltoClef mod) {
        if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
        Entity nearest = null;
        for (Entity entity : mod.level().entitiesForRendering()) {
            if (!(entity instanceof Raider raider) || !raider.isAlive() || !raider.isPatrolLeader() || raider.hasActiveRaid()) continue;
            if (nearest == null || entity.distanceToSqr(mod.getPlayer()) < nearest.distanceToSqr(mod.getPlayer())) nearest = entity;
        }
        if (nearest != null) { setDebugState("Defeating a non-raid captain for its native ominous-bottle drop."); return new KillEntityTask(nearest); }
        setDebugState("Exploring for a patrol/outpost captain; raid participants cannot supply bottles.");
        return explore;
    }
    @Override protected void onResourceStop(AltoClef mod, Task interruptTask) { }
    @Override protected boolean isEqualResource(ResourceTask other) { return other instanceof OminousBottleAcquisitionTask; }
    @Override protected boolean shouldAvoidPickingUp(AltoClef mod) { return false; }
    @Override protected String toDebugStringName() { return "Get ominous bottle x" + count; }
}
