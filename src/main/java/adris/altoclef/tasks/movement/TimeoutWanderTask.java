package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasks.speedrun.MarvionBeatMinecraftTask;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.MountedNavigation;
import adris.altoclef.util.helpers.BoatNavigation;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.input.Input;
import net.minecraft.world.level.block.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Call this when the place you're currently at is bad for some reason and you just wanna get away.
 */
public class TimeoutWanderTask extends Task implements ITaskRequiresGrounded {
    private final MovementProgressChecker stuckCheck = new MovementProgressChecker();
    private final float _distanceToWander;
    private final MovementProgressChecker _progressChecker = new MovementProgressChecker();
    private final boolean _increaseRange;
    private final TimerGame _timer = new TimerGame(60);
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
    private Vec3 _origin;
    //private DistanceProgressChecker _distanceProgressChecker = new DistanceProgressChecker(10, 0.1f);
    private boolean _forceExplore;
    private Task _unstuckTask = null;
    private int _failCounter;
    private double _wanderDistanceExtension;
    private static final double[] MOUNTED_HEADINGS = {0, 60, -60, 180};
    private Goal _boatWanderGoal;
    private int _boatHeading, _boatBlockedTicks;
    private boolean _boatWanderFinished;
    private static final int MOUNTED_BLOCKED_WAIT = 20;
    private Entity _mountedHorse;
    private Vec3 _mountedOrigin;
    private Vec3 _mountedForward;
    private Vec3 _mountedProgressPosition;
    private Goal _mountedGoal;
    private int _mountedHeadingIndex;
    private int _mountedProgressTick;
    private int _mountedStartedTick;
    private int _mountedBlockedTick = -1;
    private int _mountedRetryTick;
    private boolean _mountedExhausted;
    private boolean _mountedFinishedBlocked;

    public TimeoutWanderTask(float distanceToWander, boolean increaseRange) {
        _distanceToWander = distanceToWander;
        _increaseRange = increaseRange;
        _forceExplore = false;
    }

    public TimeoutWanderTask(float distanceToWander) {
        this(distanceToWander, false);
    }

    public TimeoutWanderTask() {
        this(Float.POSITIVE_INFINITY, false);
    }

    public TimeoutWanderTask(boolean forceExplore) {
        this();
        _forceExplore = forceExplore;
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
        for (Block AnnoyingBlocks : annoyingBlocks) {
            return mod.level().getBlockState(pos).getBlock() == AnnoyingBlocks ||
                    mod.level().getBlockState(pos).getBlock() instanceof DoorBlock ||
                    mod.level().getBlockState(pos).getBlock() instanceof FenceBlock ||
                    mod.level().getBlockState(pos).getBlock() instanceof FenceGateBlock ||
                    mod.level().getBlockState(pos).getBlock() instanceof FlowerBlock;
        }
        return false;
    }

