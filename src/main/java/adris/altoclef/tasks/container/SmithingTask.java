package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.resources.GetSmithingTemplateTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.NativeSmithingRecipes;
import adris.altoclef.util.helpers.SmithingInventoryHelper;
import adris.altoclef.util.helpers.SmithingTaskOutcome;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.util.slots.SmithingTableSlot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.trim.ArmorTrim;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Native three-input smithing with component-aware goals and bounded result acknowledgements. */
public final class SmithingTask extends Task {
    private final Item base, template, addition, output;
    private final int targetCount;
    private final boolean trim;
    // Container navigation starts only in a live execution; command/model
    // validation may construct an equivalent request before a world is available.
    private TableTask table;
    private LocalPlayer player;
    private ClientLevel world;
    private Task acquisition;
    private boolean done, success, closing, forged;
    private String result = "锻造尚未完成。";
    private int menuId = -1, pendingDestination = -1, readySince = -1, closingSince = -1, lastTick = Integer.MIN_VALUE;
    private PendingTake pending;
    private record PendingTake(ItemStack expected, int inventoryBefore, int[] inputsBefore, int stateId, int tick) { }

    private SmithingTask(Item base, Item template, Item addition, Item output, int count, boolean trim) {
        if (base == null || template == null || addition == null || output == null || count < 1 || count > 64)
            throw new IllegalArgumentException("锻造数量应为1到64，且装备、模板和材料必须有效。");
        this.base = base; this.template = template; this.addition = addition; this.output = output;
        this.targetCount = count; this.trim = trim;
    }

    public static SmithingTask upgrade(Item base, int count) {
        Item output = NativeSmithingRecipes.upgradeOutput(base);
        if (output == null) throw new IllegalArgumentException("该物品没有原版下界合金锻造升级配方。");
        return new SmithingTask(base, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, Items.NETHERITE_INGOT, output, count, false);
    }

    public static SmithingTask trim(Item armor, Item template, Item material, int count) {
        if (!NativeSmithingRecipes.isTrimBase(armor) || !NativeSmithingRecipes.isTrimTemplate(template)
                || material == null || material.getDefaultInstance().get(DataComponents.PROVIDES_TRIM_MATERIAL) == null)
            throw new IllegalArgumentException("纹饰需要可装饰的盔甲、纹饰模板和原版纹饰材料。");
        return new SmithingTask(armor, template, material, armor, count, true);
    }

    public static SmithingTask forExecution(Task current, SmithingTask requested) {
        return current instanceof SmithingTask actual && actual.equals(requested) ? actual : requested;
    }
    public boolean succeeded() { return done && success; }
    public String getResult() { return result; }
    public static String status(AltoClef mod) {
        if (mod == null || mod.getPlayer() == null) return "未进入世界。";
        return "锻造台界面=" + (mod.getPlayer().containerMenu instanceof SmithingMenu)
                + "；下界合金升级消耗模板、钻石装备和下界合金锭；纹饰按模板和材料匹配组件。";
    }

    @Override protected void onStart(AltoClef mod) {
        if (player == null) { player = mod.getPlayer(); world = mod.level(); }
        mod.getBehaviour().push();
        mod.getBehaviour().addProtectedItems(base, template, addition, output);
        lastTick = Integer.MIN_VALUE;
    }

