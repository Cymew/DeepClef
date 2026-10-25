package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.slot.MoveItemToSlotFromInventoryTask;
import adris.altoclef.tasksystem.ITaskUsesCraftingGrid;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.CraftingTableSlot;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;

/** One opportunistic boat craft. No resource task, chest lookup, recipe gathering or mining. */
public final class CarriedBoatCraftTask extends Task {
    private enum Phase { TABLE_CRAFT, TABLE_PLACE, TABLE_OPEN, BOAT_CRAFT }
    private final Item _boat, _planks;
    private LocalPlayer _player;
    private ClientLevel _world;
    private Phase _phase = Phase.TABLE_CRAFT;
    private RecipeTask _recipe;
    private InteractWithBlockTask _open;
    private BlockPos _table;
    private boolean _tableRequested, _done, _inventoryCraftOwned;
    private int _ticks, _phaseTicks, _lastTick = Integer.MIN_VALUE, _menuId = -1;
    private int _originalSlot = -1, _tableSlot = -1;

    public CarriedBoatCraftTask(Item boat, Item planks) { _boat = boat; _planks = planks; }

    @Override protected void onStart(AltoClef mod) {
        if (_player == null) { _player = mod.getPlayer(); _world = mod.level(); }
        _lastTick = Integer.MIN_VALUE;
    }

    @Override protected Task onTick(AltoClef mod) {
        if (_done) return null;
        if (_player == null || mod.getPlayer() != _player || _world == null || mod.level() != _world
                || !_player.isAlive() || _player.isPassenger()) return finish(mod);
        if (count(_player, _boat) > 0) return finish(mod);
        if (_player.isUsingItem() || mod.getFoodChain().isTryingToEat()) return null;
        if (_lastTick != _player.tickCount) { _lastTick = _player.tickCount; _ticks++; _phaseTicks++; }
        if (_ticks > 540) return finish(mod);
        switch (_phase) {
            case TABLE_CRAFT -> {
                if (count(_player, Items.CRAFTING_TABLE) > 0) {
                    advance(mod, Phase.TABLE_PLACE);
                    return null;
                }
                if (_player.containerMenu != _player.inventoryMenu) return finish(mod);
                if (_recipe == null) {
                    if (count(_player, _planks) < 9 || !emptyGrid(false)) return finish(mod);
                    _recipe = new RecipeTask(Items.CRAFTING_TABLE, _planks, false);
                }
                if (_recipe.failed()) return finish(mod);
                _inventoryCraftOwned = true;
                setDebugState("Making one table from carried matching planks.");
                return _recipe;
            }
            case TABLE_PLACE -> {
                if (_table != null && _world.hasChunkAt(_table)
                        && _world.getBlockState(_table).is(Blocks.CRAFTING_TABLE)) {
                    advance(mod, Phase.TABLE_OPEN);
                    return null;
                }
                if (_tableRequested) {
                    if (_phaseTicks > 100) return finish(mod);
                    setDebugState("Waiting for this crafting table placement.");
                    return null;
                }
                if (count(_player, Items.CRAFTING_TABLE) == 0
                        || _player.containerMenu != _player.inventoryMenu
                        || !_player.containerMenu.getCarried().isEmpty()) return finish(mod);
                if (_table == null) _table = findPlacement();
                if (_table == null) return finish(mod);
                mod.getClientBaritone().getPathingBehavior().forceCancel();
                BlockHitResult hit = placementHit(_table);
                if (hit == null) return finish(mod);
                if (_originalSlot < 0) _originalSlot = _player.getInventory().getSelectedSlot();
                if (!mod.getSlotHandler().forceEquipItem(Items.CRAFTING_TABLE)) return null;
                _tableSlot = _player.getInventory().getSelectedSlot();
                if (!_player.getMainHandItem().is(Items.CRAFTING_TABLE)) return null;
                LookHelper.lookAt(mod, hit.getLocation());
                _tableRequested = true;
                _phaseTicks = 0;
                mod.getController().useItemOn(_player, InteractionHand.MAIN_HAND, hit);
                return null;
            }
            case TABLE_OPEN -> {
                if (!validTable()) return finish(mod);
                if (_player.containerMenu instanceof CraftingMenu) {
                    if (_open == null) return finish(mod);
                    _menuId = _player.containerMenu.containerId;
                    advance(mod, Phase.BOAT_CRAFT);
                    return null;
                }
                if (_player.containerMenu != _player.inventoryMenu) return finish(mod);
                if (_phaseTicks > 100) return finish(mod);
                if (_open == null) _open = new InteractWithBlockTask(_table);
                setDebugState("Opening the table just placed from inventory.");
                return _open;
            }
            case BOAT_CRAFT -> {
                if (!validTable()) return finish(mod);
                if (!(_player.containerMenu instanceof CraftingMenu)) {
                    if (_player.containerMenu != _player.inventoryMenu) return finish(mod);
                    advance(mod, Phase.TABLE_OPEN);
                    return null;
                }
                if (_player.containerMenu.containerId != _menuId) return finish(mod);
                if (_recipe == null) {
                    if (!emptyGrid(true)) return finish(mod);
                    _recipe = new RecipeTask(_boat, _planks, true);
                }
                if (_recipe.failed()) return finish(mod);
                setDebugState("Crafting one boat from five carried matching planks.");
                return _recipe;
            }
        }
        return null;
    }

