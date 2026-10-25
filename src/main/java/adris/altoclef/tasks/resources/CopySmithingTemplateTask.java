package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.container.DoStuffInContainerTask;
import adris.altoclef.tasksystem.ITaskUsesCraftingGrid;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.JankCraftingRecipeMapping;
import adris.altoclef.util.helpers.SmithingTemplateCatalogue;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.level.block.Blocks;

import java.util.Optional;

/** One native 3x3 duplication cycle, with server acknowledgements and no discard clicks. */
final class CopySmithingTemplateTask extends DoStuffInContainerTask implements ITaskUsesCraftingGrid {
    private final SmithingTemplateCatalogue.Entry entry;
    private final int targetCount;
    private int waitingState = -1, sentTick, outputTick = -1, outputState = -1, readySince = -1;
    private int pendingSlot;
    private int menuId = -1, recipeRequestTick = -1;
    private ItemStack predictedSlot = ItemStack.EMPTY, predictedCursor = ItemStack.EMPTY;
    private boolean done;
    private String failure;
    CopySmithingTemplateTask(SmithingTemplateCatalogue.Entry entry, int targetCount) {
        super(Blocks.CRAFTING_TABLE, new ItemTarget("crafting_table"));
        this.entry = entry; this.targetCount = targetCount;
    }
    String getFailure() { return failure; }
    @Override protected Task onTick(AltoClef mod) {
        if (done || failure != null) return null;
        if (menuId >= 0 && (!(mod.getPlayer().containerMenu instanceof CraftingMenu)
                || mod.getPlayer().containerMenu.containerId != menuId)) {
            failure = "复制期间工作台界面已变化，已停止后续操作；请重试。";
            return null;
        }
        if (!(mod.getPlayer().containerMenu instanceof CraftingMenu) && !mod.getPlayer().containerMenu.getCarried().isEmpty()) {
            var fit = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(mod.getPlayer().containerMenu.getCarried(), false);
            if (fit.isPresent()) mod.getSlotHandler().handleInventoryMouseClick(fit.get(), 0, ContainerInput.PICKUP);
            else failure = "背包已满，已保留游标物品；请腾出空间后重试模板复制。";
            return null;
        }
        if (!(mod.getPlayer().containerMenu instanceof CraftingMenu) && mod.getPlayer().containerMenu != mod.getPlayer().inventoryMenu) {
            StorageHelper.closeScreen(); return null;
        }
        return super.onTick(mod);
    }
    @Override protected Task containerSubTask(AltoClef mod) {
        CraftingMenu menu = (CraftingMenu)mod.getPlayer().containerMenu;
        if (menuId < 0) menuId = menu.containerId;
        int tick = mod.getPlayer().tickCount;
        if (waitingState >= 0) {
            // Correct native predictions can produce no slot packet/state-id change.
            // Require a quiet interval with the actual expected cursor/slot state;
            // server corrections are observed before the next dependent click.
            boolean expected = same(menu.getSlot(pendingSlot).getItem(), predictedSlot) && same(menu.getCarried(), predictedCursor);
            if (tick - sentTick < 4) return null;
            if (!expected && menu.getStateId() == waitingState) {
                if (tick - sentTick > 100) failure = "模板复制操作未稳定，已保留工作台物品，请重试。";
                return null;
            }
            waitingState = -1;
        }
        if (outputTick >= 0) {
            int received = mod.getPlayer().getInventory().getNonEquipmentItems().stream()
                    .filter(stack -> stack.is(entry.item())).mapToInt(ItemStack::getCount).sum();
            boolean consumed = true;
            for (int index = 1; index <= 9; index++) consumed &= menu.getSlot(index).getItem().isEmpty();
            if (tick - outputTick >= 4 && (menu.getStateId() != outputState || tick - outputTick >= 20)
                    && consumed && menu.getCarried().isEmpty() && received >= targetCount) done = true;
            else if (tick - outputTick > 60) failure = "模板复制结果未进入背包；已保留工作台物品。";
            return null;
        }
        var recipe = SmithingTemplateCatalogue.duplicationRecipe(entry);
        if (recipeRequestTick >= 0) {
            // Recipe placement is server-side. Do not send the request again:
            // placing an already filled recipe would increment its craft count.
            boolean filled = true;
            for (int index = 0; index < 9; index++) {
                ItemStack input = menu.getSlot(index + 1).getItem();
                filled &= input.getCount() == 1 && recipe.getSlot(index).matches(input.getItem());
            }
            if (!filled) {
                if (tick - recipeRequestTick > 120)
                    failure = "服务器未按配方放入模板复制材料，已停止重复请求并保留物品。";
                return null;
            }
        }
        ItemStack cursor = menu.getCarried();
        if (!cursor.isEmpty()) {
            // A right-click deposits exactly one ingredient. The remaining stack
            // stays in the cursor until another valid slot or inventory can take it.
            for (int index = 0; index < 9; index++) {
                ItemTarget desired = recipe.getSlot(index);
                if (desired.matches(cursor.getItem()) && menu.getSlot(index + 1).getItem().isEmpty()) {
                    click(mod, menu, index + 1, 1, ContainerInput.PICKUP); return null;
                }
            }
            var fit = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false);
            if (fit.isPresent()) click(mod, menu, fit.get().getWindowSlot(), 0, ContainerInput.PICKUP);
            else failure = "背包已满，已保留模板/钻石/底材，不会丢弃游标物品。";
            return null;
        }
        if (recipeRequestTick < 0) {
            boolean empty = true;
            for (int index = 1; index <= 9; index++) empty &= menu.getSlot(index).getItem().isEmpty();
            Optional<RecipeDisplayId> mapped = empty
                    ? JankCraftingRecipeMapping.getMinecraftMappedRecipe(recipe, entry.item()) : Optional.empty();
            if (mapped.isPresent()) {
                if (!mod.getSlotHandler().canDoSlotAction()) return null;
                mod.getSlotHandler().registerSlotAction();
                // Reuse the existing crafting recipe mapping/controller route.
                // false requests one craft, yielding two templates (net +1).
                mod.getController().handlePlaceRecipe(menu.containerId, mapped.get(), false);
                recipeRequestTick = tick;
                setDebugState("按已登记的复制配方请求一次工作台合成。");
                return null;
            }
        }
        for (int index = 0; index < 9; index++) {
            ItemTarget desired = recipe.getSlot(index);
            ItemStack present = menu.getSlot(index + 1).getItem();
            if (!present.isEmpty() && (!desired.matches(present.getItem()) || present.getCount() != 1)) {
                if (mod.getItemStorage().getSlotThatCanFitInPlayerInventory(present, false).isEmpty()) {
                    failure = "背包无法容纳工作台中原有的物品，已停止复制并保留物品。"; return null;
                }
                click(mod, menu, index + 1, 0, ContainerInput.QUICK_MOVE); return null;
            }
            if (present.isEmpty()) {
                for (int source = 10; source < menu.slots.size(); source++) {
                    if (desired.matches(menu.getSlot(source).getItem().getItem())) {
                        click(mod, menu, source, 0, ContainerInput.PICKUP); return null;
                    }
                }
                failure = "模板复制缺少种子、7 颗钻石或指定底材，已保留当前工作台物品。"; return null;
            }
        }
        ItemStack output = menu.getSlot(0).getItem();
        if (!output.is(entry.item()) || output.getCount() != 2) {
            if (readySince < 0) readySince = tick;
            if (tick - readySince > 100) failure = "原版工作台未返回预期的 2 个模板，已保留种子与材料。";
            return null;
        }
        if (mod.getItemStorage().getSlotThatCanFitInPlayerInventory(output, false).isEmpty()) {
            failure = "背包没有足够空间接收 2 个模板，已保留种子与材料。"; return null;
        }
        int priorState = menu.getStateId();
        if (click(mod, menu, 0, 0, ContainerInput.QUICK_MOVE)) { outputTick = tick; outputState = priorState; }
        return null;
    }
    private static boolean same(ItemStack one, ItemStack two) { return one.getCount() == two.getCount() && ItemStack.isSameItemSameComponents(one, two); }
    private boolean click(AltoClef mod, CraftingMenu menu, int slot, int button, ContainerInput kind) {
        if (!mod.getSlotHandler().canDoSlotAction()) return false;
        waitingState = menu.getStateId(); sentTick = mod.getPlayer().tickCount;
        mod.getSlotHandler().registerSlotAction();
        mod.getController().handleContainerInput(menu.containerId, slot, button, kind, mod.getPlayer());
        pendingSlot = slot; predictedSlot = menu.getSlot(slot).getItem().copy(); predictedCursor = menu.getCarried().copy();
        return true;
    }
    @Override public boolean isFinished(AltoClef mod) { return done || failure != null; }
    @Override protected boolean isContainerOpen(AltoClef mod) { return mod.getPlayer().containerMenu instanceof CraftingMenu; }
    @Override protected boolean isSubTaskEqual(DoStuffInContainerTask other) { return other instanceof CopySmithingTemplateTask task && task.entry.item() == entry.item() && task.targetCount == targetCount; }
    @Override protected double getCostToMakeNew(AltoClef mod) { return 50; }
    @Override protected void onStop(AltoClef mod, Task interruptTask) {
        // Keep failed/interrupted crafting contents intact, including a full cursor.
        if (done && mod.getPlayer().containerMenu.getCarried().isEmpty()) StorageHelper.closeScreen();
        super.onStop(mod, interruptTask);
    }
}