    @Override protected Task onTick(AltoClef mod) {
        if (done) return null;
        if (player == null || !player.isAlive() || mod.getPlayer() != player || mod.level() != world)
            return finish(false, "锻造停止：玩家或世界已变化。");
        if (lastTick == player.tickCount) return table != null && table.isActive() ? table : acquisition;
        lastTick = player.tickCount;

        if (pending != null) return awaitTake(mod);
        // A resource/crafting child owns its cursor and container until it has
        // completed. Reclaiming its cursor here interrupts the first ingredient
        // pickup before the child can put it into the crafting grid.
        if (acquisition != null) {
            Task child = tickAcquisition(mod);
            if (child != null || done) return child;
        }
        if (!player.containerMenu.getCarried().isEmpty()) {
            handleCursor(mod); return null;
        }
        if (closing) {
            if (!(player.containerMenu instanceof InventoryMenu)) { closeIfSafe(); return null; }
            if (countInventory(this::matchesOutput) >= targetCount && (!forged || countInventory(stack -> stack.is(template)) > 0))
                return finish(true, "锻造完成，已取得目标装备并保留一份模板。");
            // Client menus do not locally return ItemCombiner inputs: await the
            // server's inventory update instead of treating a predicted close as completion.
            if (player.tickCount - closingSince > 160)
                return finish(false, "关闭锻造台后未确认成品或保留模板回到背包，请检查物品。");
            setDebugState("等待服务器返还锻造台中的剩余模板和材料。");
            return null;
        }
        int haveOutput = countInventory(this::matchesOutput);
        if (haveOutput >= targetCount) {
            if (player.containerMenu instanceof SmithingMenu) {
                closing = true; closingSince = player.tickCount; closeIfSafe(); return null;
            }
            return finish(true, "目标装备已满足，无需重复消耗模板。");
        }

        int remaining = targetCount - haveOutput;
        int usableBases = countInventory(stack -> stack.is(base) && !matchesOutput(stack));
        int templateCount = countInventory(stack -> stack.is(template));
        int additionCount = countInventory(this::matchesAddition);
        if (player.containerMenu instanceof SmithingMenu menu) {
            usableBases += matchesBase(menu.getSlot(1).getItem()) ? menu.getSlot(1).getItem().getCount() : 0;
            templateCount += menu.getSlot(0).getItem().is(template) ? menu.getSlot(0).getItem().getCount() : 0;
            additionCount += matchesAddition(menu.getSlot(2).getItem()) ? menu.getSlot(2).getItem().getCount() : 0;
        }
        // One extra template is deliberately retained as the duplication seed.
        int templateStock = SmithingInventoryHelper.templateStockFor(remaining);
        if (usableBases < remaining || templateCount < templateStock || additionCount < remaining) {
            if (!(player.containerMenu instanceof InventoryMenu)) { closeIfSafe(); return null; }
            if (templateCount < templateStock) {
                acquisition = new GetSmithingTemplateTask(template, templateStock);
            } else if (usableBases < remaining) {
                int allBases = countInventory(stack -> stack.is(base));
                acquisition = TaskCatalogue.getItemTask(base, allBases + remaining - usableBases);
            } else {
                acquisition = TaskCatalogue.getItemTask(addition, remaining);
            }
            if (acquisition == null) return finish(false, "尚无所需装备或材料的自动获取任务，请先准备材料。");
            setDebugState("获取装备、锻造材料和可保留的模板种子。");
            return acquisition;
        }

        if (!hasMainInventoryBase()) {
            if (!(player.containerMenu instanceof InventoryMenu)) { closeIfSafe(); return null; }
            if (firstFittingInventorySlot(base.getDefaultInstance()) < 0)
                return finish(false, "背包没有卸下装备的空间；已停止，未丢弃物品。");
            for (int slot : new int[]{5, 6, 7, 8, 45}) {
                ItemStack stack = player.inventoryMenu.getSlot(slot).getItem();
                if (matchesBase(stack)) { click(mod, new PlayerSlot(slot), 0, ContainerInput.QUICK_MOVE); return null; }
            }
            return finish(false, "未找到可移入锻造台的目标装备。");
        }
        setDebugState("使用原版锻造台三输入槽。");
        if (table == null) table = new TableTask();
        return table;
    }

    private Task tickAcquisition(AltoClef mod) {
        if (!acquisition.isFinished(mod)) return acquisition;
        String acquisitionFailure = SmithingTaskOutcome.failure(acquisition, mod);
        if (acquisition.isActive()) acquisition.stop(mod);
        if (acquisitionFailure != null) return finish(false, acquisitionFailure);
        if (acquisition instanceof GetSmithingTemplateTask getting && !getting.succeeded())
            return finish(false, getting.getResult());
        acquisition = null;
        return null;
    }

    private Task awaitTake(AltoClef mod) {
        if (!(player.containerMenu instanceof SmithingMenu menu) || menu.containerId != menuId)
            return finish(false, "领取结果期间锻造界面发生变化，已停止重复点击。");
        if (!menu.getCarried().isEmpty()) { handleCursor(mod); return null; }
        int[] after = inputCounts(menu);
        boolean received = countInventory(stack -> ItemStack.isSameItemSameComponents(stack, pending.expected()))
                >= pending.inventoryBefore() + pending.expected().getCount();
        boolean consumed = SmithingInventoryHelper.consumedOnce(pending.inputsBefore(), after);
        int age = player.tickCount - pending.tick();
        // Result production itself is server-only in 26.3. A matching predicted
        // click need not receive a new state id, so also allow a stable quiet window.
        if (received && consumed && (menu.getStateId() != pending.stateId() || age >= 20)) {
            pending = null; readySince = -1; forged = true;
            setDebugState("已取得原生结果，输入各消耗一份。");
            return null;
        }
        if (age > 160) return finish(false, "锻造结果未能确认，停止重复领取；请检查界面和背包。");
        setDebugState("等待结果进入背包及输入消耗确认。");
        return null;
    }

