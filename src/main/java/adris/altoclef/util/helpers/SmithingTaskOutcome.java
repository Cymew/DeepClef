package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.container.SmithingTask;
import adris.altoclef.tasks.container.UpgradeInSmithingTableTask;
import adris.altoclef.tasks.resources.GetSmithingTemplateTask;
import adris.altoclef.tasks.resources.TrialKeyAcquisitionTask;
import adris.altoclef.tasksystem.Task;

/** Propagates a terminal smithing failure through existing resource dependency tasks. */
public final class SmithingTaskOutcome {
    private SmithingTaskOutcome() { }

    public static String failure(Task root, AltoClef mod) {
        if (root == null) return null;
        String[] failure = {null};
        root.thisOrChildSatisfies(task -> {
            if (task instanceof SmithingTask operation && operation.isFinished(mod) && !operation.succeeded())
                failure[0] = operation.getResult();
            else if (task instanceof UpgradeInSmithingTableTask upgrade && upgrade.isFinished(mod) && !upgrade.succeeded())
                failure[0] = upgrade.getResult();
            else if (task instanceof GetSmithingTemplateTask template && template.isFinished(mod) && !template.succeeded())
                failure[0] = template.getResult();
            else if (task instanceof TrialKeyAcquisitionTask key && key.isFinished(mod) && !key.succeeded())
                failure[0] = key.getResult();
            return failure[0] != null;
        });
        return failure[0];
    }
}