    public void resetWander() {
        _wanderDistanceExtension = 0;
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
        _timer.reset();
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        _origin = mod.getPlayer().position();
        _progressChecker.reset();
        stuckCheck.reset();
        _failCounter = 0;
        _mountedHorse = null;
        _mountedOrigin = null;
        _mountedGoal = null;
        _mountedFinishedBlocked = false;
        _boatWanderGoal = null;
        _boatHeading = _boatBlockedTicks = 0;
        _boatWanderFinished = false;
        if (BoatNavigation.getControlledBoat(mod.getPlayer()) != null) return;
        Entity horse = MountedNavigation.getControlledHorse(mod.getPlayer());
        if (horse != null) {
            beginMountedWander(mod, horse);
            return;
        }
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
        if (!cursorStack.isEmpty()) {
            Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
            moveTo.ifPresent(slot -> mod.getSlotHandler().handleInventoryMouseClick(slot, 0, ContainerInput.PICKUP));
            if (ItemHelper.canThrowAwayStack(mod, cursorStack)) {
                mod.getSlotHandler().handleInventoryMouseClick(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
            }
            Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
            // Try throwing away cursor slot if it's garbage
            garbage.ifPresent(slot -> mod.getSlotHandler().handleInventoryMouseClick(slot, 0, ContainerInput.PICKUP));
            mod.getSlotHandler().handleInventoryMouseClick(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
        } else {
            StorageHelper.closeScreen();
        }
    }

    @Override
    protected Task onTick(AltoClef mod) {
        var boat = BoatNavigation.getControlledBoat(mod.getPlayer());
        if (boat != null) {
            if (_boatWanderFinished) { BoatNavigation.hold(mod, this); return null; }
            if (_boatWanderGoal == null) {
                double distance = Float.isInfinite(_distanceToWander) ? 16 : Math.max(3, _distanceToWander + _wanderDistanceExtension);
                _boatWanderGoal = GoalXZ.fromDirection(_origin, boat.getYRot() + (float)MOUNTED_HEADINGS[_boatHeading], distance);
            }
            BoatNavigation.tick(mod, this, _boatWanderGoal);
            if (BoatNavigation.isGoalReached(mod, _boatWanderGoal)) {
                if (Float.isInfinite(_distanceToWander)) { _origin = boat.position(); _boatWanderGoal = null; }
                else _boatWanderFinished = true;
            } else if (BoatNavigation.isBlocked(this)) {
                if (++_boatBlockedTicks >= 60) {
                    _boatBlockedTicks = 0;
                    if (++_boatHeading >= MOUNTED_HEADINGS.length) _boatWanderFinished = true;
                    else _boatWanderGoal = null;
                }
            } else _boatBlockedTicks = 0;
            setDebugState(BoatNavigation.getDebugState(this));
            return null;
        }
        BoatNavigation.release(mod, this);
        Entity horse = MountedNavigation.getControlledHorse(mod.getPlayer());
        if (horse != null) return tickMountedWander(mod, horse);
        MountedNavigation.release(mod, this);
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            _progressChecker.reset();
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
        if (!_progressChecker.check(mod) || !stuckCheck.check(mod)) {
            Optional<Entity> mob = mod.getEntityTracker().getNearestEntity(Mob.class);
            if (mob.isPresent() && mob.get().position().closerThan(mod.getPlayer().position(), 1)) {
                setDebugState("Killing annoying entity.");
                Predicate<Entity> valid = entity -> entity == mob.get();
                return new KillEntitiesTask(valid, mob.get().getClass());
            }
            BlockPos blockStuck = stuckInBlock(mod);
            if (blockStuck != null) {
                _failCounter++;
                _unstuckTask = getFenceUnstuckTask();
                return _unstuckTask;
            }
            stuckCheck.reset();
        }
        setDebugState("Exploring.");
        switch (WorldHelper.getCurrentDimension()) {
            case END -> {
                if (_timer.getDuration() >= 30) {
                    if (MarvionBeatMinecraftTask.getConfig().renderDistanceManipulation) {
                        Minecraft.getInstance().options.renderDistance().set(12);
                        Minecraft.getInstance().options.entityDistanceScaling().set(1.0);
                    }
                    _timer.reset();
                }
            }
            case OVERWORLD, NETHER -> {
                if (_timer.getDuration() >= 30) {
                    if (MarvionBeatMinecraftTask.getConfig().renderDistanceManipulation) {
                        Minecraft.getInstance().options.renderDistance().set(12);
                        Minecraft.getInstance().options.entityDistanceScaling().set(1.0);
                    }
                }
                if (_timer.elapsed()) {
                    if (MarvionBeatMinecraftTask.getConfig().renderDistanceManipulation) {
                        Minecraft.getInstance().options.renderDistance().set(32);
                        Minecraft.getInstance().options.entityDistanceScaling().set(5.0);
                    }
                    _timer.reset();
                }
            }
        }
        if (!mod.getClientBaritone().getExploreProcess().isActive()) {
            mod.getClientBaritone().getExploreProcess().explore((int) _origin.x, (int) _origin.z);
        }
        if (!_progressChecker.check(mod)) {
            _progressChecker.reset();
            if (!_forceExplore) {
                _failCounter++;
                Debug.logMessage("Failed exploring.");
            }
        }
        return null;
    }

    private void beginMountedWander(AltoClef mod, Entity horse) {
        _mountedHorse = horse;
        _mountedOrigin = horse.position();
        _mountedForward = Vec3.directionFromRotation(0, mod.getPlayer().getYRot());
        _mountedProgressPosition = horse.position();
        _mountedProgressTick = mod.getPlayer().tickCount;
        _mountedStartedTick = _mountedProgressTick;
        _mountedHeadingIndex = 0;
        _mountedGoal = null;
        _mountedBlockedTick = -1;
        _mountedRetryTick = 0;
        _mountedExhausted = false;
        _mountedFinishedBlocked = false;
    }

    private Task tickMountedWander(AltoClef mod, Entity horse) {
        if (_mountedHorse != horse) beginMountedWander(mod, horse);
        _progressChecker.reset();
        stuckCheck.reset();
        if (thisOrChildSatisfies(task -> task != this && task.isActive() && !task.stopped())) {
            // Task.tick stops an old walking child after this return. Its cleanup
            // must finish before the mounted controller starts moving.
            MountedNavigation.hold(mod, this);
            setDebugState("Stopping walking recovery before mounted exploration.");
            return null;
        }
        int tick = mod.getPlayer().tickCount;
        if (isFinished(mod)) {
            MountedNavigation.hold(mod, this);
            return null;
        }
        if (!Float.isInfinite(_distanceToWander)
                && tick - _mountedStartedTick > Math.max(320, (_distanceToWander + _wanderDistanceExtension) * 40)) {
            // A bounded pen can contain reachable segments without ever allowing
            // the requested distance from the origin. Do not loop those forever.
            _mountedFinishedBlocked = true;
            MountedNavigation.hold(mod, this);
            setDebugState("Mounted exploration could not reach the requested radius in time.");
            return null;
        }
        if (_mountedExhausted) {
            MountedNavigation.hold(mod, this);
            setDebugState("Mounted exploration blocked in every candidate direction; waiting.");
            if (!Float.isInfinite(_distanceToWander) && tick >= _mountedRetryTick) {
                // Give a finite recovery task back to its parent after bounded attempts.
                _mountedFinishedBlocked = true;
            } else if (Float.isInfinite(_distanceToWander) && tick >= _mountedRetryTick) {
                // Dynamic obstructions may clear. Retry the same fixed directions
                // after a quiet interval rather than turning continuously.
                _mountedExhausted = false;
                _mountedHeadingIndex = 0;
                _mountedGoal = null;
                _mountedBlockedTick = -1;
                _mountedProgressPosition = horse.position();
                _mountedProgressTick = tick;
            }
            return null;
        }
        if (_mountedGoal != null && MountedNavigation.isGoalReached(mod, _mountedGoal)) {
            _mountedGoal = null;
            _mountedHeadingIndex = 0;
            _mountedBlockedTick = -1;
        }
        if (_mountedGoal == null) {
            double remaining = Float.isInfinite(_distanceToWander) ? 10
                    : Math.max(0, _distanceToWander + _wanderDistanceExtension
                    - horse.position().subtract(_mountedOrigin).horizontalDistance());
            double length = Float.isInfinite(_distanceToWander) ? 10 : Math.min(12, Math.max(2, remaining + 1.5));
            Vec3 heading = _mountedForward.yRot((float)Math.toRadians(MOUNTED_HEADINGS[_mountedHeadingIndex]));
            Vec3 destination = horse.position().add(heading.scale(length));
            BlockPos target = BlockPos.containing(destination);
            _mountedGoal = new GoalXZ(target.getX(), target.getZ());
            _mountedProgressPosition = horse.position();
            _mountedProgressTick = tick;
        }
        MountedNavigation.tick(mod, this, _mountedGoal);
        if (horse.position().subtract(_mountedProgressPosition).horizontalDistanceSqr() > 0.16) {
            _mountedProgressPosition = horse.position();
            _mountedProgressTick = tick;
        }
        boolean blocked = MountedNavigation.isBlocked(this) || tick - _mountedProgressTick > 45;
        if (!blocked) {
            _mountedBlockedTick = -1;
            setDebugState("Exploring along a stable mounted route.");
            return null;
        }
        if (_mountedBlockedTick < 0) _mountedBlockedTick = tick;
        if (tick - _mountedBlockedTick >= MOUNTED_BLOCKED_WAIT) {
            _mountedGoal = null;
            _mountedBlockedTick = -1;
            _mountedHeadingIndex++;
            if (_mountedHeadingIndex >= MOUNTED_HEADINGS.length) {
                _mountedExhausted = true;
                _mountedRetryTick = tick + (Float.isInfinite(_distanceToWander) ? 100 : MOUNTED_BLOCKED_WAIT);
            }
            MountedNavigation.hold(mod, this);
        }
        setDebugState("Mounted exploration route blocked; checking fixed alternatives.");
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        BoatNavigation.release(mod, this);
        MountedNavigation.release(mod, this);
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        if (isFinished(mod) && !_mountedFinishedBlocked) {
            if (_increaseRange) {
                _wanderDistanceExtension += _distanceToWander;
                Debug.logMessage("Increased wander range");
            }
        }
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        if (BoatNavigation.getControlledBoat(mod.getPlayer()) != null) return _boatWanderFinished;
        // Why the heck did I add this in?
        //if (_origin == null) return true;

        if (Float.isInfinite(_distanceToWander)) return false;

        Entity horse = MountedNavigation.getControlledHorse(mod.getPlayer());
        if (horse != null) {
            if (_mountedFinishedBlocked && _mountedHorse == horse) return true;
            if (_mountedOrigin == null || _mountedHorse != horse) return false;
            double toWander = Math.max(0, _distanceToWander + _wanderDistanceExtension);
            return horse.position().subtract(_mountedOrigin).horizontalDistanceSqr() >= toWander * toWander;
        }

        // If we fail 10 times or more, we may as well try the previous task again.
        if (_failCounter > 10) {
            return true;
        }

        if (mod.getPlayer() != null && mod.getPlayer().position() != null && (mod.getPlayer().onGround() ||
                mod.getPlayer().isInWater())) {
            double sqDist = mod.getPlayer().position().distanceToSqr(_origin);
            double toWander = _distanceToWander + _wanderDistanceExtension;
            return sqDist > toWander * toWander;
        } else {
            return false;
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof TimeoutWanderTask task) {
            if (Float.isInfinite(task._distanceToWander) || Float.isInfinite(_distanceToWander)) {
                return Float.isInfinite(task._distanceToWander) == Float.isInfinite(_distanceToWander);
            }
            return Math.abs(task._distanceToWander - _distanceToWander) < 0.5f;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Wander for " + (_distanceToWander + _wanderDistanceExtension) + " blocks";
    }
}
