package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.chains.UserTaskChain;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.SceneObservation;
import adris.altoclef.util.helpers.VisibleFollow;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public class FollowPlayerTask extends Task {
    private final String playerName;
    private VisibleFollow observation;
    private Object world;
    private Object connection;
    private int ticks;
    private FollowLeg leg;
    private boolean finished;
    private String result = "跟随已停止。";
    public String result() { return result; }

    public FollowPlayerTask(String playerName) { this.playerName = playerName; }

    @Override protected void onStart(AltoClef mod) {
        observation = new VisibleFollow();
        world = mod.level();
        connection = mod.getPlayer().connection;
        ticks = 0;
        finished = false;
        result = "跟随已停止。";
        leg = null;
        mod.getBehaviour().push();
        mod.getBehaviour().avoidBlockBreaking(p -> true);
        mod.getBehaviour().avoidBlockPlacing(p -> true);
    }

    @Override protected Task onTick(AltoClef mod) {
        if (world != mod.level() || connection != mod.getPlayer().connection) {
            finished = true;
            result = "跟随结束：世界或连接已变化。";
            return null;
        }
        ticks++;
        Vec3 seen = null;
        for (var candidate : mod.level().players()) {
            if (candidate == mod.getPlayer() || !candidate.getName().getString().equals(playerName)
                    || !candidate.isAlive() || candidate.isSpectator() || candidate.isInvisibleTo(mod.getPlayer())) continue;
            if (SceneObservation.visibleEntityPoint(mod, candidate.getEyePosition())
                    || SceneObservation.visibleEntityPoint(mod, candidate.getBoundingBox().getCenter())) {
                seen = candidate.position();
                break;
            }
        }
        Vec3 target = observation.update(mod.getPlayer().position(), seen, ticks);
        if (observation.expired(ticks)) {
            finished = true;
            result = "跟随结束：10 秒内未重新看见 " + playerName + "。";
            mod.logWarning(result);
            return null;
        }
        setDebugState(seen != null ? "跟随可见目标，保持距离。" : "目标未目击，仅保留最后可见位置。");
        if (target == null) { leg = null; return null; }
        BlockPos point = BlockPos.containing(target);
        // An XZ leg must never dig vertically to a target on another floor.
        if (mod.getPlayer().blockPosition().getX() == point.getX()
                && mod.getPlayer().blockPosition().getZ() == point.getZ()) {
            setDebugState("已到最后目击点的水平位置，等待可见的后续路线。");
            leg = null;
            return null;
        }
        if (leg == null || leg.x != point.getX() || leg.z != point.getZ()) leg = new FollowLeg(point.getX(), point.getZ());
        return leg;
    }

    @Override public boolean isFinished(AltoClef mod) { return finished; }
    @Override protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
        observation = null;
        leg = null;
    }
    @Override protected boolean isEqual(Task other) {
        return other instanceof FollowPlayerTask task && task.playerName.equals(playerName);
    }
    @Override protected String toDebugString() { return "跟随 " + playerName; }

    /** A single owned XZ leg reuses ordinary shoreline, swimming and path-change handling. */
    private final class FollowLeg extends CustomBaritoneGoalTask {
        private final int x, z;
        FollowLeg(int x, int z) { super(false); this.x = x; this.z = z; }
        @Override protected Goal newGoal(AltoClef mod) { return new GoalXZ(x, z); }
        @Override protected boolean allowsSwimming() { return true; }
        @Override protected boolean ownsSwimming(AltoClef mod) {
            return leg == this && FollowPlayerTask.this.isActive() && !FollowPlayerTask.this.stopped()
                    && mod.getTaskRunner().getCurrentTaskChain() instanceof UserTaskChain chain
                    && chain.getCurrentTask() == FollowPlayerTask.this;
        }
        @Override protected boolean isEqual(Task other) { return other == this; }
        @Override protected String toDebugString() { return "跟随最后目击位置 (" + x + ", " + z + ")"; }
    }
}
