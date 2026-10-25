package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.Slot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;

/** Opens one identified chest and shift-moves only the requested template, without destroying the container. */
final class TemplateChestLootTask extends Task {
    private final BlockPos target;
    private final Item item;
    private boolean done;
    private int menuId = -1, openedTick, clickedTick = -1, beforeCount, sentState, sentSlot;
    TemplateChestLootTask(BlockPos target, Item item) { this.target = target.immutable(); this.item = item; }
    @Override protected void onStart(AltoClef mod) { }
    @Override protected Task onTick(AltoClef mod) {
        if (done) return null;
        if (!(mod.getPlayer().containerMenu instanceof ChestMenu menu)
                || !mod.getItemStorage().getLastBlockPosInteraction().filter(target::equals).isPresent()) {
            if (mod.getPlayer().containerMenu != mod.getPlayer().inventoryMenu) {
                if (!mod.getPlayer().containerMenu.getCarried().isEmpty()) return null;
                StorageHelper.closeScreen(); return null;
            }
            return new InteractWithBlockTask(target);
        }
        int tick = mod.getPlayer().tickCount;
        if (menuId != menu.containerId) { menuId = menu.containerId; openedTick = tick; }
        if (!menu.getCarried().isEmpty() || tick - openedTick < 4) return null;
        if (clickedTick >= 0) {
            if (tick - clickedTick >= 6
                    && (menu.getStateId() != sentState || menu.getSlot(sentSlot).getItem().isEmpty())
                    && mod.getItemStorage().getItemCountInventoryOnly(item) > beforeCount) { done = true; return null; }
            if (tick - clickedTick > 60) { StorageHelper.closeScreen(); done = true; }
            return null;
        }
        for (int slot = 0; slot < menu.getRowCount() * 9; slot++) {
            if (!menu.getSlot(slot).getItem().is(item)) continue;
            if (mod.getItemStorage().getSlotThatCanFitInPlayerInventory(menu.getSlot(slot).getItem(), false).isEmpty()) {
                setDebugState("Waiting for inventory room before collecting the template."); return null;
            }
            if (!mod.getSlotHandler().canDoSlotAction()) return null;
            beforeCount = mod.getItemStorage().getItemCountInventoryOnly(item);
            clickedTick = tick; sentState = menu.getStateId(); sentSlot = slot;
            mod.getSlotHandler().registerSlotAction();
            mod.getController().handleContainerInput(menu.containerId, slot, 0, ContainerInput.QUICK_MOVE, mod.getPlayer());
            return null;
        }
        done = true;
        return null;
    }
    @Override protected void onStop(AltoClef mod, Task interruptTask) {
        if (mod.getPlayer() != null && mod.getPlayer().containerMenu instanceof ChestMenu
                && mod.getItemStorage().getLastBlockPosInteraction().filter(target::equals).isPresent()
                && mod.getPlayer().containerMenu.getCarried().isEmpty()) StorageHelper.closeScreen();
    }
    @Override public boolean isFinished(AltoClef mod) { return done; }
    @Override protected boolean isEqual(Task other) { return other instanceof TemplateChestLootTask task && task.target.equals(target) && task.item == item; }
    @Override protected String toDebugString() { return "Inspect template chest " + target.toShortString(); }
}
