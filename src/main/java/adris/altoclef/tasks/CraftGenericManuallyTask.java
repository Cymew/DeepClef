package adris.altoclef.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.slot.ReceiveCraftingOutputSlotTask;
import adris.altoclef.tasksystem.ITaskUsesCraftingGrid;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.CraftingTableSlot;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/** Fill the inventory or table grid when the recipe book cannot place a known recipe. */
public class CraftGenericManuallyTask extends Task implements ITaskUsesCraftingGrid {
    private final RecipeTarget _target;

    public CraftGenericManuallyTask(RecipeTarget target) {
        _target = target;
    }

    @Override
    protected void onStart(AltoClef mod) {}

    static int batchSize(int target, int owned, int outputPerCraft, int stackLimit) {
        if (owned >= target) return 0;
        return Math.min(stackLimit, 1 + (target - owned - 1) / outputPerCraft);
    }

    static boolean canFill(ItemTarget ingredient, ItemStack source, ItemStack inGrid) {
        return !source.isEmpty() && ingredient.matches(source.getItem())
                && (inGrid.isEmpty() || ItemStack.isSameItemSameComponents(source, inGrid));
    }

    private void parkCursor(AltoClef mod, ItemStack cursor) {
        var destination = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, true);
        if (destination.isPresent()) {
            mod.getSlotHandler().handleInventoryMouseClick(destination.get(), 0, ContainerInput.PICKUP);
        } else {
            setDebugState("Waiting for inventory space for the cursor stack");
        }
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (!mod.getSlotHandler().canDoSlotAction()) return null;
        boolean table = StorageHelper.isBigCraftingOpen();
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        if (!table && !StorageHelper.isPlayerInventoryOpen()) {
            if (cursor.isEmpty()) StorageHelper.closeScreen();
            else parkCursor(mod, cursor);
            return null;
        }

        var recipe = _target.getRecipe();
        int owned = mod.getItemStorage().getItemCountInventoryOnly(_target.getOutputItem());
        if (owned >= _target.getTargetCount()) return null;
        for (int i = 0; i < recipe.getSlotCount(); i++) {
            Slot slot = table ? CraftingTableSlot.getInputSlot(i, recipe.isBig()) : PlayerSlot.getCraftInputSlot(i);
            ItemTarget ingredient = recipe.getSlot(i);
            ItemStack present = StorageHelper.getItemStackInSlot(slot);
            if (!present.isEmpty() && !ingredient.matches(present.getItem())) {
                if (!cursor.isEmpty()) parkCursor(mod, cursor);
                else mod.getSlotHandler().handleInventoryMouseClick(slot, 0, ContainerInput.QUICK_MOVE);
                return null;
            }
            if (ingredient.isEmpty()) continue;
            if (!present.isEmpty() && present.getCount() >= batchSize(_target.getTargetCount(), owned, recipe.outputCount(), present.getMaxStackSize())) continue;

            if (canFill(ingredient, cursor, present)) {
                int wanted = batchSize(_target.getTargetCount(), owned, recipe.outputCount(), cursor.getMaxStackSize());
                int needed = wanted - present.getCount();
                int button = cursor.getCount() <= needed || wanted == cursor.getMaxStackSize() ? 0 : 1;
                mod.getSlotHandler().handleInventoryMouseClick(slot, button, ContainerInput.PICKUP);
                return null;
            }
            for (Slot source : mod.getItemStorage().getSlotsWithItemPlayerInventory(false, ingredient.getMatches())) {
                if (Slot.isCursor(source) || !canFill(ingredient, StorageHelper.getItemStackInSlot(source), present)) continue;
                if (!cursor.isEmpty()) parkCursor(mod, cursor);
                else mod.getSlotHandler().handleInventoryMouseClick(source, 0, ContainerInput.PICKUP);
                return null;
            }
            if (present.isEmpty()) {
                setDebugState("Waiting for recipe ingredient " + ingredient);
                return null;
            }
            // A partial stack already fills this cell. Other ingredient variants can go in the next batch.
        }

        if (!cursor.isEmpty()) {
            parkCursor(mod, cursor);
            return null;
        }
        Slot output = table ? CraftingTableSlot.OUTPUT_SLOT : PlayerSlot.CRAFT_OUTPUT_SLOT;
        if (StorageHelper.getItemStackInSlot(output).is(_target.getOutputItem()))
            return new ReceiveCraftingOutputSlotTask(output, _target.getTargetCount());
        setDebugState("Waiting for the requested crafting output");
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof CraftGenericManuallyTask task) {
            return task._target.equals(_target);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Crafting: " + _target;
    }
}
