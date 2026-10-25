package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasksystem.Task;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.vault.VaultState;

/** Uses the correct native key once, then waits for its actual reward-ejection cycle. */
final class UnlockTemplateVaultTask extends Task {
    private final BlockPos target;
    private final boolean ominous;
    private int initialKeys, sentTick = -1, started, lastTick = Integer.MIN_VALUE;
    private boolean done, unlocked;
    UnlockTemplateVaultTask(BlockPos target, boolean ominous) { this.target = target.immutable(); this.ominous = ominous; }
    @Override protected void onStart(AltoClef mod) {
        initialKeys = mod.getItemStorage().getItemCountInventoryOnly(key());
        if (started == 0) started = mod.getPlayer().tickCount;
    }
    private Item key() { return ominous ? Items.OMINOUS_TRIAL_KEY : Items.TRIAL_KEY; }
    @Override protected Task onTick(AltoClef mod) {
        if (done) return null;
        if (!mod.level().hasChunkAt(target)) return new InteractWithBlockTask(key(), target);
        var state = mod.level().getBlockState(target);
        if (!state.is(Blocks.VAULT) || state.getValue(VaultBlock.OMINOUS) != ominous) { done = true; return null; }
        int tick = mod.getPlayer().tickCount;
        VaultState phase = state.getValue(VaultBlock.STATE);
        int keys = mod.getItemStorage().getItemCountInventoryOnly(key());
        if (keys < initialKeys || phase == VaultState.UNLOCKING || phase == VaultState.EJECTING) {
            unlocked = true;
            if (sentTick < 0) sentTick = tick;
        }
        if (unlocked) {
            // Loot is ejected one item at a time; do not leave after the key packet alone.
            if (tick - sentTick >= 30 && phase != VaultState.UNLOCKING && phase != VaultState.EJECTING || tick - sentTick > 300) done = true;
            setDebugState("Waiting for native vault reward ejection."); return null;
        }
        if (tick - started > 240) { done = true; return null; }
        setDebugState("Using the correct key on an eligible vault; previously rewarded vaults are skipped after bounded attempts.");
        return new InteractWithBlockTask(key(), target);
    }
    @Override protected void onStop(AltoClef mod, Task interruptTask) { }
    @Override public boolean isFinished(AltoClef mod) { return done; }
    @Override protected boolean isEqual(Task other) { return other instanceof UnlockTemplateVaultTask task && task.target.equals(target) && task.ominous == ominous; }
    @Override protected String toDebugString() { return (ominous ? "Unlock ominous" : "Unlock") + " template vault " + target.toShortString(); }
}
