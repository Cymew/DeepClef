package adris.altoclef.tasks.squashed;

import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.container.UpgradeInSmithingTableTask;

import java.util.ArrayList;
import java.util.List;

/** Each upgrade computes its real remaining inputs and preserves a reusable template seed. */
public final class SmithingSquasher extends TypeSquasher<UpgradeInSmithingTableTask> {
    @Override protected List<ResourceTask> getSquashed(List<UpgradeInSmithingTableTask> tasks) {
        // Eagerly collecting every original target overcounts partially completed
        // upgrades and used to omit templates. Reuse the exact task instances.
        return new ArrayList<>(tasks);
    }
}
