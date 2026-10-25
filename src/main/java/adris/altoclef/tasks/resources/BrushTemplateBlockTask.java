package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import baritone.api.utils.input.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.Items;

/** Native continuous brush use; completion requires the server-observed block conversion. */
final class BrushTemplateBlockTask extends Task {
    private final BlockPos target;
    private int ticks, lastTick = Integer.MIN_VALUE;
    private boolean done;
    BrushTemplateBlockTask(BlockPos target) { this.target = target.immutable(); }
    @Override protected void onStart(AltoClef mod) { }
    @Override protected Task onTick(AltoClef mod) {
        if (!mod.level().hasChunkAt(target)) return new GetWithinRangeOfBlockTask(target, 2);
        if (!mod.level().getBlockState(target).is(Blocks.SUSPICIOUS_GRAVEL)) { done = true; return null; }
        if (lastTick != mod.getPlayer().tickCount) { lastTick = mod.getPlayer().tickCount; ticks++; }
        if (ticks > 1200) { done = true; return null; }
        if (!mod.getItemStorage().hasItemInventoryOnly(Items.BRUSH)) return TaskCatalogue.getItemTask(Items.BRUSH, 1);
        if (mod.getFoodChain().isTryingToEat()) { mod.getInputControls().release(Input.CLICK_RIGHT); return null; }
        if (mod.getPlayer().containerMenu != mod.getPlayer().inventoryMenu) { mod.getInputControls().release(Input.CLICK_RIGHT); return null; }
        var reach = LookHelper.getReach(target);
        if (reach.isEmpty()) { mod.getInputControls().release(Input.CLICK_RIGHT); return new GetWithinRangeOfBlockTask(target, 2); }
        mod.getClientBaritone().getPathingBehavior().requestPause();
        LookHelper.lookAt(mod, reach.get());
        if (!mod.getSlotHandler().forceEquipItem(Items.BRUSH) || !mod.getPlayer().getMainHandItem().is(Items.BRUSH)) { mod.getInputControls().release(Input.CLICK_RIGHT); return null; }
        Vec3 eye = mod.getPlayer().getEyePosition();
        BlockHitResult hit = mod.level().clip(new ClipContext(eye,
                eye.add(mod.getPlayer().getLookAngle().scale(mod.getPlayer().blockInteractionRange())),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mod.getPlayer()));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(target)) { mod.getInputControls().release(Input.CLICK_RIGHT); return null; }
        if (!mod.getPlayer().isUsingItem()) mod.getController().useItemOn(mod.getPlayer(), InteractionHand.MAIN_HAND, hit);
        mod.getInputControls().hold(Input.CLICK_RIGHT);
        setDebugState("Brushing suspicious gravel continuously; preserving the block and its support.");
        return null;
    }
    @Override protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getInputControls().release(Input.CLICK_RIGHT);
        if (mod.getPlayer() != null && mod.getPlayer().isUsingItem() && mod.getPlayer().getUseItem().is(Items.BRUSH))
            mod.getController().releaseUsingItem(mod.getPlayer());
    }
    @Override public boolean isFinished(AltoClef mod) { return done; }
    @Override protected boolean isEqual(Task other) { return other instanceof BrushTemplateBlockTask task && task.target.equals(target); }
    @Override protected String toDebugString() { return "Brush template-bearing gravel " + target.toShortString(); }
}
