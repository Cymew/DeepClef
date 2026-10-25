package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.PutOutFireTask;
import adris.altoclef.tasks.movement.EnterNetherPortalTask;
import adris.altoclef.tasks.movement.EscapeFromLavaTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.SafeRandomShimmyTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.ElytraNavigation;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Optional;

public class WorldSurvivalChain extends SingleTaskChain {

    private enum Plan { NONE, LAVA, FIRE, DROWNING, EXTINGUISH, FIND_WATER, COLLECT_WATER, PORTAL_ESCAPE }

    private final TimerGame _wasInLavaTimer = new TimerGame(1);
    private final TimerGame _portalStuckTimer = new TimerGame(5);
    private BlockPos _extinguishWaterPosition;
    private Plan _plan = Plan.NONE;
    private BlockPos _plannedWaterPosition;
    private Rotation _plannedWaterRotation;
    private boolean _ownsJump;
    private boolean _ownsRightClick;
    private boolean _ownsInteractionPause;

    public WorldSurvivalChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    protected void onTaskFinish(AltoClef mod) { }

    @Override
    public float getPriority(AltoClef mod) {
        // Scoring must not turn, equip, press keys, stop tasks, or change global interaction state.
        _plan = Plan.NONE;
        _plannedWaterPosition = null;
        _plannedWaterRotation = null;
        if (!AltoClef.inGame()) return Float.NEGATIVE_INFINITY;

        if ((!ElytraNavigation.ownsFlight(mod) || mod.getPlayer().isInLava())
                && isInLavaOhShit(mod) && mod.getBehaviour().shouldEscapeLava()) {
            _plan = Plan.LAVA;
            return 100;
        }
        if (isInFire(mod)) {
            _plan = Plan.FIRE;
            return 100;
        }
        if (mod.getModSettings().shouldAvoidDrowning()
                && !mod.getClientBaritone().getPathingBehavior().isPathing()
                && mod.getPlayer().isInWater()
                && mod.getPlayer().getAirSupply() < mod.getPlayer().getMaxAirSupply()) {
            _plan = Plan.DROWNING;
            return 100;
        }

        // Real lava, touching fire and drowning above retain emergency priority.
        // Residual burning aloft is handled by flight's landing plan; old water
        // collection and ground escape must not take its camera or rocket hand.
        if (ElytraNavigation.ownsFlight(mod)) return Float.NEGATIVE_INFINITY;

        if (mod.getModSettings().shouldExtinguishSelfWithWater()) {
            if (mod.getPlayer().isOnFire() && !mod.getPlayer().hasEffect(MobEffects.FIRE_RESISTANCE)
                    && mod.level().dimension() != Level.NETHER) {
                if (mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
                    BlockPos waterPosition = mod.getPlayer().blockPosition();
                    if (WorldHelper.isSolid(mod, waterPosition.below()) && WorldHelper.canPlace(mod, waterPosition)) {
                        Optional<Rotation> reach = LookHelper.getReach(waterPosition.below(), Direction.UP);
                        if (reach.isPresent()) {
                            _plan = Plan.EXTINGUISH;
                            _plannedWaterPosition = waterPosition;
                            _plannedWaterRotation = reach.get();
                            return 90;
                        }
                    }
                }
                _plan = Plan.FIND_WATER;
                return 90;
            }
            if (mod.getItemStorage().hasItem(Items.BUCKET) && _extinguishWaterPosition != null
                    && mod.getBlockTracker().blockIsValid(_extinguishWaterPosition, Blocks.WATER)) {
                _plan = Plan.COLLECT_WATER;
                _plannedWaterPosition = _extinguishWaterPosition;
                return 60;
            }
        }
        _extinguishWaterPosition = null;

        if (isStuckInNetherPortal(mod)) {
            if (_portalStuckTimer.elapsed()) {
                _plan = Plan.PORTAL_ESCAPE;
                return 60;
            }
        } else {
            _portalStuckTimer.reset();
        }
        return Float.NEGATIVE_INFINITY;
    }

