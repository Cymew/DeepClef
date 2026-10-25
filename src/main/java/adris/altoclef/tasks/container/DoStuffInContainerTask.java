package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.DoToClosestBlockTask;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.PlaceBlockNearbyTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.BaritoneHelper;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;


/**
 * Interacts with a container, obtaining and placing one if none were found nearby.
 */
public abstract class DoStuffInContainerTask extends Task {

    /** Surrounding-search offsets (16x16 horizontal, ±6 vertical), nearest first. */
    private static final List<BlockPos> SURROUNDING_OFFSETS = buildSurroundingOffsets();

    private static List<BlockPos> buildSurroundingOffsets() {
        List<BlockPos> offsets = new ArrayList<>();
        for (int dx = -16; dx <= 16; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
                if (dx * dx + dz * dz > 16 * 16) continue;
                for (int dy = -6; dy <= 6; dy++) {
                    offsets.add(new BlockPos(dx, dy, dz));
                }
            }
        }
        offsets.sort(Comparator.comparingLong(o ->
                (long) o.getX() * o.getX() + o.getY() * o.getY() + o.getZ() * o.getZ()));
        return offsets;
    }

    private final ItemTarget _containerTarget;
    private final Block[] _containerBlocks;

    private final PlaceBlockNearbyTask _placeTask;
    // If we decided on placing, force place for at least 10 seconds
    private final TimerGame _placeForceTimer = new TimerGame(10);
    // If we just placed something, stop placing and try going to the nearest container.
    private final TimerGame _justPlacedTimer = new TimerGame(3);
    private BlockPos _cachedContainerPosition = null;
    private Task _openTableTask;
    // Active surrounding search: find an EXISTING container nearby before crafting one.
    private BlockPos _surroundingContainer = null;
    private int _surroundingSearchIndex = 0;
    private boolean _surroundingSearchDone = false;
    private Task _openSurroundingTask = null;

    public DoStuffInContainerTask(Block[] containerBlocks, ItemTarget containerTarget) {
        _containerBlocks = containerBlocks;
        _containerTarget = containerTarget;

        _placeTask = new PlaceBlockNearbyTask(_containerBlocks);
    }

    public DoStuffInContainerTask(Block containerBlock, ItemTarget containerTarget) {
        this(new Block[]{containerBlock}, containerTarget);
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBehaviour().push();
        if (_openTableTask == null) {
            _openTableTask = new DoToClosestBlockTask(InteractWithBlockTask::new, _containerBlocks);
        }

        mod.getBlockTracker().trackBlock(_containerBlocks);

        // Protect container since we might place it.
        mod.getBehaviour().addProtectedItems(ItemHelper.blocksToItems(_containerBlocks));
    }

    @Override
    protected Task onTick(AltoClef mod) {

        // If we're placing, keep on placing.
        if (mod.getItemStorage().hasItem(ItemHelper.blocksToItems(_containerBlocks)) && _placeTask.isActive() && !_placeTask.isFinished(mod)) {
            setDebugState("Placing container");
            return _placeTask;
        }

        if (isContainerOpen(mod)) {
            return containerSubTask(mod);
        }

        // infinity if such a container does not exist.
        double costToWalk = Double.POSITIVE_INFINITY;

        Optional<BlockPos> nearest;

        Vec3 currentPos = mod.getPlayer().position();
        BlockPos override = overrideContainerPosition(mod);

        if (override != null && mod.getBlockTracker().blockIsValid(override, _containerBlocks)) {
            // We have an override so go there instead.
            nearest = Optional.of(override);
        } else {
            // Track nearest container
            nearest = mod.getBlockTracker().getNearestTracking(currentPos, blockPos -> WorldHelper.canReach(mod, blockPos), _containerBlocks);
        }
        if (nearest.isEmpty()) {
            // If all else fails, try using our placed task
            nearest = Optional.ofNullable(_placeTask.getPlaced());
            if (nearest.isPresent() && !mod.getBlockTracker().blockIsValid(nearest.get(), _containerBlocks)) {
                nearest = Optional.empty();
            }
        }
        // Search the immediate surroundings for an EXISTING container before
        // falling back to crafting/collecting a new one (spread over ticks).
        if (nearest.isEmpty() && !_surroundingSearchDone && _surroundingContainer == null) {
            _surroundingContainer = searchSurroundingTick(mod);
            if (_surroundingContainer != null) {
                nearest = Optional.of(_surroundingContainer);
                if (_openSurroundingTask == null) {
                    final BlockPos found = _surroundingContainer;
                    _openSurroundingTask = new DoToClosestBlockTask(InteractWithBlockTask::new,
                            pos -> Optional.of(found), blockPos -> true, _containerBlocks);
                }
            } else if (_surroundingSearchIndex < SURROUNDING_OFFSETS.size()) {
                setDebugState("Searching surroundings for container...");
                return null;
            } else {
                _surroundingSearchDone = true;
            }
        }
        if (nearest.isPresent()) {
            costToWalk = BaritoneHelper.calculateGenericHeuristic(currentPos, WorldHelper.toVec3d(nearest.get()));
        }
        // A container found by the surrounding search is always used directly;
        // the make-new cost comparison only applies to tracker-known containers.
        boolean searchedFound = nearest.isPresent() && _surroundingContainer != null
                && _surroundingContainer.equals(nearest.get());

        // Make a new container if going to the container is a pretty bad cost.
        // Also keep on making the container if we're stuck in some
        if (!searchedFound && costToWalk > getCostToMakeNew(mod)) {
            _placeForceTimer.reset();
        }
        if (nearest.isEmpty() || (!searchedFound && !_placeForceTimer.elapsed() && _justPlacedTimer.elapsed())) {
            // It's cheaper to make a new one, or our only option.

            // We're no longer going to our previous container.
            _cachedContainerPosition = null;

            // Get if we don't have...
            if (!mod.getItemStorage().hasItem(_containerTarget)) {
                setDebugState("Getting container item");
                return TaskCatalogue.getItemTask(_containerTarget);
            }

            setDebugState("Placing container...");

            _justPlacedTimer.reset();
            // Now place!
            return _placeTask;
        }

        // This is insanely cursed.
        // TODO: Finish committing to optionals, this is ugly.
        _cachedContainerPosition = nearest.get();

        // Walk to it and open it

        // Wait for food
        if (mod.getFoodChain().needsToEat()) {
            setDebugState("Waiting for eating...");
            return null;
        }
        setDebugState("Walking to container... " + nearest.get().toShortString());

        if (!StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            Optional<Slot> toMoveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(StorageHelper.getItemStackInCursorSlot(), false);
            if (toMoveTo.isEmpty()) {
                return new EnsureFreeInventorySlotTask();
            }
            if (ItemHelper.canThrowAwayStack(mod, StorageHelper.getItemStackInCursorSlot())) {
                mod.getSlotHandler().handleInventoryMouseClick(Slot.UNDEFINED, 0, ContainerInput.PICKUP);
                return null;
            }
            mod.getSlotHandler().handleInventoryMouseClick(toMoveTo.get(), 0, ContainerInput.PICKUP);
            return null;
        }
        // If the target was found by the surrounding search, walk to that exact
        // position instead of letting the tracker pick (possibly) another one.
        if (_surroundingContainer != null && _surroundingContainer.equals(nearest.get()) && _openSurroundingTask != null) {
            return _openSurroundingTask;
        }
        return _openTableTask;
        //return new GetToBlockTask(nearest, true);
    }

    /** Scans a slice of the surrounding offsets for an existing container block. */
    private BlockPos searchSurroundingTick(AltoClef mod) {
        BlockPos center = mod.getPlayer().blockPosition();
        int end = Math.min(_surroundingSearchIndex + 1500, SURROUNDING_OFFSETS.size());
        for (; _surroundingSearchIndex < end; _surroundingSearchIndex++) {
            BlockPos pos = center.offset(SURROUNDING_OFFSETS.get(_surroundingSearchIndex));
            if (mod.getBlockTracker().blockIsValid(pos, _containerBlocks)) {
                return pos;
            }
        }
        return null;
    }

    public ItemTarget getContainerTarget() {
        return _containerTarget;
    }

    // Virtual
    protected BlockPos overrideContainerPosition(AltoClef mod) {
        return null;
    }

    protected BlockPos getTargetContainerPosition() {
        return _cachedContainerPosition;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
        mod.getBlockTracker().stopTracking(_containerBlocks);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DoStuffInContainerTask task) {
            if (!Arrays.equals(task._containerBlocks, _containerBlocks)) return false;
            if (!task._containerTarget.equals(_containerTarget)) return false;
            return isSubTaskEqual(task);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Doing stuff in " + _containerTarget + " container";
    }

    protected abstract boolean isSubTaskEqual(DoStuffInContainerTask other);

    protected abstract boolean isContainerOpen(AltoClef mod);

    protected abstract Task containerSubTask(AltoClef mod);

    protected abstract double getCostToMakeNew(AltoClef mod);
}
