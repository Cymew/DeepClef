package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.GoalRunAwayFromEntities;
import baritone.api.pathing.goals.Goal;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;

import java.util.Optional;

/**
 * 远离被点燃的硫磺方块（吞下 TNT 后 isPrimed=true，即将爆炸）。
 * 仿照 RunAwayFromCreepersTask 实现。
 */
public class RunAwayFromSulfurCubesTask extends CustomBaritoneGoalTask {

    private final double _distanceToRun;

    public RunAwayFromSulfurCubesTask(double distance) {
        _distanceToRun = distance;
    }

    @SuppressWarnings("RedundantIfStatement")
    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof RunAwayFromSulfurCubesTask task) {
            if (Math.abs(task._distanceToRun - _distanceToRun) > 1) return false;
            return true;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Run " + _distanceToRun + " blocks away from primed sulfur cubes";
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        // We want to run away NOW
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        return new GoalRunAwayFromPrimedSulfurCubes(mod, _distanceToRun);
    }

    private static class GoalRunAwayFromPrimedSulfurCubes extends GoalRunAwayFromEntities {

        public GoalRunAwayFromPrimedSulfurCubes(AltoClef mod, double distance) {
            super(mod, distance, false, 10);
        }

        @Override
        protected Optional<Entity> getEntities(AltoClef mod) {
            // 只躲避"已点燃"的硫磺方块；普通硫磺方块是被动生物，不用躲。
            return mod.getEntityTracker().getNearestEntity(
                    entity -> entity instanceof SulfurCube sulfurCube && sulfurCube.isPrimed(),
                    SulfurCube.class);
        }
    }
}