    @Override
    protected void onTick(AltoClef mod) {
        Task task = switch (_plan) {
            case LAVA -> new EscapeFromLavaTask();
            case FIRE -> new DoToClosestBlockTask(PutOutFireTask::new, Blocks.FIRE, Blocks.SOUL_FIRE);
            case FIND_WATER -> new DoToClosestBlockTask(GetToBlockTask::new, Blocks.WATER);
            case COLLECT_WATER -> new InteractWithBlockTask(new ItemTarget(Items.BUCKET, 1), Direction.UP,
                    _plannedWaterPosition.below(), true);
            case PORTAL_ESCAPE -> new SafeRandomShimmyTask();
            default -> null;
        };
        // Stop a previous safety task before applying the newly selected action's inputs.
        setTask(task);
        if (_plan != Plan.DROWNING && _ownsJump) {
            mod.getInputControls().release(Input.JUMP);
            _ownsJump = false;
        }
        if (_plan != Plan.EXTINGUISH && _ownsRightClick) {
            mod.getInputControls().release(Input.CLICK_RIGHT);
            _ownsRightClick = false;
        }
        if (_plan != Plan.PORTAL_ESCAPE) releaseInteractionPause(mod);

        if (_plan == Plan.DROWNING) {
            mod.getInputControls().hold(Input.JUMP);
            _ownsJump = true;
        } else if (_plan == Plan.EXTINGUISH) {
            mod.getClientBaritone().getLookBehavior().updateTarget(_plannedWaterRotation, true);
            if (mod.getClientBaritone().getPlayerContext().isLookingAt(_plannedWaterPosition.below())
                    && mod.getSlotHandler().forceEquipItem(Items.WATER_BUCKET)) {
                _extinguishWaterPosition = _plannedWaterPosition;
                mod.getInputControls().tryPress(Input.CLICK_RIGHT);
                _ownsRightClick = true;
            }
        } else if (_plan == Plan.PORTAL_ESCAPE) {
            mod.getExtraBaritoneSettings().setInteractionPaused(true);
            _ownsInteractionPause = true;
        }
        super.onTick(mod);
    }

    private boolean isInLavaOhShit(AltoClef mod) {
        if (mod.getPlayer().isInLava() && !mod.getPlayer().hasEffect(MobEffects.FIRE_RESISTANCE)) {
            _wasInLavaTimer.reset();
            return true;
        }
        return mod.getPlayer().isOnFire() && !_wasInLavaTimer.elapsed();
    }

    private boolean isInFire(AltoClef mod) {
        if (mod.getPlayer().isOnFire() && !mod.getPlayer().hasEffect(MobEffects.FIRE_RESISTANCE)) {
            for (BlockPos pos : WorldHelper.getBlocksTouchingPlayer(mod)) {
                Block block = mod.level().getBlockState(pos).getBlock();
                if (block instanceof BaseFireBlock) return true;
            }
        }
        return false;
    }

    private boolean isStuckInNetherPortal(AltoClef mod) {
        Task userTask = mod.getUserTaskChain().getCurrentTask();
        return WorldHelper.isInNetherPortal(mod)
                && (userTask == null || !userTask.thisOrChildSatisfies(task -> task instanceof EnterNetherPortalTask));
    }

    private void releaseInteractionPause(AltoClef mod) {
        if (_ownsInteractionPause) {
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
            _ownsInteractionPause = false;
        }
    }

    private void releaseOwnedActions(AltoClef mod) {
        if (_ownsJump) {
            mod.getInputControls().release(Input.JUMP);
            _ownsJump = false;
        }
        if (_ownsRightClick) {
            mod.getInputControls().release(Input.CLICK_RIGHT);
            _ownsRightClick = false;
        }
        releaseInteractionPause(mod);
    }

    @Override
    public void onInterrupt(AltoClef mod, TaskChain other) {
        super.onInterrupt(mod, other);
        releaseOwnedActions(mod);
    }

    @Override
    protected void onStop(AltoClef mod) {
        super.onStop(mod);
        releaseOwnedActions(mod);
    }

    @Override
    public String getName() {
        return "Misc Level Survival Chain";
    }

    @Override
    public boolean isActive() {
        return true;
    }
}
