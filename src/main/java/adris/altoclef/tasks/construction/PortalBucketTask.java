package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.input.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import java.util.Objects;

/** A single bucket operation. Never obtains another bucket after the held one changes type.
 * The owner verifies the resulting block before advancing the construction phase.
 */
public final class PortalBucketTask extends Task {
    private final Item item;
    private final BlockPos click;
    private final Direction face;
    private final TimerGame clickTimer = new TimerGame(0.4);
    private BlockPos stand;

    public PortalBucketTask(Item item, BlockPos click, Direction face) {
        this.item = item;
        this.click = click;
        this.face = face;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBehaviour().push();
        mod.getBehaviour().setRayTracingFluidHandling(item == Items.BUCKET
                ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE);
        clickTimer.reset();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (!hasItem(mod, item)) return null;
        if (item == Items.BUCKET && !mod.level().getFluidState(click).isSource()) return null;
        var reach = LookHelper.getReach(click, face);
        if (reach.isEmpty()) {
            if (stand == null || !canStand(mod, stand) || stand.equals(mod.getPlayer().blockPosition())) {
                stand = findStand(mod);
            }
            if (stand == null) {
                setDebugState("No safe position with a clear view of the bucket target");
                return null;
            }
            return new GetToBlockTask(stand, false);
        }
        // Stop the movement child before using its view/input for an interaction.
        if (thisOrChildSatisfies(t -> t != this && t.isActive() && !t.stopped())) return null;
        if (mod.getFoodChain().isTryingToEat() || mod.getPlayer().isBlocking()
                || mod.getExtraBaritoneSettings().isInteractionPaused()) return null;
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        if (!mod.getSlotHandler().forceEquipItem(item)) return null;
        LookHelper.lookAt(mod, reach.get());
        Vec3 eye = mod.getPlayer().getEyePosition(1.0F);
        double range = mod.getClientBaritone().getPlayerContext().playerController().getBlockReachDistance();
        // Check the actual view and face, not merely a reachable planned rotation.
        if (clickTimer.elapsed() && hits(mod, eye, eye.add(mod.getPlayer().getViewVector(1.0F).scale(range)))) {
            mod.getInputControls().tryPress(Input.CLICK_RIGHT);
            clickTimer.reset();
        }
        return null;
    }

    private BlockPos findStand(AltoClef mod) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        Vec3 point = Vec3.atCenterOf(click);
        if (face != null) point = point.add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5));
        double range = mod.getClientBaritone().getPlayerContext().playerController().getBlockReachDistance();
        for (BlockPos p : BlockPos.betweenClosed(click.offset(-4, -3, -4), click.offset(4, 2, 4))) {
            if (!canStand(mod, p)) continue;
            Vec3 eye = Vec3.atBottomCenterOf(p).add(0, 1.62, 0);
            Vec3 ray = point.subtract(eye);
            if (ray.length() > range - 0.15 || !hits(mod, eye, point.add(ray.normalize().scale(0.02)))) continue;
            double distance = p.distSqr(mod.getPlayer().blockPosition());
            if (distance < bestDistance) { bestDistance = distance; best = p.immutable(); }
        }
        return best;
    }

    private boolean canStand(AltoClef mod, BlockPos p) {
        if (!mod.level().hasChunkAt(p) || !mod.level().getBlockState(p).isAir()
                || !mod.level().getBlockState(p.above()).isAir()) return false;
        var floor = mod.level().getBlockState(p.below());
        if (!floor.isSolidRender() || !floor.getFluidState().isEmpty()) return false;
        // Do not stand in the cell into which the held lava will be poured.
        return face == null || item != Items.LAVA_BUCKET || !p.equals(click.relative(face));
    }

    private boolean hits(AltoClef mod, Vec3 eye, Vec3 end) {
        var hit = mod.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE,
                item == Items.BUCKET ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE, mod.getPlayer()));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(click)
                && (face == null || hit.getDirection() == face);
    }

    /** Bypass the storage tracker's one-tick bucket lag after use. */
    public static boolean hasItem(AltoClef mod, Item item) {
        var inventory = mod.getPlayer().getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).is(item)) return true;
        }
        return false;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getInputControls().release(Input.CLICK_RIGHT);
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof PortalBucketTask task && task.item == item
                && task.click.equals(click) && Objects.equals(task.face, face);
    }

    @Override
    protected String toDebugString() { return "Portal bucket " + item + " at " + click.toShortString(); }
}
