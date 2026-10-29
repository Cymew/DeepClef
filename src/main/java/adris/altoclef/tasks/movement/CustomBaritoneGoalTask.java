package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.chains.UserTaskChain;
import adris.altoclef.tasksystem.ITaskRequiresGrounded;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.AutoBoatPolicy;
import adris.altoclef.util.helpers.ElytraEquipmentHelper;
import adris.altoclef.util.helpers.MountedNavigation;
import adris.altoclef.util.helpers.BoatNavigation;
import adris.altoclef.util.helpers.MountedBlockGoal;
import adris.altoclef.util.helpers.MountedTravelProgress;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.input.Input;
import net.minecraft.world.level.block.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Turns a baritone goal into a task.
 */
public abstract class CustomBaritoneGoalTask extends Task implements ITaskRequiresGrounded {
    private final Task _wanderTask = new TimeoutWanderTask(5, true);
    private final MovementProgressChecker stuckCheck = new MovementProgressChecker();
    private final boolean _wander;
    protected MovementProgressChecker _checker = new MovementProgressChecker();
    protected Goal _cachedGoal = null;
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
    private final MountedTravelProgress _mountedProgress = new MountedTravelProgress();
    private boolean _continueOnFoot;
    private boolean _footConfirmed;
    private int _footConfirmationTick;
    private Object _mountedLevel;
    private UUID _mountedHorseId;
    private Goal _mountedProgressGoal;
    private BlockPos _mountedGoalPosition;
    private final MountedTravelProgress _boatProgress = new MountedTravelProgress();
    private boolean _boatContinueOnFoot, _boatFootConfirmed;
    private int _boatFootTick;
    private Object _boatLevel;
    private UUID _boatId;
    private Goal _boatProgressGoal;
    private AutoBoatBoardTask _automaticBoatTask;
    private BoatRecoveryTask _boatRecoveryTask;
    private UUID _boatRecoveryId;
    private boolean _automaticBoatCleanupPending, _boatRecoveryCleanupPending;
    private int _automaticBoatStartTick, _boatRecoveryStartTick;
    private int _automaticBoatLastProbe = Integer.MIN_VALUE / 2, _automaticBoatRetryAfter;
    private Object _automaticBoatWorld;
    private Vec3 _automaticBoatBlockedOrigin;
    private final BoatExitMemory _automaticBoatExit = new BoatExitMemory();
    private ElytraFlightTask _automaticElytraTask;
    private boolean _automaticElytraCleanupPending, _automaticElytraCompleted;
    private int _automaticElytraStartTick, _automaticElytraLastProbe = Integer.MIN_VALUE / 2, _automaticElytraRetryAfter;
    private Object _automaticElytraWorld;
    private Vec3 _automaticElytraBlockedOrigin;
    private adris.altoclef.util.helpers.SwimNavigation _swimming;
    private int _swimRetryAfter;
    public boolean hasSwimController() { return _swimming != null; }

    // This happens all the time in mineshafts and swamps/jungles

    public CustomBaritoneGoalTask(boolean wander) {
        _wander = wander;
    }

