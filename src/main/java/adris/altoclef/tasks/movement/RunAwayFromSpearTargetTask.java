package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.GoalRunAwayFromEntities;
import adris.altoclef.util.helpers.SpearHelper;
import baritone.api.pathing.goals.Goal;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Sustained pathing fallback when facing backsteps cannot create enough space.
 */
public class RunAwayFromSpearTargetTask extends CustomBaritoneGoalTask {

    private final Entity _target;
    private final double _distanceToRun;

    public RunAwayFromSpearTargetTask(Entity target, double distance) {
        _target = target;
        _distanceToRun = distance;
    }

    @SuppressWarnings("RedundantIfStatement")
    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof RunAwayFromSpearTargetTask task) {
            if (!task._target.equals(_target)) return false;
            if (Math.abs(task._distanceToRun - _distanceToRun) > 1) return false;
            return true;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Retreat " + _distanceToRun + " blocks from spear target";
    }

    @Override
    protected Goal newGoal(AltoClef mod) {
        return new GoalRunAwayFromSpearTarget(mod, _target, _distanceToRun);
    }

    private static class GoalRunAwayFromSpearTarget extends GoalRunAwayFromEntities {

        private final Entity _target;
        private final AltoClef _mod;
        private final double _distance;

        public GoalRunAwayFromSpearTarget(AltoClef mod, Entity target, double distance) {
            super(mod, distance, false, 10);
            _mod = mod;
            _distance = distance;
            _target = target;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            if (_target == null || !_target.isAlive()) return true;
            var player = _mod.getPlayer();
            // Path nodes are block centers. Use actual position for the current node,
            // matching SpearHelper's continuous eye-to-body distance at task completion.
            Vec3 eye = player.blockPosition().getX() == x && player.blockPosition().getY() == y
                    && player.blockPosition().getZ() == z ? player.getEyePosition()
                    : new Vec3(x + 0.5, y + player.getEyeY() - player.getY(), z + 0.5);
            return SpearHelper.distanceToTarget(eye, _target.getBoundingBox()) >= _distance;
        }

        @Override
        protected Optional<Entity> getEntities(AltoClef mod) {
            // 目标死亡则视为已脱离，任务结束。
            if (_target == null || !_target.isAlive()) return Optional.empty();
            return Optional.of(_target);
        }
    }
}
