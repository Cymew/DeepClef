package adris.altoclef.mining;

import adris.altoclef.tasks.resources.DirtMiningTerrain;
import adris.altoclef.tasks.resources.DirtProgress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.HashSet;

final class DirtPitRegression {
    private static final class Terrain implements BlockGetter {
        private final int[][] depths;
        private final Map<BlockPos, BlockState> edits = new HashMap<>();
        Terrain(int[][] depths) { this.depths = depths; }
        @Override public BlockState getBlockState(BlockPos p) {
            int x = p.getX(), z = p.getZ();
            int depth = z >= 0 && z < depths.length && x >= 0 && x < depths[z].length ? depths[z][x] : 0;
            return edits.getOrDefault(p, (p.getY() < -depth ? Blocks.DIRT : Blocks.AIR).defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos p) { return getBlockState(p).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos p) { return null; }
        @Override public int getMinY() { return -64; }
        @Override public int getHeight() { return 384; }

        boolean reachable(BlockPos feet, BlockPos p) {
            Vec3 eye = Vec3.atBottomCenterOf(feet).add(0, 1.62, 0);
            var hit = clip(new ClipContext(eye, Vec3.atCenterOf(p), ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, CollisionContext.empty()));
            return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(p)
                    && eye.distanceTo(hit.getLocation()) <= 4.5;
        }
    }

    static void run() {
        for (int depth : new int[]{2, 3}) {
            replay(new int[][]{{depth, depth}}, 0, 0, true);
            replay(new int[][]{{depth}, {depth}}, 0, 1, true);
        }
        for (int depth : new int[]{1, 11}) {
            int[][] pit = {{1,1,1,1}, {1,2,3,1}, {depth,3,2,1}, {1,1,1,1}};
            replay(pit, 2, 1, false);
            replay(pit, 1, 2, false);
            if (depth == 11) replay(pit, 0, 2, true);
        }
        Terrain flat = new Terrain(new int[][]{});
        check(DirtMiningTerrain.stepOut(flat, BlockPos.ZERO, p -> true, p -> true) == null, "leave open ground alone");
        check(DirtMiningTerrain.localTarget(BlockPos.ZERO, BlockPos.ZERO.below()::equals).isEmpty(), "keep player's floor");
        Terrain shaft = new Terrain(new int[][]{{3,3}});
        BlockPos bottom = new BlockPos(0,-3,0);
        check(DirtMiningTerrain.stepOut(shaft, bottom, p -> true, p -> false) == null, "respect protected banks");
        check(DirtMiningTerrain.stepOut(shaft, bottom, p -> false, p -> true) == null, "stay inside loaded terrain");
        DirtProgress progress = new DirtProgress();
        boolean stalled = false;
        for (int i = 0; i < 200; i++) {
            Vec3 around = new Vec3(Math.cos(i), 0, Math.sin(i));
            stalled = progress.stalled(BlockPos.ZERO, around, (i % 5) * .001);
        }
        check(stalled, "circling and repeating early damage eventually expires");
        for (int i = 0; i < 400; i++) {
            check(!progress.stalled(bottom, Vec3.ZERO, i / 500.0), "slow continuous mining must finish");
        }
        System.out.println("Dirt pit replays passed (narrow pits, enlarged pits, support and progress checks)");
    }

    private static void replay(int[][] depths, int x, int z, boolean needsDig) {
        Terrain world = new Terrain(depths);
        BlockPos feet = new BlockPos(x, -depths[z][x], z);
        int cuts = 0;
        for (int action = 0; action < 64 && feet.getY() < 0; action++) {
            BlockPos start = feet;
            var step = DirtMiningTerrain.stepOut(world, start, p -> true, p -> world.reachable(start, p));
            check(step != null, "found a step from " + start);
            if (step.obstruction() != null) {
                check(step.obstruction().getY() > feet.getY(), "cut headroom, preserve ledge");
                check(world.reachable(feet, step.obstruction()), "cut is in reach and visible");
                world.edits.put(step.obstruction(), Blocks.AIR.defaultBlockState());
                cuts++;
            } else {
                check(step.stand().getY() == feet.getY() + 1, "single-block climb");
                check(canWalkToStep(world, feet, step.stand()), "can walk to take-off point with enough jump clearance");
                check(world.getBlockState(step.stand()).isAir() && world.getBlockState(step.stand().above()).isAir()
                        && world.getBlockState(step.stand().below()).is(Blocks.DIRT), "supported landing with headroom");
                feet = step.stand();
            }
        }
        check(feet.getY() == 0, "returned to surface");
        check((cuts > 0) == needsDig, "use existing stairs before cutting dirt");
        BlockPos surface = feet;
        var next = DirtMiningTerrain.localTarget(surface, p -> world.getBlockState(p).is(Blocks.DIRT) && world.reachable(surface, p));
        check(next.isPresent() && !next.get().equals(surface.below()), "resume gathering from safe footing");
    }

    private static boolean canWalkToStep(Terrain world, BlockPos start, BlockPos landing) {
        var queue = new ArrayDeque<BlockPos>();
        var seen = new HashSet<BlockPos>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            BlockPos feet = queue.remove();
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos next = feet.relative(dir);
                if (next.above().equals(landing) && world.getBlockState(feet.above(2)).isAir()) return true;
                if (next.distSqr(start) > 32 || !seen.add(next)) continue;
                if (world.getBlockState(next.below()).is(Blocks.DIRT) && world.getBlockState(next).isAir()
                        && world.getBlockState(next.above()).isAir()) queue.add(next);
            }
        }
        return false;
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
