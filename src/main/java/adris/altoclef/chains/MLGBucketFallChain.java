package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.movement.MLGBucketTask;
import adris.altoclef.tasksystem.ITaskOverridesGrounded;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.ElytraNavigation;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;

import java.util.Optional;

public class MLGBucketFallChain extends SingleTaskChain implements ITaskOverridesGrounded {

    private enum Plan { NONE, CLUTCH, COLLECT_WATER, CHORUS_FRUIT }

    private final TimerGame _tryCollectWaterTimer = new TimerGame(4);
    private final TimerGame _pickupRepeatTimer = new TimerGame(0.25);
    private MLGBucketTask _lastMLG;
    private Plan _plan = Plan.NONE;
    private Plan _activePlan = Plan.NONE;
    private boolean _ownsForeground;
    private BlockPos _pickupPosition;
    private boolean _ownsRightClick;
    private boolean _ownsInteractionPause;
    private boolean _doingChorusFruit;

    public MLGBucketFallChain(TaskRunner runner) {
        super(runner);
    }

    @Override
    protected void onTaskFinish(AltoClef mod) { }

    @Override
    public float getPriority(AltoClef mod) {
        // Detect and cache a plan only. Other chains may still win this tick.
        _plan = Plan.NONE;
        _pickupPosition = null;
        if (!AltoClef.inGame()) return Float.NEGATIVE_INFINITY;
        if (isFallingOhNo(mod)) {
            _tryCollectWaterTimer.reset();
            _plan = Plan.CLUTCH;
            return 100;
        }
        if (!ElytraNavigation.ownsFlight(mod) && !_tryCollectWaterTimer.elapsed() && _lastMLG != null
                && mod.getItemStorage().hasItem(Items.BUCKET)
                && !mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
            BlockPos placed = _lastMLG.getWaterPlacedPos();
            if (placed != null && placed.closerThan(mod.getPlayer().blockPosition(), 5.5)
                    && mod.level().getBlockState(placed).getBlock() == Blocks.WATER) {
                _plan = Plan.COLLECT_WATER;
                _pickupPosition = placed;
                return 60;
            }
        }
        _lastMLG = null;
        if (mod.getPlayer().hasEffect(MobEffects.LEVITATION)
                && !mod.getPlayer().getCooldowns().isOnCooldown(new ItemStack(Items.CHORUS_FRUIT))
                && mod.getPlayer().getEffect(MobEffects.LEVITATION).getDuration() <= 70
                && mod.getItemStorage().hasItemInventoryOnly(Items.CHORUS_FRUIT)
                && !mod.getItemStorage().hasItemInventoryOnly(Items.WATER_BUCKET)) {
            _plan = Plan.CHORUS_FRUIT;
            return 100;
        }
        return Float.NEGATIVE_INFINITY;
    }

    @Override
    protected void onTick(AltoClef mod) {
        _ownsForeground = true;
        _activePlan = _plan;
        if (_plan != Plan.CHORUS_FRUIT) releaseChorusFruit(mod);
        switch (_plan) {
            case CLUTCH -> {
                setTask(new MLGBucketTask());
                _lastMLG = (MLGBucketTask) _mainTask;
            }
            case COLLECT_WATER -> collectWater(mod);
            case CHORUS_FRUIT -> {
                setTask(null);
                if (mod.getSlotHandler().forceEquipItem(Items.CHORUS_FRUIT)) {
                    mod.getInputControls().hold(Input.CLICK_RIGHT);
                    _ownsRightClick = true;
                    _doingChorusFruit = true;
                    mod.getExtraBaritoneSettings().setInteractionPaused(true);
                    _ownsInteractionPause = true;
                }
            }
            default -> setTask(null);
        }
        super.onTick(mod);
    }

    private void collectWater(AltoClef mod) {
        Optional<Rotation> reach;
        mod.getBehaviour().push();
        try {
            mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.SOURCE_ONLY);
            reach = LookHelper.getReach(_pickupPosition, Direction.UP);
        } finally {
            mod.getBehaviour().pop();
        }
        if (reach.isEmpty()) {
            setTask(TaskCatalogue.getItemTask(Items.WATER_BUCKET, 1));
            return;
        }
        // Any old clutch/fetch task releases its controls before this water-pickup action takes them.
        setTask(null);
        mod.getBehaviour().push();
        try {
            mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.SOURCE_ONLY);
            mod.getClientBaritone().getLookBehavior().updateTarget(reach.get(), true);
            if (mod.getClientBaritone().getPlayerContext().isLookingAt(_pickupPosition)
                    && mod.getSlotHandler().forceEquipItem(Items.BUCKET) && _pickupRepeatTimer.elapsed()) {
                _pickupRepeatTimer.reset();
                mod.getInputControls().tryPress(Input.CLICK_RIGHT);
                _ownsRightClick = true;
            }
        } finally {
            mod.getBehaviour().pop();
        }
    }

    private void releaseChorusFruit(AltoClef mod) {
        if (_doingChorusFruit) {
            // Never cancel a different owner's use action during a later cleanup pass.
            if (AltoClef.inGame() && mod.getPlayer().isUsingItem()
                    && mod.getPlayer().getUseItem().is(Items.CHORUS_FRUIT) && mod.getController() != null) {
                mod.getController().releaseUsingItem(mod.getPlayer());
            }
            _doingChorusFruit = false;
        }
        if (_ownsRightClick) {
            mod.getInputControls().release(Input.CLICK_RIGHT);
            _ownsRightClick = false;
        }
        if (_ownsInteractionPause) {
            mod.getExtraBaritoneSettings().setInteractionPaused(false);
            _ownsInteractionPause = false;
        }
    }

    @Override
    public void onInterrupt(AltoClef mod, TaskChain other) {
        super.onInterrupt(mod, other);
        releaseChorusFruit(mod);
        _ownsForeground = false;
        _activePlan = Plan.NONE;
    }

    @Override
    protected void onStop(AltoClef mod) {
        super.onStop(mod);
        releaseChorusFruit(mod);
        _lastMLG = null;
        _plan = Plan.NONE;
        _ownsForeground = false;
        _activePlan = Plan.NONE;
    }

    @Override
    public String getName() {
        return "MLG Water Bucket Fall Chain";
    }

    @Override
    public boolean isActive() {
        return true;
    }

    public boolean doneMLG() {
        // A planned 60-priority pickup must not veto a combat chain that won arbitration.
        // Actual falling is guarded separately by isFallingOhNo().
        return !_ownsForeground || _activePlan != Plan.CLUTCH && _activePlan != Plan.COLLECT_WATER;
    }

    public boolean isChorusFruiting() {
        return _ownsForeground && _doingChorusFruit;
    }

    public boolean isFallingOhNo(AltoClef mod) {
        if (!mod.getModSettings().shouldAutoMLGBucket()) return false;
        // A controlled dive is real flight. Losing glide still falls through to
        // the ordinary clutch detector even while a flight task is pending.
        if (ElytraNavigation.ownsFlight(mod) && mod.getPlayer().isFallFlying()) return false;
        if (mod.getPlayer().isSwimming() || mod.getPlayer().isInWater()
                || mod.getPlayer().onGround() || mod.getPlayer().onClimbable()) return false;
        return mod.getPlayer().getDeltaMovement().y < -0.7;
    }
}
