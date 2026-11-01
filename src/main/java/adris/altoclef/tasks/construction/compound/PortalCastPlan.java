package adris.altoclef.tasks.construction.compound;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Collection;
import java.util.function.Function;

/** Geometry shared by site selection, construction and the fluid regression fixture.
 * Origin is the left cell of the two-cell drain, at the original lava surface.
 * Local X runs along the shore, Z points into the pool; the portal is at Z=0.
 */
public final class PortalCastPlan {
    public record Placement(BlockPos target, BlockPos support, Direction face) {}

    public final BlockPos origin;
    public final Direction forward;
    public final Direction right;
    public final boolean poolEdge;
    private final List<BlockPos> mold;
    private final List<Placement> frame;
    private final List<BlockPos> interior;
    private final Set<BlockPos> frameCells;
    private final Set<BlockPos> fluidCells;
    private final Set<BlockPos> bankCells;

    public PortalCastPlan(BlockPos origin, Direction forward, boolean poolEdge) {
        if (forward.getAxis() == Direction.Axis.Y) throw new IllegalArgumentException("Horizontal shore required");
        this.origin = origin.immutable();
        this.forward = forward;
        this.right = forward.getCounterClockWise();
        this.poolEdge = poolEdge;
        mold = buildMold();
        frame = buildFrame();
        interior = buildInterior();
        frameCells = new HashSet<>();
        frame.forEach(p -> frameCells.add(p.target()));
        fluidCells = new HashSet<>(frameCells);
        fluidCells.addAll(interior);
        for (int x = 0; x < 2; x++) for (int y = 1; y <= 3; y++) {
            BlockPos p = at(x, y, -1);
            if (!mold.contains(p)) fluidCells.add(p);
        }
        bankCells = new HashSet<>();
        for (int x = -1; x <= 2; x++) for (int z = -2; z <= -1; z++) {
            bankCells.add(at(x, 0, z));
            bankCells.add(at(x, -1, z));
        }
    }

    public BlockPos at(int x, int y, int z) {
        return origin.relative(right, x).above(y).relative(forward, z);
    }

    public BlockPos starter() { return at(0, 0, 0); }
    public BlockPos primerWater() { return at(1, 0, 0); }
    public BlockPos waterSource() { return at(0, 3, -1); }

    public List<BlockPos> mold() { return mold; }
    public List<Placement> frame() { return frame; }
    public List<BlockPos> interior() { return interior; }
    public boolean isBank(BlockPos pos) { return bankCells.contains(pos); }
    public Set<BlockPos> channels() {
        Set<BlockPos> result = new HashSet<>(fluidCells);
        result.removeAll(frameCells);
        return result;
    }

    private List<BlockPos> buildMold() {
        List<BlockPos> result = new ArrayList<>();
        // Back wall prevents the source and its lower outflow escaping over uneven land.
        for (int y = 1; y <= 3; y++) {
            result.add(at(-1, y, -1));
            result.add(at(2, y, -1));
            result.add(at(0, y, -2));
            result.add(at(1, y, -2));
        }
        result.add(at(1, 1, -1)); // Step directs the right stream into the drain.
        result.add(at(-1, 3, 0)); // Overhang / first top casting face.
        return List.copyOf(result);
    }

    private List<Placement> buildFrame() {
        List<Placement> result = new ArrayList<>();
        for (int x : new int[]{-1, 2}) {
            for (int y = 0; y < 3; y++) {
                result.add(new Placement(at(x, y, 0), at(x, y, -1), forward));
            }
        }
        // Finish the shallow-pool bottom while the entire curtain is still flowing.
        for (int x = 0; x < 2; x++) {
            result.add(new Placement(at(x, -1, 0), at(x, -1, -1), forward));
        }
        result.add(new Placement(at(0, 3, 0), at(-1, 3, 0), right));
        result.add(new Placement(at(1, 3, 0), at(0, 3, 0), right));
        return List.copyOf(result);
    }

    private List<BlockPos> buildInterior() {
        List<BlockPos> result = new ArrayList<>();
        for (int y = 0; y < 3; y++) for (int x = 0; x < 2; x++) result.add(at(x, y, 0));
        return List.copyOf(result);
    }

    /** Cells consumed by the initial pour, including its two displaced sources. */
    public List<BlockPos> primedCells() {
        return List.of(starter(), primerWater(), at(-1, 0, 0), at(2, 0, 0),
                at(0, 0, 1), at(1, 0, 1), at(0, -1, 0), at(1, -1, 0));
    }

    public boolean isFrame(BlockPos pos) {
        return frameCells.contains(pos);
    }

    /** Leave the drain, water channels and frame available to fluids, not path scaffolding. */
    public boolean isFluidSpace(BlockPos pos) {
        return fluidCells.contains(pos);
    }

    public static boolean lavaSource(BlockState state) {
        return state.is(Blocks.LAVA) && state.getFluidState().isSource();
    }

    public static boolean fullBlock(BlockState state) {
        return state.isSolidRender() && state.getFluidState().isEmpty();
    }

    /** Conservative geometry check: no clearing an unknown roof or spilling behind a cliff. */
    public boolean fits(Function<BlockPos, BlockState> world) {
        for (int x = -1; x <= 2; x++) {
            for (int z = -2; z <= -1; z++) {
                if (!fullBlock(world.apply(at(x, 0, z)))) return false;
            }
            for (int y = 1; y <= 4; y++) for (int z = -2; z <= 1; z++) {
                if (!world.apply(at(x, y, z)).isAir()) return false;
            }
            BlockState surface = world.apply(at(x, 0, 0));
            if (poolEdge ? !lavaSource(surface) : !fullBlock(surface)) return false;
            if (surface.is(Blocks.BEDROCK)) return false;
        }
        for (int x = 0; x < 2; x++) {
            BlockState front = world.apply(at(x, 0, 1));
            if (poolEdge ? !lavaSource(front) : !fullBlock(front)) return false;
            BlockState bottom = world.apply(at(x, -1, 0));
            if (!lavaSource(bottom) && (!fullBlock(bottom) || bottom.is(Blocks.BEDROCK))) return false;
            if (!lavaSource(bottom) && !fullBlock(world.apply(at(x, -2, 0)))) return false;
            // The bank supplies a clickable face for both bottom lava placements.
            if (!fullBlock(world.apply(at(x, -1, -1)))) return false;
        }
        return true;
    }

    public int poursNeededAfterPriming(Function<BlockPos, BlockState> world) {
        if (!poolEdge) return (int) frame.stream().filter(p -> !world.apply(p.target()).is(Blocks.OBSIDIAN)).count();
        int already = 2; // The two lower side blocks touch the priming water.
        for (int x = 0; x < 2; x++) {
            BlockState floor = world.apply(at(x, -1, 0));
            if (lavaSource(floor) || floor.is(Blocks.OBSIDIAN)) already++;
        }
        return 10 - already;
    }

    public boolean hasEnoughLava(Collection<BlockPos> exposedSources, Function<BlockPos, BlockState> world) {
        List<BlockPos> consumed = poolEdge ? primedCells() : List.of();
        long available = exposedSources.stream().distinct().filter(p -> p.distSqr(origin) <= 16 * 16)
                .filter(p -> !consumed.contains(p) && lavaSource(world.apply(p))
                        && world.apply(p.above()).isAir()).count();
        return available >= poursNeededAfterPriming(world);
    }
}
