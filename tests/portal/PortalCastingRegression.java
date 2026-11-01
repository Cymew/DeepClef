package adris.altoclef.portal;

import adris.altoclef.tasks.construction.compound.PortalCastPlan;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import sun.misc.Unsafe;

import java.lang.reflect.Method;
import java.util.*;

/** Runs vanilla 26.3 water ticks and lava contact conversion on an in-memory block grid.
 * No client, network or user save is opened. Navigation remains an in-game test concern.
 */
public final class PortalCastingRegression {
    private static int checks;
    private static Method lavaContact;
    private static Unsafe unsafe;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        unsafe = (Unsafe) field.get(null);
        var bindTags = Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class);
        bindTags.setAccessible(true);
        for (Fluid f : List.of(Fluids.WATER, Fluids.FLOWING_WATER)) {
            bindTags.invoke(BuiltInRegistries.FLUID.wrapAsHolder(f), List.of(FluidTags.WATER));
        }
        for (Fluid f : List.of(Fluids.LAVA, Fluids.FLOWING_LAVA)) {
            bindTags.invoke(BuiltInRegistries.FLUID.wrapAsHolder(f), List.of(FluidTags.LAVA));
        }
        // These are the only washed-away block types present in the fixture.
        for (var b : List.of(Blocks.AIR, Blocks.WATER, Blocks.LAVA)) {
            bindTags.invoke(BuiltInRegistries.BLOCK.wrapAsHolder(b), List.of(BlockTags.WASHED_AWAY_BY_FLUIDS));
        }
        lavaContact = LiquidBlock.class.getDeclaredMethod("shouldSpreadLiquid", Level.class, BlockPos.class, BlockState.class);
        lavaContact.setAccessible(true);
        miningThroughWater();
        for (Direction face : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            for (int depth : new int[]{1, 2, 4}) run(face, depth, true);
            run(face, 3, true); // Mixed bottom: one natural obsidian, one block to cast.
            run(face, 1, false);
        }
        System.out.println("Portal casting checks passed: " + checks);
    }

    private static void miningThroughWater() throws Exception {
        Grid world = (Grid) unsafe.allocateInstance(Grid.class);
        world.blocks = new HashMap<>();
        BlockPos stone = new BlockPos(0, 0, 0);
        world.raw(stone, Blocks.STONE.defaultBlockState());
        world.raw(stone.above(), Blocks.WATER.defaultBlockState());
        Vec3 eye = new Vec3(.5, 3, .5);
        for (var fluid : List.of(ClipContext.Fluid.SOURCE_ONLY, ClipContext.Fluid.NONE)) {
            var hit = world.clip(new ClipContext(eye, new Vec3(.5, .5, .5), ClipContext.Block.OUTLINE,
                    fluid, net.minecraft.world.phys.shapes.CollisionContext.empty()));
            check(hit.getBlockPos().equals(fluid == ClipContext.Fluid.NONE ? stone : stone.above()),
                    "mining ray bypasses the water source that blocks a bucket ray");
        }
        check(!adris.altoclef.tasks.construction.DestroyBlockTask.isClearedState(Blocks.STONE.defaultBlockState()), "stone still needs mining");
        check(adris.altoclef.tasks.construction.DestroyBlockTask.isClearedState(Blocks.WATER.defaultBlockState()), "water-filled mined hole is complete");
        check(adris.altoclef.tasks.construction.DestroyBlockTask.isClearedState(Blocks.AIR.defaultBlockState()), "dry mined hole is complete");
        check(!adris.altoclef.tasks.construction.DestroyBlockTask.isClearedState(Blocks.OAK_SLAB.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true)), "waterlogged solid still needs mining");
        // A stone lip blocks the player's view, while the opposite bank sees into the wet hole.
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
            if (x != 0 || z != 0) world.raw(new BlockPos(x, 1, z), Blocks.STONE.defaultBlockState());
        }
        world.raw(new BlockPos(0, 2, -1), Blocks.STONE.defaultBlockState());
        BlockPos player = new BlockPos(0, 2, -2);
        var blocked = world.clip(new ClipContext(Vec3.atBottomCenterOf(player).add(0,1.62,0), Vec3.atCenterOf(stone),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
        check(!blocked.getBlockPos().equals(stone), "original position cannot see stone behind lip");
        BlockPos stand = adris.altoclef.tasks.construction.DestroyBlockTask.findMiningStand(world, stone, player, 4.5);
        check(stand != null && !stand.equals(player), "choose a different visible mining stance across water");
        for (Direction side : Direction.values()) world.raw(stone.relative(side), Blocks.STONE.defaultBlockState());
        check(adris.altoclef.tasks.construction.DestroyBlockTask.findMiningStand(world, stone, player, 4.5) == null,
                "do not pretend to see a completely enclosed stone");
    }

    private static void run(Direction face, int depth, boolean shore) throws Exception {
        var plan = new PortalCastPlan(new BlockPos(31, 64, -17), face, shore);
        Grid world = (Grid) unsafe.allocateInstance(Grid.class);
        world.blocks = new HashMap<>();
        world.rules = new GameRules(FeatureFlags.VANILLA_SET);
        world.randomSource = RandomSource.create(1);
        for (int x = -10; x <= 10; x++) for (int z = -10; z <= 14; z++) {
            for (int y = -6; y <= 0; y++) {
                boolean pool = shore && x >= -3 && x <= 5 && z >= 0 && z <= 8 && y > -depth;
                world.raw(plan.at(x, y, z), pool ? Blocks.LAVA.defaultBlockState() : Blocks.STONE.defaultBlockState());
            }
        }
        if (depth == 3) {
            world.raw(plan.at(0, -1, 0), Blocks.STONE.defaultBlockState());
            world.raw(plan.at(0, -2, 0), Blocks.STONE.defaultBlockState());
        }
        if (!shore) {
            for (int x = 5; x <= 9; x++) for (int z = 3; z <= 8; z++) {
                world.raw(plan.at(x, 0, z), Blocks.LAVA.defaultBlockState());
            }
        }
        check(plan.fits(world::getBlockState), "site accepted " + face + " depth=" + depth + " shore=" + shore);
        int pours = plan.poursNeededAfterPriming(world::getBlockState);
        check(pours == (shore ? depth == 3 ? 7 : depth > 1 ? 6 : 8 : 10), "reuse budget");
        List<BlockPos> supply = world.blocks.entrySet().stream()
                .filter(e -> PortalCastPlan.lavaSource(e.getValue()) && world.getBlockState(e.getKey().above()).isAir())
                .map(Map.Entry::getKey).filter(p -> !plan.primedCells().contains(p)).toList();
        check(plan.hasEnoughLava(supply, world::getBlockState), "adequate supply accepted");
        check(!plan.hasEnoughLava(supply.subList(0, pours - 1), world::getBlockState), "one source short rejected");
        check(plan.hasEnoughLava(supply.subList(0, pours), world::getBlockState), "exact supply accepted");
        check(!plan.hasEnoughLava(Collections.nCopies(pours, supply.getFirst()), world::getBlockState), "duplicate sources not counted twice");
        BlockState originalFloor = world.getBlockState(plan.at(0, -1, 0));
        world.raw(plan.at(0, -1, 0), Blocks.BEDROCK.defaultBlockState());
        check(!plan.fits(world::getBlockState), "unbreakable bottom rejected");
        world.raw(plan.at(0, -1, 0), originalFloor);
        world.raw(plan.at(0, 3, 0), Blocks.STONE.defaultBlockState());
        check(!plan.fits(world::getBlockState), "low ceiling rejected");
        world.raw(plan.at(0, 3, 0), Blocks.AIR.defaultBlockState());
        world.raw(plan.at(0, 0, -2), Blocks.AIR.defaultBlockState());
        check(!plan.fits(world::getBlockState), "hole behind the water source rejected");
        world.raw(plan.at(0, 0, -2), Blocks.STONE.defaultBlockState());
        if (shore) {
            world.setBlockAndUpdate(plan.starter(), Blocks.DIRT.defaultBlockState());
            world.setBlockAndUpdate(plan.primerWater(), Blocks.WATER.defaultBlockState());
            world.settle();
            world.setBlockAndUpdate(plan.starter(), Blocks.AIR.defaultBlockState());
            world.settle();
            check(world.getBlockState(plan.at(-1, 0, 0)).is(Blocks.OBSIDIAN), "left side primed");
            check(world.getBlockState(plan.at(2, 0, 0)).is(Blocks.OBSIDIAN), "right side primed");
            world.setBlockAndUpdate(plan.primerWater(), Blocks.AIR.defaultBlockState());
            world.settle();
        } else {
            for (int x = 0; x < 2; x++) world.setBlockAndUpdate(plan.at(x, 0, 0), Blocks.AIR.defaultBlockState());
        }
        Set<BlockPos> reserve = new HashSet<>();
        // Budget/keep the exposed supply. Cooling covered lava beside a mixed-depth
        // bottom is harmless and must not be confused with flooding the surface pool.
        world.blocks.forEach((p, s) -> { if (s.is(Blocks.LAVA) && world.getBlockState(p.above()).isAir()) reserve.add(p); });
        for (BlockPos p : plan.mold()) world.setBlockAndUpdate(p, Blocks.DIRT.defaultBlockState());
        world.setBlockAndUpdate(plan.waterSource(), Blocks.WATER.defaultBlockState());
        world.settle();
        int actualPours = 0;
        for (var placement : plan.frame()) {
            check(placement.support().relative(placement.face()).equals(placement.target()), "face targets correct cell");
            BlockPos pos = placement.target();
            if (world.getBlockState(pos).is(Blocks.OBSIDIAN)) continue;
            if (!world.getBlockState(pos).getFluidState().isEmpty() || world.getBlockState(pos).isAir()) {
                // Water is replaced by the bucket, with adjacent flowing water left intact.
            } else {
                world.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                world.settle();
            }
            check(PortalCastPlan.fullBlock(world.getBlockState(placement.support())), "casting face exists");
            check(reachable(world, placement.support(), placement.face(), false), "dry standing position can reach casting face");
            check(wet(world, pos), "target has adjacent water " + pos + " local face=" + face);
            BlockPos take = reserve.stream().filter(p -> PortalCastPlan.lavaSource(world.getBlockState(p))
                    && world.getBlockState(p.above()).isAir()).findFirst().orElseThrow();
            world.setBlockAndUpdate(take, Blocks.AIR.defaultBlockState());
            reserve.remove(take);
            world.setBlockAndUpdate(pos, Blocks.LAVA.defaultBlockState());
            world.settle();
            check(world.getBlockState(pos).is(Blocks.OBSIDIAN), "native lava contact produced obsidian");
            check(world.getFluidState(plan.waterSource()).isSource(), "water source preserved");
            actualPours++;
        }
        check(actualPours == pours, "no redundant lava pours");
        check(reachable(world, plan.waterSource(), null, true), "completed frame still permits water recovery");
        for (BlockPos p : reserve) check(world.getBlockState(p).is(Blocks.LAVA), "water did not flood remaining pool " + p);
        world.setBlockAndUpdate(plan.waterSource(), Blocks.AIR.defaultBlockState());
        world.settle();
        for (BlockPos p : plan.interior()) check(world.getBlockState(p).isAir(), "portal interior drains completely");
        for (var p : plan.frame()) check(world.getBlockState(p.target()).is(Blocks.OBSIDIAN), "ten final frame blocks");
        // Reject unsupported banks and low ceilings, in every rotation.
        Grid emptySite = (Grid) unsafe.allocateInstance(Grid.class);
        emptySite.blocks = new HashMap<>();
        check(!plan.fits(emptySite::getBlockState), "floating site rejected");
        System.out.println("PASS " + face + " " + (shore ? "pool depth " + depth : "flat ground") + ", pours=" + actualPours);
    }

    private static boolean wet(Grid world, BlockPos pos) {
        for (Direction d : Direction.values()) if (d != Direction.DOWN
                && world.getBlockState(pos.relative(d)).is(Blocks.WATER)) return true;
        return false;
    }

    private static boolean reachable(Grid world, BlockPos click, Direction side, boolean pickup) {
        Vec3 point = Vec3.atCenterOf(click);
        if (side != null) point = point.add(Vec3.atLowerCornerOf(side.getUnitVec3i()).scale(.5));
        for (BlockPos p : BlockPos.betweenClosed(click.offset(-4,-3,-4), click.offset(4,2,4))) {
            if (!world.getBlockState(p).isAir() || !world.getBlockState(p.above()).isAir()
                    || !PortalCastPlan.fullBlock(world.getBlockState(p.below()))) continue;
            Vec3 eye = Vec3.atBottomCenterOf(p).add(0,1.62,0);
            Vec3 delta = point.subtract(eye);
            if (delta.length() > 4.35) continue;
            var hit = world.clip(new ClipContext(eye, point.add(delta.normalize().scale(.02)), ClipContext.Block.OUTLINE,
                    pickup ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(click)
                    && (side == null || hit.getDirection() == side)) return true;
        }
        return false;
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    /** Constructor is never called: only the block/fluid API used by vanilla ticks is backed. */
    public static final class Grid extends ServerLevel {
        Map<BlockPos, BlockState> blocks;
        GameRules rules;
        RandomSource randomSource;
        long changes;
        private Grid() { super(null, null, null, null, null, null, false, 0, List.of(), false); }
        void raw(BlockPos p, BlockState state) { blocks.put(p.immutable(), state); }
        @Override public BlockState getBlockState(BlockPos p) { return blocks.getOrDefault(p, Blocks.AIR.defaultBlockState()); }
        @Override public FluidState getFluidState(BlockPos p) { return getBlockState(p).getFluidState(); }
        @Override public GameRules getGameRules() { return rules; }
        @Override public RandomSource getRandom() { return randomSource; }
        @Override public boolean setBlock(BlockPos p, BlockState state, int flags, int recursion) {
            if (getBlockState(p) == state) return false;
            raw(p, state);
            changes++;
            react(p);
            for (Direction d : Direction.values()) react(p.relative(d));
            return true;
        }
        void react(BlockPos p) {
            if (!getBlockState(p).is(Blocks.LAVA)) return;
            try { lavaContact.invoke(Blocks.LAVA, this, p, getBlockState(p)); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        @Override public void scheduleTick(BlockPos p, Fluid fluid, int delay) {}
        @Override public void levelEvent(Entity entity, int type, BlockPos p, int data) {}
        void settle() {
            for (int round = 0; round < 100; round++) {
                long before = changes;
                List<BlockPos> water = blocks.entrySet().stream().filter(e -> e.getValue().is(Blocks.WATER))
                        .map(Map.Entry::getKey).sorted(Comparator.comparingInt((BlockPos p) -> p.getY()).reversed()
                                .thenComparingInt(p -> p.getX()).thenComparingInt(p -> p.getZ())).toList();
                for (BlockPos p : water) {
                    BlockState state = getBlockState(p);
                    if (state.is(Blocks.WATER)) state.getFluidState().tick(this, p, state);
                }
                if (before == changes) return;
            }
            throw new AssertionError("Fluid grid did not settle");
        }
    }
}
