package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.SmithingTemplateCatalogue;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Gets an actual first template, then duplicates one seed at a time through native crafting. */
public class GetSmithingTemplateTask extends ResourceTask {
    private final Item item;
    private final int count;
    private final boolean duplicateOnly;
    private Task operation;
    private AcquireSmithingTemplateSourceTask source;
    private boolean success;
    private String failure;
    public GetSmithingTemplateTask(int count) { this(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, count); }
    public GetSmithingTemplateTask(Item item, int count) { this(item, count, false); }
    GetSmithingTemplateTask(Item item, int count, boolean duplicateOnly) {
        super(item, count);
        if (SmithingTemplateCatalogue.byItem(item) == null || count < 1 || count > 2304)
            throw new IllegalArgumentException("Invalid smithing template or target count");
        this.item = item; this.count = count; this.duplicateOnly = duplicateOnly;
    }
    public static DuplicateSmithingTemplateTask duplicate(Item item, int copies) { return new DuplicateSmithingTemplateTask(item, copies); }
    public boolean succeeded() { return success && failure == null; }
    public String getResult() { return failure != null ? failure : success ? "已获取指定数量的锻造模板。" : "锻造模板尚未获取完成。"; }
    @Override public boolean isFinished(AltoClef mod) {
        if (failure != null) return true;
        if (operation instanceof AcquireSmithingTemplateSourceTask site && !site.isFinished(mod)) return false;
        if (operation instanceof CopySmithingTemplateTask copy && !copy.isFinished(mod)) return false;
        success = mod != null && super.isFinished(mod);
        return success;
    }
    @Override protected void onResourceStart(AltoClef mod) {
        mod.getBehaviour().addProtectedItems(item, Items.DIAMOND);
        mod.getBehaviour().addProtectedItems(SmithingTemplateCatalogue.byItem(item).duplicationMaterials());
    }
    @Override protected Task onTick(AltoClef mod) {
        // The legacy ResourceTask cursor fallback can discard its target when
        // full. Native template copying owns its cursor and never takes that path.
        if (operation instanceof CopySmithingTemplateTask) return onResourceTick(mod);
        if (operation instanceof AcquireSmithingTemplateSourceTask site && site.awaitingChestConfirmation(mod)) return onResourceTick(mod);
        var cursor = StorageHelper.getItemStackInCursorSlot();
        if (!cursor.isEmpty() && cursor.is(item)) {
            var fit = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false);
            if (fit.isPresent()) mod.getSlotHandler().handleInventoryMouseClick(fit.get(), 0, ContainerInput.PICKUP);
            else failure = "背包已满，已保留游标中的模板；腾出空间后可继续。";
            return null;
        }
        return super.onTick(mod);
    }
    @Override protected Task onResourceTick(AltoClef mod) {
        if (operation != null) {
            if (!operation.isFinished(mod)) return operation;
            if (operation.isActive()) operation.stop(mod);
            if (operation instanceof AcquireSmithingTemplateSourceTask world && world.getFailure() != null) {
                failure = world.getFailure(); return null;
            }
            if (operation instanceof CopySmithingTemplateTask copy && copy.getFailure() != null) {
                failure = copy.getFailure(); return null;
            }
            operation = null;
        }
        var entry = SmithingTemplateCatalogue.byItem(item);
        int current = mod.getItemStorage().getItemCountInventoryOnly(item);
        if (current > 0) {
            if (current >= count) return null;
            // One seed is consumed and two returned: NET +1. Request exactly one
            // cycle without asking the resource collector to acquire its own seed.
            if (mod.getItemStorage().getItemCountInventoryOnly(Items.DIAMOND) < 7) {
                setDebugState("Collecting seven diamonds for one template copy.");
                return TaskCatalogue.getItemTask(Items.DIAMOND, 7);
            }
            if (!mod.getItemStorage().hasItemInventoryOnly(entry.duplicationMaterials())) {
                setDebugState("Collecting the template's actual duplication material.");
                return TaskCatalogue.getItemTask(new ItemTarget(entry.duplicationMaterials(), 1));
            }
            operation = new CopySmithingTemplateTask(entry, current + 1);
            setDebugState("Duplicating one seed through the crafting table (net +1).");
            return operation;
        }
        if (duplicateOnly) { failure = "复制需要先持有至少一个同种模板，不能凭空合成首份模板。"; return null; }
        if (WorldHelper.getCurrentDimension() != entry.dimension()) {
            setDebugState("Travelling to the template's source dimension.");
            return new DefaultGoToDimensionTask(entry.dimension());
        }
        if (source == null) source = new AcquireSmithingTemplateSourceTask(entry);
        operation = source;
        setDebugState(entry.sourceDescription());
        return source;
    }
    @Override protected void onResourceStop(AltoClef mod, Task interruptTask) { }
    @Override protected boolean isEqualResource(ResourceTask other) {
        return other instanceof GetSmithingTemplateTask task && task.item == item && task.duplicateOnly == duplicateOnly;
    }
    @Override protected String toDebugStringName() { return "Get smithing template " + item + " x" + count; }
    @Override protected boolean shouldAvoidPickingUp(AltoClef mod) { return false; }
}