    public CustomBaritoneGoalTask() {
        this(true);
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
        _checker.reset();
        stuckCheck.reset();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // Flight owns the full trip through observed landing. Do not classify its absence
        // from a boat as a dismount, nor let a walking child act during the handoff.
        if (_automaticElytraTask != null) {
            _checker.reset(); stuckCheck.reset();
            if (mod.getPlayer().tickCount == _automaticElytraStartTick) return null;
            if (!_automaticElytraTask.isFinished(mod)) return _automaticElytraTask;
            setDebugState(_automaticElytraTask.getResult());
            if (_automaticElytraTask.succeeded()) _automaticElytraCompleted = true;
            else {
                _automaticElytraRetryAfter = mod.getPlayer().tickCount + 200;
                _automaticElytraBlockedOrigin = mod.getPlayer().position();
            }
            _automaticElytraTask = null;
            _automaticElytraCleanupPending = true;
            return null;
        }
        if (_automaticElytraCleanupPending) {
            _checker.reset(); stuckCheck.reset();
            if (mod.getPlayer().isFallFlying() || !mod.getPlayer().onGround()
                    && !mod.getPlayer().isInWater() && !mod.getPlayer().onClimbable() && !mod.getPlayer().isPassenger()) {
                setDebugState("Waiting for flight landing before returning to the original walking goal.");
                return null;
            }
            _automaticElytraCleanupPending = false;
            return null;
        }
        observeOrdinaryBoatExit(mod);
        // The parent stays pending while its child boards or performs optional cleanup.
        // Returning null on each handoff lets Task stop the previous child before a new owner acts.
        if (_automaticBoatTask != null) {
            _checker.reset(); stuckCheck.reset();
            if (mod.getPlayer().tickCount == _automaticBoatStartTick) return null;
            if (!_automaticBoatTask.isFinished(mod)) return _automaticBoatTask;
            setDebugState(_automaticBoatTask.getResult());
            if (!_automaticBoatTask.succeeded()) blockAutomaticBoatRetry(mod);
            _automaticBoatTask = null;
            _automaticBoatCleanupPending = true;
            return null;
        }
        if (_automaticBoatCleanupPending) {
            _automaticBoatCleanupPending = false;
            return null;
        }
        if (_boatRecoveryTask != null) {
            _checker.reset(); stuckCheck.reset();
            if (mod.getPlayer().tickCount == _boatRecoveryStartTick) return null;
            if (!_boatRecoveryTask.isFinished(mod)) return _boatRecoveryTask;
            setDebugState(_boatRecoveryTask.getResult());
            _boatRecoveryTask = null;
            _boatRecoveryCleanupPending = true;
            return null;
        }
        if (_boatRecoveryCleanupPending) {
            _boatRecoveryCleanupPending = false;
            return null;
        }
        if (_continueOnFoot && !MountedNavigation.isSafetyFrame()) {
            if (mod.getPlayer().isPassenger()) {
                _footConfirmed = false;
                if (mod.getPlayer().getVehicle() instanceof AbstractBoat) {
                    BoatNavigation.requestDismount(mod, this);
                    setDebugState(BoatNavigation.getDebugState(this));
                } else {
                    MountedNavigation.requestDismount(mod, this);
                    setDebugState(MountedNavigation.getDebugState(this));
                }
                return null;
            }
            MountedNavigation.release(mod, this);
            if (!_footConfirmed) {
                _footConfirmed = true;
                _footConfirmationTick = mod.getPlayer().tickCount;
                mod.getClientBaritone().getCustomGoalProcess().onLostControl();
                mod.getClientBaritone().getPathingBehavior().forceCancel();
                _checker.reset();
                stuckCheck.reset();
                setDebugState("Dismounted; preparing the walking route.");
                return null;
            }
            if (mod.getPlayer().tickCount - _footConfirmationTick <= 0 || !mod.getPlayer().onGround()) {
                setDebugState("Waiting for footing after dismount.");
                return null;
            }
        }
        if (_boatContinueOnFoot && !prepareBoatWalking(mod)) return null;
        if (_boatContinueOnFoot && _boatRecoveryId != null) {
            _boatRecoveryTask = new BoatRecoveryTask(_boatRecoveryId);
            _boatRecoveryId = null;
            _boatRecoveryStartTick = mod.getPlayer().tickCount;
            setDebugState("Preparing optional recovery of the boat just dismounted.");
            return null;
        }
        if (!MountedNavigation.isSafetyFrame() && mod.getPlayer().getVehicle() instanceof AbstractBoat) {
            if (_cachedGoal == null) _cachedGoal = newGoal(mod);
            _checker.reset();
            stuckCheck.reset();
            if (thisOrChildSatisfies(task -> task != this && task.isActive() && !task.stopped())) {
                BoatNavigation.hold(mod, this);
                setDebugState("Stopping walking recovery before sailing.");
            } else {
                if (BoatNavigation.getControlledBoat(mod.getPlayer()) != null) {
                    BoatNavigation.tick(mod, this, _cachedGoal);
                } else {
                    BoatNavigation.hold(mod, this);
                }
                updateBoatProgress(mod);
                setDebugState(BoatNavigation.getDebugState(this));
            }
            return null;
        }
        BoatNavigation.release(mod, this);
        if (!MountedNavigation.isSafetyFrame() && MountedNavigation.getControlledHorse(mod.getPlayer()) != null) {
            if (_cachedGoal == null) _cachedGoal = newGoal(mod);
            _checker.reset();
            stuckCheck.reset();
            // Returning null stops a previous walking child after this method.
            // Let that cleanup finish before acquiring a moving mounted lease.
            if (thisOrChildSatisfies(task -> task != this && task.isActive() && !task.stopped())) {
                MountedNavigation.hold(mod, this);
                setDebugState("Stopping walking recovery before riding.");
            } else {
                var horse = MountedNavigation.getControlledHorse(mod.getPlayer());
                BlockPos goalPosition = _cachedGoal instanceof MountedBlockGoal block ? block.resolved().getGoalPos() : null;
                if (_mountedLevel != mod.level() || !horse.getUUID().equals(_mountedHorseId)
                        || _mountedProgressGoal != _cachedGoal || !java.util.Objects.equals(goalPosition, _mountedGoalPosition)) {
                    _mountedProgress.reset();
                    _mountedLevel = mod.level();
                    _mountedHorseId = horse.getUUID();
                    _mountedProgressGoal = _cachedGoal;
                    _mountedGoalPosition = goalPosition;
                }
                MountedNavigation.tick(mod, this, _cachedGoal);
                var decision = allowsMountedWalkingFallback()
                        && mod.getTaskRunner().getCurrentTaskChain() instanceof UserTaskChain
                        ? _mountedProgress.update(mod.getPlayer().tickCount,
                            MountedNavigation.remainingDistance(this),
                            horse.onGround(), MountedNavigation.travelPhase(this))
                        : MountedTravelProgress.Decision.CONTINUE;
                if (decision == MountedTravelProgress.Decision.REPLAN) {
                    MountedNavigation.retryRoute(this);
                } else if (decision == MountedTravelProgress.Decision.WALK) {
                    _continueOnFoot = true;
                    if (_cachedGoal instanceof MountedBlockGoal block) block.continueOnFoot();
                    Debug.logMessage("Mounted route made no progress after retries; continuing on foot.");
                    MountedNavigation.requestDismount(mod, this);
                }
                setDebugState(MountedNavigation.getDebugState(this));
            }
            return null;
        }
        MountedNavigation.release(mod, this);
        // Continue the same water controller before considering another travel mode.
        if (_swimming != null) {
            var outcome = _swimming.tick(mod);
            setDebugState(_swimming.result());
            _checker.reset(); stuckCheck.reset();
            if (outcome == adris.altoclef.util.helpers.SwimNavigation.Outcome.RUNNING) return null;
            _swimming.release(mod); _swimming = null;
            _swimRetryAfter = mod.getPlayer().tickCount + 100;
            return null;
        }
        if (shouldProbeAutomaticElytra(mod)) {
            BlockPos destination = automaticElytraDestination(_cachedGoal);
            _automaticElytraTask = ElytraFlightTask.travel(destination.getX(), destination.getZ());
            _automaticElytraStartTick = mod.getPlayer().tickCount;
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            _checker.reset(); stuckCheck.reset();
            setDebugState("Long coordinate trip; preparing carried elytra and safe fireworks.");
            return null;
        }
        if (shouldProbeAutomaticBoat(mod)) {
            if (_cachedGoal == null) _cachedGoal = newGoal(mod);
            _automaticBoatLastProbe = mod.getPlayer().tickCount;
            var opportunity = AutoBoatPolicy.find(mod, _cachedGoal);
            if (opportunity.isPresent()) {
                _automaticBoatTask = new AutoBoatBoardTask(opportunity.get().waterPoint());
                _automaticBoatStartTick = mod.getPlayer().tickCount;
                // A new water leg must not inherit a previous leg's dismount latch.
                _boatContinueOnFoot = _boatFootConfirmed = false;
                _continueOnFoot = _footConfirmed = false;
                _boatProgress.reset();
                _boatId = null; _boatLevel = null; _boatProgressGoal = null;
                mod.getClientBaritone().getCustomGoalProcess().onLostControl();
                mod.getClientBaritone().getPathingBehavior().forceCancel();
                _checker.reset(); stuckCheck.reset();
                setDebugState("Long water route ahead; preparing a boat.");
                return null;
            }
        }
        if (allowsAutomaticBoat() && mod.getTaskRunner().getCurrentTaskChain() instanceof UserTaskChain chain
                && chain.getCurrentTask() == this && mod.getPlayer().isInWater() && !mod.getPlayer().isPassenger()
                && mod.getPlayer().tickCount >= _swimRetryAfter) {
            if (_cachedGoal == null) _cachedGoal = newGoal(mod);
            BlockPos target = automaticElytraDestination(_cachedGoal);
            if (target != null) {
                // Actual underwater XYZ goals use volume navigation; land/XZ trips use the surface.
                boolean surface = _cachedGoal instanceof GoalXZ || !mod.level().hasChunkAt(target)
                        || !mod.level().getFluidState(target).is(net.minecraft.tags.FluidTags.WATER);
                _swimming = new adris.altoclef.util.helpers.SwimNavigation(mod, target, surface, false);
                mod.getClientBaritone().getCustomGoalProcess().onLostControl();
                mod.getClientBaritone().getPathingBehavior().forceCancel();
                _checker.reset(); stuckCheck.reset();
                return null; // Let the previous walking child release its inputs first.
            }
        }
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            _checker.reset();
        }
        if (WorldHelper.isInNetherPortal(mod)) {
            if (!mod.getClientBaritone().getPathingBehavior().isPathing()) {
                setDebugState("Getting out from nether portal");
                mod.getInputControls().hold(Input.SNEAK);
                mod.getInputControls().hold(Input.MOVE_FORWARD);
                return null;
            }
            mod.getInputControls().release(Input.SNEAK);
            mod.getInputControls().release(Input.MOVE_BACK);
            mod.getInputControls().release(Input.MOVE_FORWARD);
        }
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            mod.getInputControls().release(Input.SNEAK);
            mod.getInputControls().release(Input.MOVE_BACK);
            mod.getInputControls().release(Input.MOVE_FORWARD);
        }
        if (_unstuckTask != null && _unstuckTask.isActive() && !_unstuckTask.isFinished(mod) && stuckInBlock(mod) != null) {
            setDebugState("Getting unstuck from block.");
            stuckCheck.reset();
            // Stop other tasks, we are JUST shimmying
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getExploreProcess().onLostControl();
            return _unstuckTask;
        }
        if (!_checker.check(mod) || !stuckCheck.check(mod)) {
            BlockPos blockStuck = stuckInBlock(mod);
            if (blockStuck != null) {
                _unstuckTask = getFenceUnstuckTask();
                return _unstuckTask;
            }
            stuckCheck.reset();
        }
        if (_cachedGoal == null) {
            _cachedGoal = newGoal(mod);
        }

        if (_wander) {
            if (isFinished(mod)) {
                // Don't wander if we've reached our goal.
                _checker.reset();
            } else {
                if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
                    setDebugState("Wandering...");
                    _checker.reset();
                    return _wanderTask;
                }
                if (!_checker.check(mod)) {
                    Debug.logMessage("Failed to make progress on goal, wandering.");
                    onWander(mod);
                    return _wanderTask;
                }
            }
        }
        if (!mod.getClientBaritone().getCustomGoalProcess().isActive()
                && mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) {
            mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(_cachedGoal);
        }
        setDebugState("Completing goal.");
        return null;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        if (_swimming != null) return false;
        if (_automaticElytraTask != null || _automaticElytraCleanupPending || mod.getPlayer().isFallFlying()) return false;
        // Completion is queried before onTick: capture an exit even if its landing is already the goal.
        observeOrdinaryBoatExit(mod);
        if (_automaticBoatTask != null || _automaticBoatCleanupPending || _boatRecoveryTask != null
                || _boatRecoveryCleanupPending || _boatRecoveryId != null) return false;
        if (_boatContinueOnFoot && !boatWalkingReady(mod)) return false;
        if (_continueOnFoot && (mod.getPlayer().isPassenger() || !_footConfirmed
                || mod.getPlayer().tickCount - _footConfirmationTick <= 0 || !mod.getPlayer().onGround())) {
            return false;
        }
        if (_cachedGoal == null) {
            _cachedGoal = newGoal(mod);
        }
        if (mod.getPlayer().getVehicle() instanceof AbstractBoat) {
            return BoatNavigation.isGoalReached(mod, _cachedGoal);
        }
        if (MountedNavigation.getControlledHorse(mod.getPlayer()) != null) {
            return _cachedGoal != null && MountedNavigation.isGoalReached(mod, _cachedGoal);
        }
        return _cachedGoal != null && _cachedGoal.isInGoal(mod.getPlayer().blockPosition());
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        if (_swimming != null) _swimming.release(mod);
        BoatNavigation.release(mod, this);
        MountedNavigation.release(mod, this);
        mod.getClientBaritone().getPathingBehavior().forceCancel();
    }

    protected abstract Goal newGoal(AltoClef mod);

    private void updateBoatProgress(AltoClef mod) {
        if (!allowsMountedWalkingFallback() || !(mod.getTaskRunner().getCurrentTaskChain() instanceof UserTaskChain)
                || BoatNavigation.isDocking(this)) return;
        if (!(mod.getPlayer().getVehicle() instanceof AbstractBoat vessel)) return;
        if (_boatLevel != mod.level() || !vessel.getUUID().equals(_boatId) || _boatProgressGoal != _cachedGoal) {
            _boatProgress.reset();
            _boatLevel = mod.level(); _boatId = vessel.getUUID(); _boatProgressGoal = _cachedGoal;
        }
        var phase = BoatNavigation.travelPhase(this);
        var decision = _boatProgress.update(mod.getPlayer().tickCount, _cachedGoal.heuristic(vessel.blockPosition()) / 4,
                phase != MountedTravelProgress.Phase.AIRBORNE, phase);
        if (decision == MountedTravelProgress.Decision.REPLAN) {
            BoatNavigation.retryRoute(this);
        } else if (decision == MountedTravelProgress.Decision.WALK) {
            _boatContinueOnFoot = true;
            _boatRecoveryId = vessel.getUUID();
            BoatNavigation.requestDismount(mod, this);
        }
    }

    private boolean prepareBoatWalking(AltoClef mod) {
        if (MountedNavigation.isSafetyFrame()) return false;
        if (mod.getPlayer().isPassenger()) {
            _boatFootConfirmed = false;
            if (mod.getPlayer().getVehicle() instanceof AbstractBoat) {
                BoatNavigation.requestDismount(mod, this);
                setDebugState(BoatNavigation.getDebugState(this));
            } else MountedNavigation.requestDismount(mod, this);
            return false;
        }
        BoatNavigation.release(mod, this);
        if (!_boatFootConfirmed) {
            _boatFootConfirmed = true; _boatFootTick = mod.getPlayer().tickCount;
            blockAutomaticBoatRetry(mod);
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            _checker.reset(); stuckCheck.reset();
            return false;
        }
        if (!boatWalkingReady(mod)) { setDebugState("Waiting for boat dismount confirmation and footing."); return false; }
        return true;
    }

    private boolean boatWalkingReady(AltoClef mod) {
        return _boatFootConfirmed && !mod.getPlayer().isPassenger() && mod.getPlayer().tickCount - _boatFootTick > 0
                && (mod.getPlayer().onGround() || mod.getPlayer().isInWater());
    }

    /** Ordinary travel may abandon the mount after bounded failed attempts. */
    protected boolean allowsMountedWalkingFallback() {
        return false;
    }

    /** Only ordinary coordinate travel opts in; interaction/resource child tasks never place a boat. */
    protected boolean allowsAutomaticBoat() { return false; }

    private boolean shouldProbeAutomaticElytra(AltoClef mod) {
        if (!allowsAutomaticBoat() || _automaticElytraCompleted || MountedNavigation.isSafetyFrame()
                || !(mod.getTaskRunner().getCurrentTaskChain() instanceof UserTaskChain chain)
                || chain.getCurrentTask() != this || mod.getPlayer().isPassenger() || !mod.getPlayer().onGround()
                || mod.getPlayer().isFallFlying() || mod.getFoodChain().isTryingToEat() || mod.getPlayer().isUsingItem()
                || _automaticBoatTask != null || _automaticBoatCleanupPending || _boatRecoveryTask != null
                || _boatRecoveryCleanupPending || _boatRecoveryId != null) return false;
        if (_automaticElytraWorld != mod.level()) {
            _automaticElytraWorld = mod.level();
            _automaticElytraLastProbe = Integer.MIN_VALUE / 2;
            _automaticElytraRetryAfter = 0;
            _automaticElytraBlockedOrigin = null;
        }
        if (!automaticElytraRetryReady(mod.getPlayer().tickCount, _automaticElytraLastProbe,
                _automaticElytraRetryAfter, mod.getPlayer().position(), _automaticElytraBlockedOrigin)) return false;
        _automaticElytraLastProbe = mod.getPlayer().tickCount;
        if (_cachedGoal == null) _cachedGoal = newGoal(mod);
        BlockPos destination = automaticElytraDestination(_cachedGoal);
        return automaticElytraDistanceReady(mod.getPlayer().position(), destination)
                && ElytraEquipmentHelper.hasUsableElytra(mod) && ElytraEquipmentHelper.countSafeRockets(mod) >= 8;
    }

    private static BlockPos automaticElytraDestination(Goal goal) {
        if (goal instanceof GoalXZ xz) return new BlockPos(xz.getX(), 0, xz.getZ());
        // Virtual getGoalPos preserves an already resolved MountedBlockGoal destination.
        return goal instanceof GoalBlock block ? block.getGoalPos() : null;
    }

    private static boolean automaticElytraDistanceReady(Vec3 position, BlockPos destination) {
        return position != null && destination != null
                && Math.hypot(position.x - (destination.getX() + .5), position.z - (destination.getZ() + .5)) >= 256;
    }

    private static boolean automaticElytraRetryReady(int tick, int lastProbe, int retryAfter,
                                                     Vec3 position, Vec3 blockedOrigin) {
        return tick - lastProbe >= 20 && tick >= retryAfter && (blockedOrigin == null
                || Math.hypot(position.x - blockedOrigin.x, position.z - blockedOrigin.z) >= 32);
    }

    private boolean shouldProbeAutomaticBoat(AltoClef mod) {
        if (!allowsAutomaticBoat() || MountedNavigation.isSafetyFrame()
                || !(mod.getTaskRunner().getCurrentTaskChain() instanceof UserTaskChain chain)
                || chain.getCurrentTask() != this || mod.getPlayer().isPassenger()
                || mod.getFoodChain().isTryingToEat() || mod.getPlayer().isUsingItem()) return false;
        if (_automaticBoatWorld != mod.level()) {
            _automaticBoatWorld = mod.level();
            _automaticBoatLastProbe = Integer.MIN_VALUE / 2;
            _automaticBoatRetryAfter = 0;
            _automaticBoatBlockedOrigin = null;
        }
        return automaticBoatRetryReady(mod.getPlayer().tickCount, _automaticBoatLastProbe,
                _automaticBoatRetryAfter, mod.getPlayer().position(), _automaticBoatBlockedOrigin);
    }

    private static boolean automaticBoatRetryReady(int tick, int lastProbe, int retryAfter,
                                                   Vec3 position, Vec3 blockedOrigin) {
        return tick - lastProbe >= 20 && tick >= retryAfter && (blockedOrigin == null
                || Math.hypot(position.x - blockedOrigin.x, position.z - blockedOrigin.z) >= 16);
    }

    private void blockAutomaticBoatRetry(AltoClef mod) {
        _automaticBoatWorld = mod.level();
        _automaticBoatRetryAfter = mod.getPlayer().tickCount + 200;
        _automaticBoatBlockedOrigin = mod.getPlayer().position();
    }

    private void observeOrdinaryBoatExit(AltoClef mod) {
        var player = mod.getPlayer();
        boolean ordinary = allowsAutomaticBoat() && !MountedNavigation.isSafetyFrame()
                && mod.getTaskRunner().getCurrentTaskChain() instanceof UserTaskChain chain
                && chain.getCurrentTask() == this;
        boolean pending = _automaticBoatTask != null || _automaticBoatCleanupPending
                || _boatRecoveryTask != null || _boatRecoveryCleanupPending
                || _boatRecoveryId != null || _boatContinueOnFoot
                || _automaticElytraTask != null || _automaticElytraCleanupPending;
        var controlled = player != null && ordinary ? BoatNavigation.getControlledBoat(player) : null;
        UUID exited = _automaticBoatExit.observe(player, mod.level(), player != null && player.isAlive(),
                ordinary, player != null && player.isPassenger(),
                controlled == null ? null : controlled.getUUID(), pending);
        if (_automaticBoatExit.contextChanged) {
            // An unstarted recovery must not resolve an old UUID in a different world.
            _boatRecoveryId = null;
            if (_boatRecoveryTask != null) {
                _boatRecoveryTask = null;
                _boatRecoveryCleanupPending = true;
            }
            _boatContinueOnFoot = _boatFootConfirmed = false;
            _boatId = null; _boatLevel = null; _boatProgressGoal = null;
            _boatProgress.reset();
        }
        if (exited != null) {
            _boatRecoveryId = exited;
            _boatContinueOnFoot = true;
            _boatFootConfirmed = false;
            _checker.reset(); stuckCheck.reset();
            setDebugState("Boat dismount observed; waiting for footing before optional recovery.");
        }
    }

    /** Actual transition memory is pure so server exits, interruptions and world changes can be replayed. */
    private static final class BoatExitMemory {
        private Object player, world;
        private UUID boat;
        private boolean contextChanged;

        private UUID observe(Object currentPlayer, Object currentWorld, boolean alive,
                             boolean ordinaryRoot, boolean passenger, UUID controlledBoat, boolean pending) {
            contextChanged = false;
            if (currentPlayer == null || currentWorld == null || !alive) {
                contextChanged = player != null || world != null;
                player = world = null;
                boat = null;
                return null;
            }
            if (player != currentPlayer || world != currentWorld) {
                contextChanged = player != null || world != null;
                player = currentPlayer; world = currentWorld;
                boat = null;
            }
            // A higher-priority chain may pause ordinary travel; retain its observation until it resumes.
            if (!ordinaryRoot) return null;
            if (pending) { boat = null; return null; }
            if (passenger) { boat = controlledBoat; return null; }
            UUID exited = boat;
            boat = null;
            return exited;
        }
    }

    protected void onWander(AltoClef mod) {
    }
}