    private boolean matchesOutput(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(output)) return false;
        if (!trim) return true;
        ArmorTrim decoration = stack.get(DataComponents.TRIM);
        return decoration != null && decoration.pattern().is(NativeSmithingRecipes.trimPattern(template))
                && Objects.equals(decoration.material(), addition.getDefaultInstance().get(DataComponents.PROVIDES_TRIM_MATERIAL));
    }
    private boolean matchesBase(ItemStack stack) { return !stack.isEmpty() && stack.is(base) && !matchesOutput(stack); }
    private boolean matchesAddition(ItemStack stack) {
        return !stack.isEmpty() && stack.is(addition) && (!trim || Objects.equals(stack.get(DataComponents.PROVIDES_TRIM_MATERIAL),
                addition.getDefaultInstance().get(DataComponents.PROVIDES_TRIM_MATERIAL)));
    }
    private int countInventory(Predicate<ItemStack> predicate) {
        int result = 0;
        for (int index = 0; index < player.getInventory().getContainerSize(); index++) {
            ItemStack stack = player.getInventory().getItem(index);
            if (predicate.test(stack)) result += stack.getCount();
        }
        return result;
    }
    private boolean hasMainInventoryBase() {
        for (int index = 0; index < 36; index++) if (matchesBase(player.getInventory().getItem(index))) return true;
        return player.containerMenu instanceof SmithingMenu menu && matchesBase(menu.getSlot(1).getItem());
    }
    private List<ItemStack> inventorySnapshot() {
        List<ItemStack> contents = new ArrayList<>();
        for (int index = 0; index < 36; index++) contents.add(player.getInventory().getItem(index));
        return contents;
    }
    private int firstFittingInventorySlot(ItemStack carried) {
        for (int index = 0; index < 36; index++) {
            ItemStack at = player.getInventory().getItem(index);
            if (at.isEmpty() || ItemStack.isSameItemSameComponents(at, carried)
                    && at.getMaxStackSize() - at.getCount() >= carried.getCount()) return index;
        }
        return -1;
    }
    private void handleCursor(AltoClef mod) {
        ItemStack cursor = player.containerMenu.getCarried();
        if (cursor.isEmpty()) return;
        if (pendingDestination >= 0 && player.containerMenu instanceof SmithingMenu menu && menu.containerId == menuId) {
            ItemStack destination = menu.getSlot(pendingDestination).getItem();
            boolean rightType = pendingDestination == 0 ? cursor.is(template)
                    : pendingDestination == 1 ? matchesBase(cursor) : matchesAddition(cursor);
            if (rightType && (destination.isEmpty() || ItemStack.isSameItemSameComponents(destination, cursor))) {
                if (click(mod, new SmithingTableSlot(pendingDestination), pendingDestination == 1 ? 1 : 0, ContainerInput.PICKUP))
                    pendingDestination = -1;
                return;
            }
        }
        pendingDestination = -1;
        int fit = firstFittingInventorySlot(cursor);
        if (fit < 0) { finish(false, "游标物品无法安全放回背包，已停止并保留物品。"); return; }
        click(mod, Slot.getFromCurrentScreenInventory(fit), 0, ContainerInput.PICKUP);
    }
    private boolean closeIfSafe() {
        if (player == null || player.containerMenu instanceof InventoryMenu) return true;
        List<ItemStack> returning = new ArrayList<>();
        returning.add(player.containerMenu.getCarried());
        if (player.containerMenu instanceof SmithingMenu menu)
            for (int slot = 0; slot < 3; slot++) returning.add(menu.getSlot(slot).getItem());
        if (!SmithingInventoryHelper.canFit(inventorySnapshot(), returning)) {
            finish(false, "背包空间不足以收回锻造输入，已保留界面物品，未自动丢弃。");
            return false;
        }
        player.closeContainer(); menuId = -1; pendingDestination = -1; readySince = -1;
        return true;
    }
    private Task finish(boolean succeeded, String text) {
        done = true; success = succeeded; result = text; setDebugState(text); return null;
    }
    private boolean click(AltoClef mod, Slot slot, int button, ContainerInput operation) {
        if (!mod.getSlotHandler().canDoSlotAction()) return false;
        // SlotHandler performs its own timing check; use the native controller once
        // after registering this allowed action, rather than consuming a one-shot gate twice.
        mod.getSlotHandler().registerSlotAction();
        mod.getController().handleContainerInput(player.containerMenu.containerId, slot.getWindowSlot(), button, operation, player);
        return true;
    }
    private static int[] inputCounts(SmithingMenu menu) {
        return new int[]{menu.getSlot(0).getItem().getCount(), menu.getSlot(1).getItem().getCount(), menu.getSlot(2).getItem().getCount()};
    }
    @Override public boolean isFinished(AltoClef mod) { return done; }
    @Override protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
        if (player == mod.getPlayer() && world == mod.level() && player != null
                && player.containerMenu instanceof SmithingMenu menu && menu.containerId == menuId) closeIfSafe();
        pending = null;
    }
    @Override protected boolean isEqual(Task other) {
        return other instanceof SmithingTask task && task.base == base && task.template == template
                && task.addition == addition && task.output == output && task.targetCount == targetCount && task.trim == trim;
    }
    @Override protected String toDebugString() { return (trim ? "Trimming " : "Upgrading ") + base + " x" + targetCount; }

    private final class TableTask extends DoStuffInContainerTask {
        TableTask() { super(Blocks.SMITHING_TABLE, new ItemTarget("smithing_table")); }
        @Override protected boolean isSubTaskEqual(DoStuffInContainerTask other) { return other instanceof SmithingTask.TableTask; }
        @Override protected boolean isContainerOpen(AltoClef mod) { return player.containerMenu instanceof SmithingMenu; }
        @Override protected double getCostToMakeNew(AltoClef mod) { return 600; }
        @Override protected Task containerSubTask(AltoClef mod) {
            SmithingMenu menu = (SmithingMenu)player.containerMenu;
            menuId = menu.containerId;
            if (pending != null) return null;
            if (!menu.getCarried().isEmpty()) { handleCursor(mod); return null; }
            Predicate<ItemStack>[] expected = new Predicate[]{(Predicate<ItemStack>) stack -> stack.is(template),
                    (Predicate<ItemStack>) SmithingTask.this::matchesBase, (Predicate<ItemStack>) SmithingTask.this::matchesAddition};
            for (int index = 0; index < 3; index++) {
                ItemStack current = menu.getSlot(index).getItem();
                if (!current.isEmpty() && !expected[index].test(current)) {
                    if (!SmithingInventoryHelper.canFit(inventorySnapshot(), List.of(current))) {
                        finish(false, "背包不能安全收回锻造槽中的原物品。"); return null;
                    }
                    click(mod, new SmithingTableSlot(index), 0, ContainerInput.QUICK_MOVE); readySince = -1; return null;
                }
                if (current.isEmpty()) {
                    for (int inventory = 0; inventory < 36; inventory++) {
                        if (!expected[index].test(player.getInventory().getItem(inventory))) continue;
                        if (click(mod, Slot.getFromCurrentScreenInventory(inventory), 0, ContainerInput.PICKUP)) pendingDestination = index;
                        readySince = -1; return null;
                    }
                    return null;
                }
            }
            if (readySince < 0) readySince = player.tickCount;
            ItemStack calculated = NativeSmithingRecipes.preview(menu.getSlot(1).getItem(), template,
                    menu.getSlot(2).getItem(), world.registryAccess());
            ItemStack actual = menu.getSlot(3).getItem();
            if (calculated.isEmpty()) { finish(false, "此组合没有可执行的原版锻造变化，已停止消耗。"); return null; }
            if (!actual.isEmpty() && matchesOutput(actual) && ItemStack.isSameItemSameComponents(calculated, actual)) {
                if (!SmithingInventoryHelper.canFit(inventorySnapshot(), List.of(actual))) {
                    finish(false, "背包没有锻造成品的位置。"); return null;
                }
                PendingTake observation = new PendingTake(actual.copy(), countInventory(stack -> ItemStack.isSameItemSameComponents(stack, actual)),
                        inputCounts(menu), menu.getStateId(), player.tickCount);
                if (click(mod, SmithingTableSlot.OUTPUT_SLOT, 0, ContainerInput.PICKUP)) pending = observation;
            } else if (player.tickCount - readySince > 120) {
                finish(false, "服务器未提供匹配组件的锻造结果，已停止重复操作。");
            }
            return null;
        }
    }
}
