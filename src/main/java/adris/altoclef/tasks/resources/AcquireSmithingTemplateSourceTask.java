package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasks.movement.SearchChunkForBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.SmithingTemplateCatalogue;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VaultBlock;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Public client-visible exploration and native source interactions; never reads hidden loot/structure data. */
public final class AcquireSmithingTemplateSourceTask extends Task {
    private final SmithingTemplateCatalogue.Entry entry;
    private final Set<BlockPos> visited = new HashSet<>();
    private Task operation;
    private BlockPos activeSite;
    private Task search;
    private String failure;
    private final Block[] tracked;

    public AcquireSmithingTemplateSourceTask(SmithingTemplateCatalogue.Entry entry) {
        this.entry = entry;
        Set<Block> blocks = new HashSet<>(Arrays.asList(entry.markers()));
        blocks.add(Blocks.CHEST); blocks.add(Blocks.TRAPPED_CHEST);
        blocks.add(Blocks.SUSPICIOUS_GRAVEL); blocks.add(Blocks.VAULT);
        tracked = blocks.toArray(Block[]::new);
    }
    public String getFailure() { return failure; }
    boolean awaitingChestConfirmation(AltoClef mod) { return operation instanceof TemplateChestLootTask chest && !chest.isFinished(mod); }
    @Override protected void onStart(AltoClef mod) {
        mod.getBehaviour().push();
        mod.getBehaviour().avoidBlockBreaking(pos -> mod.level().hasChunkAt(pos)
                && (mod.level().getBlockState(pos).is(Blocks.SUSPICIOUS_GRAVEL)
                || mod.level().getBlockState(pos.above()).is(Blocks.SUSPICIOUS_GRAVEL)
                || mod.level().getBlockState(pos).is(Blocks.CHEST)
                || mod.level().getBlockState(pos).is(Blocks.TRAPPED_CHEST)
                || mod.level().getBlockState(pos).is(Blocks.VAULT)));
        mod.getBlockTracker().trackBlock(tracked);
        if (search == null) search = new SearchChunkForBlockTask(entry.markers());
    }
    @Override protected Task onTick(AltoClef mod) {
        if (failure != null) return null;
        if (WorldHelper.getCurrentDimension() != entry.dimension()) return null;
        if (operation != null) {
            if (!operation.isFinished(mod)) return operation;
            if (operation.isActive()) operation.stop(mod);
            if (operation instanceof TrialKeyAcquisitionTask key && !key.succeeded()) {
                failure = key.getResult(); return null;
            }
            if (activeSite != null) visited.add(activeSite);
            operation = null; activeSite = null;
        }
        if (mod.getItemStorage().hasItemInventoryOnly(entry.item())) return null;
        // Any exposed chest may already contain the seed; known stocked chests
        // and dropped templates were handled even earlier by ResourceTask.
        Optional<BlockPos> chest = closest(mod, Blocks.CHEST, Blocks.TRAPPED_CHEST);
        if (chest.isPresent()) return at(chest.get(), new TemplateChestLootTask(chest.get(), entry.item()));
        switch (entry.source()) {
            case ARCHAEOLOGY -> {
                Optional<BlockPos> gravel = closest(mod, Blocks.SUSPICIOUS_GRAVEL);
                if (gravel.isPresent()) return at(gravel.get(), new BrushTemplateBlockTask(gravel.get()));
            }
            case ELDER_GUARDIAN -> {
                if (mod.getEntityTracker().entityFound(ElderGuardian.class)) {
                    setDebugState("Fighting a discovered elder guardian for the native tide-template drop.");
                    return new KillEntitiesTask(ElderGuardian.class);
                }
            }
            case VAULT, OMINOUS_VAULT -> {
                boolean ominous = entry.source() == SmithingTemplateCatalogue.SourceKind.OMINOUS_VAULT;
                Item key = ominous ? Items.OMINOUS_TRIAL_KEY : Items.TRIAL_KEY;
                if (!mod.getItemStorage().hasItemInventoryOnly(key)) {
                    operation = new TrialKeyAcquisitionTask(ominous, 1);
                    return operation;
                }
                Optional<BlockPos> vault = mod.getBlockTracker().getNearestTracking(pos -> !visited.contains(pos)
                                && mod.level().hasChunkAt(pos) && mod.level().getBlockState(pos).is(Blocks.VAULT)
                                && mod.level().getBlockState(pos).getValue(VaultBlock.OMINOUS) == ominous,
                        Blocks.VAULT);
                if (vault.isPresent()) return at(vault.get(), new UnlockTemplateVaultTask(vault.get(), ominous));
            }
            default -> { }
        }
        setDebugState("Exploring for " + entry.sourceDescription());
        return search;
    }
    private Task at(BlockPos site, Task task) { activeSite = site.immutable(); operation = task; return task; }
    private Optional<BlockPos> closest(AltoClef mod, Block... blocks) {
        return mod.getBlockTracker().getNearestTracking(pos -> !visited.contains(pos) && mod.level().hasChunkAt(pos)
                && pos.closerThan(mod.getPlayer().blockPosition(), 128), blocks);
    }
    @Override protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(tracked); mod.getBehaviour().pop();
    }
    @Override public boolean isFinished(AltoClef mod) {
        return failure != null || mod != null && mod.getItemStorage().hasItemInventoryOnly(entry.item())
                && (!(operation instanceof TemplateChestLootTask chest) || chest.isFinished(mod));
    }
    @Override protected boolean isEqual(Task other) { return other instanceof AcquireSmithingTemplateSourceTask task && task.entry.item() == entry.item(); }
    @Override protected String toDebugString() { return "Find first " + entry.key() + " smithing template"; }
}
