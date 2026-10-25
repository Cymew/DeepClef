package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;

/** A coordinate in air resolves to the nearest standable height while riding. */
public final class MountedBlockGoal extends GoalBlock {
    private final AltoClef mod;
    private final BlockPos requested;
    private final GoalBlock walking;
    private GoalBlock riding;
    private GoalBlock walkingFallback;
    private int lastResolve = Integer.MIN_VALUE / 2;
    private LocalPlayer player;
    private Entity horse;
    private Object level;

    public MountedBlockGoal(AltoClef mod, BlockPos requested) {
        super(requested);
        this.mod = mod;
        this.requested = requested.immutable();
        walking = new GoalBlock(requested);
        riding = walking;
    }

    public GoalBlock resolved() {
        Entity mount = MountedNavigation.getControlledHorse(mod.getPlayer());
        if (player != mod.getPlayer() || horse != mount || level != mod.level()) {
            player = mod.getPlayer();
            horse = mount;
            level = mod.level();
            lastResolve = Integer.MIN_VALUE / 2;
        }
        if (mount == null) return walkingFallback == null ? walking : walkingFallback;
        int tick = mod.getPlayer().tickCount;
        if (tick - lastResolve >= 20) {
            lastResolve = tick;
            BlockPos stand = MountedNavigation.findStandNear(mod, requested, 12);
            riding = stand == null ? walking : new GoalBlock(stand);
        }
        return riding;
    }

    /** Keep the accepted ground destination when automatic dismount continues the task. */
    public void continueOnFoot() {
        if (walkingFallback == null) walkingFallback = resolved();
    }

    @Override public boolean isInGoal(int x, int y, int z) { return resolved().isInGoal(x, y, z); }
    @Override public double heuristic(int x, int y, int z) { return resolved().heuristic(x, y, z); }
    @Override public BlockPos getGoalPos() { return resolved().getGoalPos(); }
    @Override public String toString() { return "Mounted block goal " + requested; }
}
