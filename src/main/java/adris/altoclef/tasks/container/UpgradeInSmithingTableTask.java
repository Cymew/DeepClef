package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.NativeSmithingRecipes;
import adris.altoclef.util.helpers.SmithingInventoryHelper;
import net.minecraft.world.item.Items;

/** Catalogue-compatible resource facade over the component-aware native smithing task. */
public final class UpgradeInSmithingTableTask extends ResourceTask {
    private final ItemTarget tool, material, output;
    private final SmithingTask operation;

    public UpgradeInSmithingTableTask(ItemTarget tool, ItemTarget material, ItemTarget output) {
        super(output);
        if (tool.getMatches().length != 1 || output.getMatches().length != 1
                || NativeSmithingRecipes.upgradeOutput(tool.getMatches()[0]) != output.getMatches()[0]
                || !material.matches(Items.NETHERITE_INGOT))
            throw new IllegalArgumentException("No matching native netherite smithing recipe");
        this.tool = new ItemTarget(tool, output.getTargetCount());
        this.material = new ItemTarget(material, output.getTargetCount());
        this.output = output;
        operation = SmithingTask.upgrade(tool.getMatches()[0], output.getTargetCount());
    }

    public ItemTarget getTools() { return tool; }
    public ItemTarget getMaterials() { return material; }
    public ItemTarget getTemplates() { return new ItemTarget(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE,
            SmithingInventoryHelper.templateStockFor(output.getTargetCount())); }
    public boolean succeeded() { return operation.succeeded(); }
    public String getResult() { return operation.getResult(); }
    @Override public boolean isFinished(AltoClef mod) { return operation.isFinished(mod); }
    @Override protected boolean shouldAvoidPickingUp(AltoClef mod) { return true; }
    @Override protected void onResourceStart(AltoClef mod) { }
    // The generic ResourceTask cursor fallback can discard a stack when full.
    // Smithing owns its exact stacks and handles all movement/capacity itself.
    @Override protected Task onTick(AltoClef mod) { return onResourceTick(mod); }
    @Override protected Task onResourceTick(AltoClef mod) { return operation; }
    @Override protected void onResourceStop(AltoClef mod, Task interruptTask) { }
    @Override protected boolean isEqualResource(ResourceTask other) {
        return other instanceof UpgradeInSmithingTableTask task && task.tool.equals(tool)
                && task.material.equals(material) && task.output.equals(output);
    }
    @Override protected String toDebugStringName() { return "Upgrading with native smithing " + tool; }
}
