package adris.altoclef.tasks.resources;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/** Small, level-ground search used only when gathering ordinary dirt. */
public final class DirtMiningTerrain {
    private static final int RADIUS = 4;
    private static final List<BlockPos> HARVEST_OFFSETS = harvestOffsets();

    public record Step(BlockPos stand, BlockPos obstruction) {}

    static boolean supportsPlayer(BlockPos feet, BlockPos block) {
        // Dirt paths are slightly shorter than a block, so the feet can occupy the support cell itself.
        return block.getX() == feet.getX() && block.getZ() == feet.getZ() && block.getY() <= feet.getY();
    }

    private static List<BlockPos> harvestOffsets() {
        var offsets = new ArrayList<BlockPos>();
        for (BlockPos offset : BlockPos.betweenClosed(-4, -1, -4, 4, 3, 4)) {
            if (!supportsPlayer(BlockPos.ZERO, offset)) offsets.add(offset.immutable());
        }
        // Harvest a face in the bank before removing more of the floor.
        offsets.sort(Comparator.comparingInt((BlockPos p) -> p.getY() < 0 ? 1 : 0)
                .thenComparingDouble(p -> p.distSqr(BlockPos.ZERO)));
        return List.copyOf(offsets);
    }

    public static Optional<BlockPos> localTarget(BlockPos feet, Predicate<BlockPos> canMineFromHere) {
        for (BlockPos offset : HARVEST_OFFSETS) {
            BlockPos target = feet.offset(offset);
            if (canMineFromHere.test(target)) return Optional.of(target);
        }
        return Optional.empty();
    }

    private static boolean footing(BlockGetter world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getFluidState().isEmpty() && state.isCollisionShapeFullBlock(world, pos)
                && !state.is(Blocks.MAGMA_BLOCK) && !state.is(Blocks.CACTUS);
    }

    static boolean canStand(BlockGetter world, BlockPos feet) {
        return footing(world, feet.below()) && world.getBlockState(feet).isAir() && world.getBlockState(feet.above()).isAir();
    }

    /** Flood only the current walkable floor. A level way out beats climbing, which beats digging. */
    public static Step stepOut(BlockGetter world, BlockPos origin, Predicate<BlockPos> loaded,
                               Predicate<BlockPos> mayCut) {
        if (!canStand(world, origin)) return null;
        var queue = new ArrayDeque<BlockPos>();
        var visited = new HashSet<BlockPos>();
        queue.add(origin);
        visited.add(origin);
        Step climb = null;
        var cuts = new ArrayList<Step>();
        while (!queue.isEmpty()) {
            BlockPos feet = queue.remove();
            if (Math.max(Math.abs(feet.getX() - origin.getX()), Math.abs(feet.getZ() - origin.getZ())) >= RADIUS) {
                return null;
            }
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos adjacent = feet.relative(dir);
                if (!loaded.test(adjacent)) continue;
                if (world.getBlockState(adjacent).isAir() && world.getBlockState(adjacent.above()).isAir()
                        && footing(world, adjacent.below())) {
                    if (visited.add(adjacent)) queue.add(adjacent);
                    continue;
                }
                if (!footing(world, adjacent)) continue;
                BlockPos stand = adjacent.above();
                // A jump needs extra headroom over its take-off cell as well as its landing.
                BlockPos ceiling = feet.above(2);
                if (!world.getBlockState(ceiling).isAir()) {
                    if (dirt(world.getBlockState(ceiling)) && openBank(world, ceiling) && openBank(world, stand)) {
                        cuts.add(new Step(stand, ceiling));
                    }
                    continue;
                }
                if (world.getBlockState(stand).isAir() && world.getBlockState(stand.above()).isAir()) {
                    if (climb == null) climb = new Step(stand, null);
                    continue;
                }
                // Open a step toward a verified gap. Keep the solid block we will jump onto.
                if (!openBank(world, stand)) continue;
                BlockPos cut = world.getBlockState(stand).isAir() ? stand.above() : stand;
                cuts.add(new Step(stand, cut));
            }
        }
        if (climb != null) return climb;
        for (Step step : cuts) {
            if (mayCut.test(step.obstruction())) return step;
        }
        return null;
    }

    static boolean dirt(BlockState state) {
        return state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT_PATH);
    }

    private static boolean openBank(BlockGetter world, BlockPos base) {
        for (int dy = 0; dy <= 12; dy++) {
            BlockPos check = base.above(dy);
            var state = world.getBlockState(check);
            if (state.isAir() && world.getBlockState(check.above()).isAir()) return true;
            if (!state.isAir() && !dirt(state)) return false;
        }
        return false;
    }
}
