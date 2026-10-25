package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.GoalFollowEntity;
import adris.altoclef.util.helpers.MountedNavigation;
import adris.altoclef.util.helpers.BoatNavigation;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.utils.input.Input;
import net.minecraft.world.level.block.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;

public class GetToEntityTask extends Task implements ITaskRequiresGrounded {
    private final MovementProgressChecker stuckCheck = new MovementProgressChecker();
    private final MovementProgressChecker _progress = new MovementProgressChecker();
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(5);
    private final Entity _entity;
    private final double _closeEnoughDistance;
    private final GoalFollowEntity _mountedGoal;
    Block[] annoyingBlocks = new Block[]{
            Blocks.VINE,
            Blocks.NETHER_SPROUTS,
            Blocks.CAVE_VINES,
            Blocks.CAVE_VINES_PLANT,
            Blocks.TWISTING_VINES,
            Blocks.TWISTING_VINES_PLANT,
            Blocks.WEEPING_VINES_PLANT,
            Blocks.LADDER,
            Blocks.BIG_DRIPLEAF,
            Blocks.BIG_DRIPLEAF_STEM,
            Blocks.SMALL_DRIPLEAF,
            Blocks.TALL_GRASS,
            Blocks.GRASS_BLOCK,
            Blocks.SWEET_BERRY_BUSH
    };
    private Task _unstuckTask = null;

    public GetToEntityTask(Entity entity, double closeEnoughDistance) {
        _entity = entity;
        _closeEnoughDistance = closeEnoughDistance;
        _mountedGoal = new GoalFollowEntity(entity, closeEnoughDistance);
    }

    public GetToEntityTask(Entity entity) {
        this(entity, 1);
    }

    private static BlockPos[] generateSides(BlockPos pos) {
        return new BlockPos[]{
                pos.offset(1, 0, 0),
                pos.offset(-1, 0, 0),
                pos.offset(0, 0, 1),
                pos.offset(0, 0, -1),
                pos.offset(1, 0, -1),
                pos.offset(1, 0, 1),
                pos.offset(-1, 0, -1),
                pos.offset(-1, 0, 1)
        };
    }

    private boolean isAnnoying(AltoClef mod, BlockPos pos) {
        if (annoyingBlocks != null) {
            for (Block AnnoyingBlocks : annoyingBlocks) {
                return mod.level().getBlockState(pos).getBlock() == AnnoyingBlocks ||
                        mod.level().getBlockState(pos).getBlock() instanceof DoorBlock ||
                        mod.level().getBlockState(pos).getBlock() instanceof FenceBlock ||
                        mod.level().getBlockState(pos).getBlock() instanceof FenceGateBlock ||
                        mod.level().getBlockState(pos).getBlock() instanceof FlowerBlock;
            }
        }
        return false;
    }

    // This happens all the time in mineshafts and swamps/jungles
    private BlockPos stuckInBlock(AltoClef mod) {
        BlockPos p = mod.getPlayer().blockPosition();
        if (isAnnoying(mod, p)) return p;
        if (isAnnoying(mod, p.above())) return p.above();
        BlockPos[] toCheck = generateSides(p);
        for (BlockPos check : toCheck) {
            if (isAnnoying(mod, check)) {
                return check;
            }
        }
        BlockPos[] toCheckHigh = generateSides(p.above());
        for (BlockPos check : toCheckHigh) {
            if (isAnnoying(mod, check)) {
                return check;
            }
        }
        return null;
    }

    private Task getFenceUnstuckTask() {
        return new SafeRandomShimmyTask();
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        _progress.reset();
        stuckCheck.reset();
        _wanderTask.resetWander();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (!MountedNavigation.isSafetyFrame() && mod.getPlayer().getVehicle() instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat) {
            _progress.reset();
            stuckCheck.reset();
            if (!_entity.isAlive() || _entity.isRemoved() || BoatNavigation.getControlledBoat(mod.getPlayer()) == null
                    || thisOrChildSatisfies(task -> task != this && task.isActive() && !task.stopped())) {
                BoatNavigation.hold(mod, this);
            } else BoatNavigation.tick(mod, this, _mountedGoal);
            setDebugState(BoatNavigation.getDebugState(this));
            return null;
        }
        BoatNavigation.release(mod, this);
        if (!MountedNavigation.isSafetyFrame() && MountedNavigation.getControlledHorse(mod.getPlayer()) != null) {
            _progress.reset();
            stuckCheck.reset();
            if (!_entity.isAlive() || _entity.isRemoved()
                    || thisOrChildSatisfies(task -> task != this && task.isActive() && !task.stopped())) {
                MountedNavigation.hold(mod, this);
                setDebugState("Waiting safely before mounted approach.");
            } else {
                MountedNavigation.tick(mod, this, _mountedGoal);
                setDebugState(MountedNavigation.isBlocked(this)
                        ? "Mounted approach blocked; waiting safely." : "Riding to entity.");
            }
            return null;
        }
        MountedNavigation.release(mod, this);
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            _progress.reset();
        }
        if (WorldHelper.isInNetherPortal(mod)) {
            if (!mod.getClientBaritone().getPathingBehavior().isPathing()) {
                setDebugState("Getting out from nether portal");
                mod.getInputControls().hold(Input.SNEAK);
                mod.getInputControls().hold(Input.MOVE_FORWARD);
                return null;
            } else {
                mod.getInputControls().release(Input.SNEAK);
                mod.getInputControls().release(Input.MOVE_BACK);
                mod.getInputControls().release(Input.MOVE_FORWARD);
            }
        } else {
            if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
                mod.getInputControls().release(Input.SNEAK);
                mod.getInputControls().release(Input.MOVE_BACK);
                mod.getInputControls().release(Input.MOVE_FORWARD);
            }
        }
        if (_unstuckTask != null && _unstuckTask.isActive() && !_unstuckTask.isFinished(mod) && stuckInBlock(mod) != null) {
            setDebugState("Getting unstuck from block.");
            stuckCheck.reset();
            // Stop other tasks, we are JUST shimmying
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getExploreProcess().onLostControl();
            return _unstuckTask;
        }
        if (!_progress.check(mod) || !stuckCheck.check(mod)) {
            BlockPos blockStuck = stuckInBlock(mod);
            if (blockStuck != null) {
                _unstuckTask = getFenceUnstuckTask();
                return _unstuckTask;
            }
            stuckCheck.reset();
        }
        if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
            _progress.reset();
            setDebugState("Failed to get to target, wandering for a bit.");
            return _wanderTask;
        }

        if (!mod.getClientBaritone().getCustomGoalProcess().isActive()) {
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(new GoalFollowEntity(_entity, _closeEnoughDistance));
        }

        if (mod.getPlayer().closerThan(_entity, _closeEnoughDistance)) {
            _progress.reset();
        }

        if (!_progress.check(mod)) {
            return _wanderTask;
        }

        setDebugState("Going to entity");
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        BoatNavigation.release(mod, this);
        MountedNavigation.release(mod, this);
        mod.getClientBaritone().getPathingBehavior().forceCancel();
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof GetToEntityTask task) {
            return task._entity.equals(_entity) && Math.abs(task._closeEnoughDistance - _closeEnoughDistance) < 0.1;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Approach entity " + _entity.getType().getDescriptionId();
    }
}