    private boolean validTable() {
        return _table != null && _world.hasChunkAt(_table)
                && _world.getBlockState(_table).is(Blocks.CRAFTING_TABLE)
                && _player.position().distanceToSqr(Vec3.atCenterOf(_table)) <= 36;
    }

    private BlockPos findPlacement() {
        var choices = new ArrayList<BlockPos>();
        BlockPos origin = _player.blockPosition();
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            for (int dy = -1; dy <= 1; dy++) choices.add(origin.offset(dx, dy, dz));
        }
        choices.sort(Comparator.comparingDouble(pos -> _player.position().distanceToSqr(Vec3.atCenterOf(pos))));
        for (BlockPos candidate : choices) if (placementHit(candidate) != null) return candidate.immutable();
        return null;
    }

    private BlockHitResult placementHit(BlockPos target) {
        if (_world.isOutsideBuildHeight(target) || !_world.hasChunkAt(target)
                || !_world.hasChunkAt(target.below()) || !_world.getBlockState(target).isAir()) return null;
        var below = _world.getBlockState(target.below());
        if (!below.getFluidState().isEmpty() || !below.isCollisionShapeFullBlock(_world, target.below())
                || below.getMenuProvider(_world, target.below()) != null) return null;
        AABB box = new AABB(target);
        if (box.intersects(_player.getBoundingBox()) || !_world.noCollision(_player, box)
                || !_world.getEntities(_player, box, EntitySelector.NO_SPECTATORS).isEmpty()) return null;
        Vec3 eye = _player.getEyePosition();
        Vec3 top = new Vec3(target.getX() + .5, target.getY() - .001, target.getZ() + .5);
        if (eye.distanceToSqr(top) > Math.pow(_player.blockInteractionRange() - .15, 2)) return null;
        BlockHitResult hit = _world.clip(new ClipContext(eye, top,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, _player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getDirection() == Direction.UP
                && hit.getBlockPos().equals(target.below()) ? hit : null;
    }

    private void advance(AltoClef mod, Phase next) {
        stopChildren(mod);
        _recipe = null; _open = null;
        _phase = next; _phaseTicks = 0;
        restoreSlot();
    }

    private void stopChildren(AltoClef mod) {
        if (_recipe != null && _recipe.isActive()) _recipe.stop(mod);
        if (_open != null && _open.isActive()) _open.stop(mod);
    }

    private Task finish(AltoClef mod) {
        _done = true;
        stopChildren(mod);
        restoreSlot();
        closeOwnedMenu();
        return null;
    }

    private void closeOwnedMenu() {
        if (_player != null && _world != null && Minecraft.getInstance().player == _player
                && Minecraft.getInstance().level == _world
                && (_player.containerMenu == _player.inventoryMenu && _inventoryCraftOwned
                || _menuId >= 0 && _player.containerMenu.containerId == _menuId)) {
            // Vanilla returns cursor/input contents when this locally owned menu closes.
            _player.closeContainer();
        }
        _menuId = -1;
        _inventoryCraftOwned = false;
    }

    private boolean emptyGrid(boolean big) {
        if (!_player.containerMenu.getCarried().isEmpty()) return false;
        for (Slot slot : big ? CraftingTableSlot.INPUT_SLOTS : PlayerSlot.CRAFT_INPUT_SLOTS) {
            if (!StorageHelper.getItemStackInSlot(slot).isEmpty()) return false;
        }
        return true;
    }

    private void restoreSlot() {
        if (_player != null && _tableSlot >= 0 && _originalSlot >= 0
                && Minecraft.getInstance().player == _player && Minecraft.getInstance().level == _world
                && _player.getInventory().getSelectedSlot() == _tableSlot) {
            _player.getInventory().setSelectedSlot(_originalSlot);
        }
        _tableSlot = -1;
    }

    static int count(LocalPlayer player, Item item) {
        int total = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static boolean isRecipeInput(boolean big, int index) {
        return index >= 0 && index < (big ? 9 : 4)
                && (!big || index == 0 || index == 2 || index >= 3 && index <= 5);
    }

    @Override public boolean isFinished(AltoClef mod) {
        return _done || mod.getPlayer() != null && count(mod.getPlayer(), _boat) > 0;
    }
    @Override protected void onStop(AltoClef mod, Task interruptTask) { restoreSlot(); closeOwnedMenu(); }
    @Override protected boolean isEqual(Task other) {
        return other instanceof CarriedBoatCraftTask task && task._boat == _boat && task._planks == _planks;
    }
    @Override protected String toDebugString() { return "Crafting one boat from carried materials"; }

    /** Explicit inventory-only slot movement; each recipe input is exactly one plank. */
    private static final class RecipeTask extends Task implements ITaskUsesCraftingGrid {
        private final Item output, planks;
        private final boolean big;
        private MoveItemToSlotFromInventoryTask move;
        private boolean failed;
        RecipeTask(Item output, Item planks, boolean big) { this.output = output; this.planks = planks; this.big = big; }
        boolean failed() { return failed; }
        @Override protected void onStart(AltoClef mod) { }
        @Override protected Task onTick(AltoClef mod) {
            var player = mod.getPlayer();
            if (failed || player == null || big != StorageHelper.isBigCraftingOpen()) return null;
            if (move != null) {
                if (!move.isFinished(mod)) return move;
                if (move.isActive()) move.stop(mod);
                move = null;
                return null;
            }
            ItemStack cursor = player.containerMenu.getCarried();
            if (!cursor.isEmpty()) {
                var fit = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false);
                if (fit.isEmpty()) { failed = true; return null; }
                mod.getSlotHandler().handleInventoryMouseClick(fit.get(), 0, ContainerInput.PICKUP);
                return null;
            }
            int length = big ? 9 : 4;
            for (int index = 0; index < length; index++) {
                Slot slot = big ? CraftingTableSlot.getInputSlot(index, true) : PlayerSlot.getCraftInputSlot(index);
                ItemStack present = StorageHelper.getItemStackInSlot(slot);
                boolean needed = isRecipeInput(big, index);
                if (!present.isEmpty() && (!needed || !present.is(planks) || present.getCount() != 1)) {
                    mod.getSlotHandler().handleInventoryMouseClick(slot, 0, ContainerInput.QUICK_MOVE);
                    return null;
                }
                if (needed && present.isEmpty()) {
                    if (count(player, planks) == 0) { failed = true; return null; }
                    move = new MoveItemToSlotFromInventoryTask(new ItemTarget(planks, 1), slot);
                    return move;
                }
            }
            Slot slot = big ? CraftingTableSlot.OUTPUT_SLOT : PlayerSlot.CRAFT_OUTPUT_SLOT;
            if (StorageHelper.getItemStackInSlot(slot).is(output)) {
                mod.getSlotHandler().handleInventoryMouseClick(slot, 0, ContainerInput.QUICK_MOVE);
            }
            return null;
        }
        @Override protected void onStop(AltoClef mod, Task interruptTask) { }
        @Override protected boolean isEqual(Task other) {
            return other instanceof RecipeTask task && task.output == output && task.planks == planks && task.big == big;
        }
        @Override protected String toDebugString() { return "Filling one carried-material recipe"; }
    }
}
