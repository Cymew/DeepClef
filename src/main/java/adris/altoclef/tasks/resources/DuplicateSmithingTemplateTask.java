package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.SmithingTemplateCatalogue;
import net.minecraft.world.item.Item;

/** Explicit copy counts are net additions locked when execution starts. */
public final class DuplicateSmithingTemplateTask extends Task {
    private final Item item;
    private final int copies;
    private GetSmithingTemplateTask child;
    private boolean done, success;
    private String result = "模板复制尚未完成。";
    public DuplicateSmithingTemplateTask(Item item, int copies) {
        if (SmithingTemplateCatalogue.byItem(item) == null || copies < 1 || copies > 2303)
            throw new IllegalArgumentException("Invalid template copy request");
        this.item = item; this.copies = copies;
    }
    public static int targetForCopies(int initial, int copies) {
        if (initial < 1 || copies < 1 || (long)initial + copies > 2304)
            throw new IllegalArgumentException("Template duplication requires a seed and an attainable inventory count");
        return initial + copies;
    }
    public boolean succeeded() { return done && success; }
    public String getResult() { return result; }
    @Override protected void onStart(AltoClef mod) {
        if (child != null || done) return;
        int initial = mod.getItemStorage().getItemCountInventoryOnly(item);
        try { child = new GetSmithingTemplateTask(item, targetForCopies(initial, copies), true); }
        catch (IllegalArgumentException invalid) { done = true; result = "复制需要背包里有同种模板种子，且结果必须能放入背包。"; }
    }
    @Override protected Task onTick(AltoClef mod) {
        if (done || child == null) return null;
        if (!child.isFinished(mod)) return child;
        success = child.succeeded(); done = true; result = child.getResult();
        return null;
    }
    @Override protected void onStop(AltoClef mod, Task interruptTask) { }
    @Override public boolean isFinished(AltoClef mod) { return done; }
    @Override protected boolean isEqual(Task other) {
        return other instanceof DuplicateSmithingTemplateTask task && task.item == item && task.copies == copies;
    }
    @Override protected String toDebugString() { return "Duplicate smithing template " + item + " net +" + copies; }
}
