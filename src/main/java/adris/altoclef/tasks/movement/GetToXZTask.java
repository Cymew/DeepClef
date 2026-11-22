package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import net.minecraft.core.BlockPos;

public class GetToXZTask extends CustomBaritoneGoalTask {

    private final int _x, _z;
    private final Dimension _dimension;
    public enum Gait {
        AUTO, SNEAK, SPRINT;
        public boolean allowsAutomaticTravel() { return this == AUTO; }
    }
    private final Gait gait;
    private int movingTicks, matchedTicks;
    private double startX, startZ;

    public Gait gait() { return gait; }

    public static GetToXZTask styledOwner(AltoClef mod) {
        if (mod == null || mod.getPlayer() == null || mod.getTaskRunner() == null
                || mod.getUserTaskChain() == null || mod.getTaskRunner().getCurrentTaskChain() != mod.getUserTaskChain()
                || !(mod.getUserTaskChain().getCurrentTask() instanceof GetToXZTask walk)
                || walk.gait == Gait.AUTO || !walk.isActive() || mod.getPlayer().isPassenger()
                || mod.getPlayer().isInWater() || mod.getPlayer().getAbilities().flying
                || mod.getPlayer().isFallFlying() || net.minecraft.client.Minecraft.getInstance().gui.screen() != null) return null;
        return walk;
    }

    public static net.minecraft.world.entity.player.Input withGait(net.minecraft.world.entity.player.Input keys, Gait gait) {
        if (gait == Gait.AUTO) return keys;
        boolean sneak = gait == Gait.SNEAK || keys.shift(); // preserve edge protection
        return new net.minecraft.world.entity.player.Input(keys.forward(), keys.backward(), keys.left(), keys.right(),
                keys.jump(), sneak, !sneak && keys.forward() && gait == Gait.SPRINT);
    }

    public void beginFrame(net.minecraft.client.player.LocalPlayer p) { startX = p.getX(); startZ = p.getZ(); }
    public void endFrame(net.minecraft.client.player.LocalPlayer p) {
        if (Math.hypot(p.getX()-startX, p.getZ()-startZ) <= 0.001) return;
        movingTicks++;
        if (gait == Gait.SNEAK ? p.isCrouching() : p.isSprinting()) matchedTicks++;
    }

    public String report(BlockPos actual) {
        String position = arrivalReport(_x, _z, actual);
        if (gait == Gait.AUTO) return position;
        return position + "。陆地移动期间实际" + (gait == Gait.SNEAK ? "潜行" : "疾跑")
                + " " + matchedTicks + "/" + movingTicks + " tick。"
                + (movingTicks == 0 ? "未观测到指定姿态移动" : matchedTicks == movingTicks ? "已全程保持" : "未全程保持");
    }

    public GetToXZTask(int x, int z) {
        this(x, z, null);
    }

    public GetToXZTask(int x, int z, Dimension dimension) {
        this(x, z, dimension, Gait.AUTO);
    }

    public GetToXZTask(int x, int z, Dimension dimension, Gait gait) {
        this.gait = gait;
        _x = x;
        _z = z;
        _dimension = dimension;
    }

    public String targetDescription() { return "目标 XZ (" + _x + ", " + _z + ")"; }
    public boolean verifiedAt(AltoClef mod) {
        BlockPos actual = mod.getPlayer().blockPosition();
        return actual.getX()==_x && actual.getZ()==_z && (_dimension==null || _dimension==WorldHelper.getCurrentDimension());
    }

    public static String arrivalReport(int x, int z, BlockPos actual) {
        return (actual.getX() == x && actual.getZ() == z ? "已到达" : "未到达")
                + "目标 XZ (" + x + ", " + z + ")，实际位置 " + actual.toShortString();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_dimension != null && WorldHelper.getCurrentDimension() != _dimension) {
            return new DefaultGoToDimensionTask(_dimension);
        }
        return super.onTick(mod);
    }

    @Override
    protected boolean allowsMountedWalkingFallback() {
        return true;
    }

    @Override
    protected boolean allowsAutomaticBoat() { return gait.allowsAutomaticTravel(); }

    @Override
    protected void onStop(AltoClef mod, Task replacement) {
        super.onStop(mod, replacement);
        if (gait == Gait.SPRINT && mod.getPlayer() != null) mod.getPlayer().setSprinting(false);
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        return new GoalXZ(_x, _z);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof GetToXZTask task) {
            return task._x == _x && task._z == _z && task._dimension == _dimension && task.gait == gait;
        }
        return false;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return super.isFinished(mod)
                && (_dimension == null || _dimension == WorldHelper.getCurrentDimension());
    }

    @Override
    protected String toDebugString() {
        return "Getting to (" + _x + "," + _z + ")" + (_dimension != null ? " in dimension " + _dimension : "");
    }
}
