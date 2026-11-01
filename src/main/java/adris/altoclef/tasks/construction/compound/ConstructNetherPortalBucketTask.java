package adris.altoclef.tasks.construction.compound;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.construction.PlaceBlockTask;
import adris.altoclef.tasks.construction.PortalBucketTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Single-bucket shore casting: prime a drain, leave water running, then shuttle lava. */
public class ConstructNetherPortalBucketTask extends Task {
    private enum Phase { FIND_SITE, STARTER, PRIME, OPEN_DRAIN, RECOVER_PRIMER, MOLD, WATER, CAST, RECOVER_WATER, DRAIN, LIGHT }
    private static final int SEARCH_RADIUS = 24;
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final Item[] BUILD_ITEMS = {Items.DIRT, Items.COBBLESTONE, Items.NETHERRACK, Items.COBBLED_DEEPSLATE};
    private final TimerGame searchTimer = new TimerGame(5);
    private final TimerGame fluidTimer = new TimerGame(0.75);
    private final TimerGame warningTimer = new TimerGame(10);
    private final TimeoutWanderTask searchWander = new TimeoutWanderTask(5);
    private Phase phase = Phase.FIND_SITE;
    private PortalCastPlan plan;
    private boolean firstSearch = true;
    private final Set<BlockPos> ownedMold = new HashSet<>();
    private BlockPos lavaTarget;
    private BlockPos lastFrame;

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.LAVA);
        mod.getBehaviour().push();
        mod.getBehaviour().addProtectedItems(Items.BUCKET, Items.WATER_BUCKET, Items.LAVA_BUCKET,
                Items.FLINT_AND_STEEL, Items.FIRE_CHARGE);
        mod.getBehaviour().avoidBlockBreaking(pos -> plan != null && (
                (plan.isFrame(pos) && block(mod, pos).is(Blocks.OBSIDIAN))
                || (phase.ordinal() < Phase.DRAIN.ordinal() && plan.mold().contains(pos))
                || (phase.ordinal() >= Phase.PRIME.ordinal() && phase.ordinal() <= Phase.RECOVER_WATER.ordinal()
                    && (pos.equals(plan.at(0, 0, 1)) || pos.equals(plan.at(1, 0, 1))))));
        mod.getBehaviour().avoidBlockPlacing(pos -> plan != null && plan.isFluidSpace(pos)
                && !(phase == Phase.STARTER && pos.equals(plan.starter())));
        // Navigation must not pave over the supply or mine the bank holding the water.
        mod.getBehaviour().avoidBlockPlacing(pos -> block(mod, pos).is(Blocks.LAVA)
                && !(plan != null && phase == Phase.STARTER && pos.equals(plan.starter())));
        mod.getBehaviour().avoidBlockBreaking(pos -> plan != null && plan.isBank(pos));
        fluidTimer.reset();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (isFinished(mod)) return null;
        if (plan == null) return findSite(mod);
        if (!mod.level().hasChunkAt(plan.origin)) return waiting("Waiting for the portal construction chunk to load");
        setDebugState("Single-bucket portal: " + phase);
        switch (phase) {
            case STARTER -> {
                if (!PortalCastPlan.fullBlock(block(mod, plan.starter()))) return placeMold(mod, plan.starter());
                advance(Phase.PRIME);
            }
            case PRIME -> {
                if (!waterSource(mod, plan.primerWater())) {
                    return pourWater(mod, plan.primerWater(), plan.starter(), plan.right);
                }
                if (!fluidTimer.elapsed() || !block(mod, plan.at(2, 0, 0)).is(Blocks.OBSIDIAN)
                        || !block(mod, plan.at(1, 0, 1)).is(Blocks.OBSIDIAN)) return null;
                advance(Phase.OPEN_DRAIN);
            }
            case OPEN_DRAIN -> {
                if (PortalCastPlan.fullBlock(block(mod, plan.starter()))) return new DestroyBlockTask(plan.starter());
                if (!fluidTimer.elapsed() || !block(mod, plan.at(-1, 0, 0)).is(Blocks.OBSIDIAN)
                        || !block(mod, plan.at(0, 0, 1)).is(Blocks.OBSIDIAN)) return null;
                advance(Phase.RECOVER_PRIMER);
            }
            case RECOVER_PRIMER -> {
                if (waterSource(mod, plan.primerWater())) return pickupWater(mod, plan.primerWater());
                if (!has(mod, Items.WATER_BUCKET) || !fluidTimer.elapsed()) return null;
                advance(Phase.MOLD);
            }
            case MOLD -> {
                for (BlockPos channel : plan.channels()) {
                    if (block(mod, channel).is(Blocks.OBSIDIAN)) return waiting("Obsidian obstructs the water channel at " + channel.toShortString());
                    if (PortalCastPlan.fullBlock(block(mod, channel))) return new DestroyBlockTask(channel);
                }
                for (BlockPos pos : plan.mold()) {
                    if (!PortalCastPlan.fullBlock(block(mod, pos))) return placeMold(mod, pos);
                }
                advance(Phase.WATER);
            }
            case WATER -> {
                if (!waterSource(mod, plan.waterSource())) {
                    return pourWater(mod, plan.waterSource(), plan.at(-1, 3, -1), plan.right);
                }
                if (!fluidTimer.elapsed() || !water(mod, plan.at(0, 0, 0)) || !water(mod, plan.at(1, 0, 0))) return null;
                advance(Phase.CAST);
            }
            case CAST -> { return cast(mod); }
            case RECOVER_WATER -> {
                if (waterSource(mod, plan.waterSource())) return pickupWater(mod, plan.waterSource());
                if (!has(mod, Items.WATER_BUCKET)) return waiting("Waiting for recovered water bucket");
                advance(Phase.DRAIN);
            }
            case DRAIN -> {
                for (BlockPos pos : plan.interior()) {
                    if (water(mod, pos)) return waiting("Waiting for the portal curtain to drain");
                    if (!block(mod, pos).isAir()) return new DestroyBlockTask(pos);
                }
                for (BlockPos pos : ownedMold.stream().sorted(Comparator.comparingInt((BlockPos p) -> p.getY()).reversed()).toList()) {
                    if (!block(mod, pos).isAir() && !plan.isFrame(pos)) return new DestroyBlockTask(pos);
                }
                advance(Phase.LIGHT);
            }
            case LIGHT -> {
                if (plan.frame().stream().anyMatch(p -> !block(mod, p.target()).is(Blocks.OBSIDIAN))) {
                    advance(Phase.MOLD);
                    return null;
                }
                return new InteractWithBlockTask(new ItemTarget(new Item[]{Items.FLINT_AND_STEEL, Items.FIRE_CHARGE}, 1),
                        Direction.UP, plan.at(0, -1, 0), false);
            }
            default -> { }
        }
        return null;
    }

    private Task findSite(AltoClef mod) {
        if (!has(mod, Items.FLINT_AND_STEEL) && !has(mod, Items.FIRE_CHARGE)) {
            return TaskCatalogue.getItemTask(Items.FLINT_AND_STEEL, 1);
        }
        if (!has(mod, Items.BUCKET) && !has(mod, Items.WATER_BUCKET) && !has(mod, Items.LAVA_BUCKET)) {
            return TaskCatalogue.getItemTask(Items.BUCKET, 1);
        }
        if (mod.getItemStorage().getItemCount(BUILD_ITEMS) < 15) return PlaceBlockTask.getMaterialTask(15);
        if (!has(mod, Items.WATER_BUCKET)) {
            if (!has(mod, Items.BUCKET)) return waiting("Bring water or an empty bucket before starting the cast");
            return TaskCatalogue.getItemTask(Items.WATER_BUCKET, 1);
        }
        if (firstSearch || searchTimer.elapsed()) {
            firstSearch = false;
            searchTimer.reset();
            List<BlockPos> sources = exposedSources(mod, mod.getPlayer().blockPosition(), SEARCH_RADIUS);
            plan = selectPlan(mod, sources, true);
            if (plan == null) plan = selectPlan(mod, sources, false);
            if (plan != null) {
                Debug.logMessage("[DeepClef portal] single bucket " + (plan.poolEdge ? "shore" : "flat-ground")
                        + " cast at " + plan.origin.toShortString() + " facing " + plan.forward
                        + ", remaining pours=" + plan.poursNeededAfterPriming(p -> block(mod, p)));
                advance(plan.poolEdge ? Phase.STARTER : Phase.MOLD);
                return null;
            }
            Debug.logMessage("[DeepClef portal] no suitable shore/flat cast with enough exposed lava within " + SEARCH_RADIUS);
        }
        setDebugState("Searching for lava and a supported, clear casting site");
        return searchWander;
    }

    private PortalCastPlan selectPlan(AltoClef mod, List<BlockPos> sources, boolean shore) {
        Set<BlockPos> tried = new HashSet<>();
        for (BlockPos lava : sources) {
            if (shore) {
                for (Direction direction : HORIZONTAL) {
                    PortalCastPlan candidate = new PortalCastPlan(lava, direction, true);
                    if (validPlan(mod, candidate, sources)) return candidate;
                }
            } else {
                for (Direction direction : HORIZONTAL) for (int distance = 2; distance <= 6; distance++) {
                    BlockPos origin = lava.relative(direction, distance);
                    if (!tried.add(origin)) continue;
                    for (Direction face : HORIZONTAL) {
                        PortalCastPlan candidate = new PortalCastPlan(origin, face, false);
                        if (validPlan(mod, candidate, sources)) return candidate;
                    }
                }
            }
        }
        return null;
    }

    private boolean validPlan(AltoClef mod, PortalCastPlan candidate, List<BlockPos> sources) {
        if (!candidate.fits(p -> mod.level().hasChunkAt(p) ? block(mod, p) : Blocks.BEDROCK.defaultBlockState())) return false;
        return candidate.hasEnoughLava(sources, p -> block(mod, p));
    }

    private Task cast(AltoClef mod) {
        if (!waterSource(mod, plan.waterSource())) {
            if (has(mod, Items.WATER_BUCKET)) { advance(Phase.MOLD); return null; }
            if (has(mod, Items.BUCKET)) return TaskCatalogue.getItemTask(Items.WATER_BUCKET, 1);
            // Store the carried lava back in its original pool cell before reusing the bucket for water.
            if (has(mod, Items.LAVA_BUCKET) && lavaTarget != null
                    && (block(mod, lavaTarget).isAir() || block(mod, lavaTarget).is(Blocks.LAVA))) {
                for (Direction face : Direction.values()) {
                    BlockPos support = lavaTarget.relative(face.getOpposite());
                    if (PortalCastPlan.fullBlock(block(mod, support))) return new PortalBucketTask(Items.LAVA_BUCKET, support, face);
                }
            }
            return waiting("Casting water source missing; restore water at " + plan.waterSource().toShortString());
        }
        for (PortalCastPlan.Placement placement : plan.frame()) {
            BlockPos pos = placement.target();
            if (block(mod, pos).is(Blocks.OBSIDIAN)) continue;
            if (!pos.equals(lastFrame)) {
                lastFrame = pos;
                fluidTimer.reset();
                Debug.logMessage("[DeepClef portal] casting frame " + pos.toShortString());
            }
            BlockState state = block(mod, pos);
            if (state.is(Blocks.LAVA)) {
                if (fluidTimer.elapsed() && !touchesWater(mod, pos) && has(mod, Items.BUCKET)) {
                    return new PortalBucketTask(Items.BUCKET, pos, null);
                }
                return waiting("Waiting for lava to cool at " + pos.toShortString());
            }
            if (!state.isAir() && !state.is(Blocks.WATER)) return new DestroyBlockTask(pos);
            if (!touchesWater(mod, pos)) {
                if (fluidTimer.elapsed()) advance(Phase.MOLD);
                return waiting("Restoring casting water at " + pos.toShortString());
            }
            if (!has(mod, Items.LAVA_BUCKET)) {
                if (!has(mod, Items.BUCKET)) return waiting("Waiting for the empty casting bucket");
                if (lavaTarget == null || !PortalCastPlan.lavaSource(block(mod, lavaTarget))) {
                    lavaTarget = exposedSources(mod, plan.origin, 16).stream()
                            .filter(p -> !plan.isFrame(p) && !plan.isFluidSpace(p))
                            .min(Comparator.comparingDouble(p -> p.distSqr(mod.getPlayer().blockPosition()))).orElse(null);
                }
                if (lavaTarget == null) return waiting("No exposed lava sources remain near this portal");
                return new PortalBucketTask(Items.BUCKET, lavaTarget, null);
            }
            if (!PortalCastPlan.fullBlock(block(mod, placement.support()))) {
                return waiting("Casting support missing at " + placement.support().toShortString());
            }
            return new PortalBucketTask(Items.LAVA_BUCKET, placement.support(), placement.face());
        }
        advance(Phase.RECOVER_WATER);
        return null;
    }

    private Task placeMold(AltoClef mod, BlockPos pos) {
        for (Item item : BUILD_ITEMS) if (has(mod, item)) {
            ownedMold.add(pos);
            return new PlaceBlockTask(pos, net.minecraft.world.level.block.Block.byItem(item));
        }
        return PlaceBlockTask.getMaterialTask(15);
    }

    private Task pourWater(AltoClef mod, BlockPos target, BlockPos support, Direction face) {
        fluidTimer.reset();
        if (!has(mod, Items.WATER_BUCKET)) return waiting("Waiting for water bucket to place at " + target.toShortString());
        return new PortalBucketTask(Items.WATER_BUCKET, support, face);
    }

    private Task pickupWater(AltoClef mod, BlockPos source) {
        fluidTimer.reset();
        if (!has(mod, Items.BUCKET)) return waiting("Waiting for empty bucket to recover the water source");
        return new PortalBucketTask(Items.BUCKET, source, null);
    }

    private static List<BlockPos> exposedSources(AltoClef mod, BlockPos center, int radius) {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos cursor : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
            if (!mod.level().hasChunkAt(cursor)) continue;
            if (PortalCastPlan.lavaSource(block(mod, cursor)) && block(mod, cursor.above()).isAir()) result.add(cursor.immutable());
        }
        result.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
        return result;
    }

    private static boolean touchesWater(AltoClef mod, BlockPos pos) {
        if (water(mod, pos.above())) return true;
        for (Direction side : HORIZONTAL) if (water(mod, pos.relative(side))) return true;
        return false;
    }

    private static BlockState block(AltoClef mod, BlockPos pos) { return mod.level().getBlockState(pos); }
    private static boolean water(AltoClef mod, BlockPos pos) { return block(mod, pos).is(Blocks.WATER); }
    private static boolean waterSource(AltoClef mod, BlockPos pos) { return water(mod, pos) && block(mod, pos).getFluidState().isSource(); }
    private static boolean has(AltoClef mod, Item item) { return PortalBucketTask.hasItem(mod, item); }

    private Task waiting(String message) {
        setDebugState(message);
        if (warningTimer.elapsed()) {
            warningTimer.reset();
            Debug.logWarning("[DeepClef portal] " + message);
        }
        return null;
    }

    private void advance(Phase next) {
        phase = next;
        fluidTimer.reset();
        Debug.logMessage("[DeepClef portal] phase=" + next);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        if (plan == null || mod.level() == null) return false;
        boolean done = block(mod, plan.origin.above()).is(Blocks.NETHER_PORTAL);
        if (done) mod.getBlockTracker().addBlock(Blocks.NETHER_PORTAL, plan.origin.above());
        return done;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.LAVA);
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) { return other instanceof ConstructNetherPortalBucketTask; }

    @Override
    protected String toDebugString() { return "Construct Nether Portal (single bucket)"; }
}
