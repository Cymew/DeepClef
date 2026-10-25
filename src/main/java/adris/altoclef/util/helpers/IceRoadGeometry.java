package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LilyPadBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Same-height ice-road observations and vanilla boat-foot contact geometry. */
public final class IceRoadGeometry {
    public enum Cell { ICE, AIR, BLOCKED, UNKNOWN }

    /** No reads may generate/load a chunk. The live caller can supply its real collision view. */
    public interface Blocks {
        boolean loaded(int x, int z);
        BlockState block(int x, int y, int z);
        default BlockGetter collisionView() { return null; }
    }

    public record Contact(boolean loaded, int ice, int other, double friction) {
        public boolean supported() { return loaded && ice + other > 0; }
        public boolean iceOnly() { return loaded && ice > 0 && other == 0; }
    }

    private static final double CONTACT_DEPTH = .001;
    private static final double SWEEP_STEP = .125;
    private IceRoadGeometry() { }

    /**
     * Permit escape from a mixed ice/non-ice border without adding new drag.
     * A translated hull can cover two or three block rows at the same material
     * ratio, so comparing the absolute count of non-ice contacts would stall it.
     */
    public static boolean allowsRecovery(Contact before, Contact after) {
        if (before == null || after == null || !before.loaded() || !after.loaded()
                || before.ice() < 0 || before.other() < 0 || after.ice() <= 0 || after.other() < 0) return false;
        long beforeTotal = (long)before.ice() + before.other();
        long afterTotal = (long)after.ice() + after.other();
        return beforeTotal > 0 && (long)after.other() * beforeTotal <= (long)before.other() * afterTotal;
    }

    /** Four native ice variants share the graph classification, while friction remains native. */
    public static Cell cell(Blocks blocks, int x, int floorY, int z, int airLayers) {
        if (blocks == null || !blocks.loaded(x, z)) return Cell.UNKNOWN;
        BlockState floor = blocks.block(x, floorY, z);
        if (floor == null) return Cell.UNKNOWN;
        for (int layer = 1; layer <= airLayers; layer++) {
            BlockState above = blocks.block(x, floorY + layer, z);
            if (above == null) return Cell.UNKNOWN;
            if (!above.isAir()) return Cell.BLOCKED;
        }
        if (BoatTerrainGeometry.isIce(floor)) return Cell.ICE;
        return floor.isAir() ? Cell.AIR : Cell.BLOCKED;
    }

    /**
     * Matches AbstractBoat.getGroundFriction: intersect the bottom .001 slab
     * with native collision shapes, count each touching block once, ignore air
     * and lily pads, and average contacted block friction without area weights.
     * This observes support; complete hull/rider sweeps remain the live caller's job.
     */
    public static Contact contact(Blocks blocks, Vec3 foot, double halfWidth) {
        if (blocks == null || !finite(foot) || !Double.isFinite(halfWidth) || halfWidth <= 0)
            return new Contact(false, 0, 0, Double.NaN);
        AABB support = new AABB(foot.x - halfWidth, foot.y - CONTACT_DEPTH, foot.z - halfWidth,
                foot.x + halfWidth, foot.y, foot.z + halfWidth);
        int x0 = Mth.floor(support.minX) - 1, x1 = Mth.ceil(support.maxX) + 1;
        int y0 = Mth.floor(support.minY) - 1, y1 = Mth.ceil(support.maxY) + 1;
        int z0 = Mth.floor(support.minZ) - 1, z1 = Mth.ceil(support.maxZ) + 1;
        // Native shapes may project beyond their block, so preserve the native
        // one-block scan border instead of reducing this to just floor cells.
        for (int x = x0; x < x1; x++) for (int z = z0; z < z1; z++)
            if (!blocks.loaded(x, z)) return new Contact(false, 0, 0, Double.NaN);
        BlockGetter view = blocks.collisionView();
        if (view == null) view = new ObservationView(blocks);
        int ice = 0, other = 0;
        float friction = 0;
        for (int x = x0; x < x1; x++) for (int z = z0; z < z1; z++) {
            int edges = (x == x0 || x == x1 - 1 ? 1 : 0) + (z == z0 || z == z1 - 1 ? 1 : 0);
            if (edges == 2) continue;
            for (int y = y0; y < y1; y++) {
                if (edges > 0 && (y == y0 || y == y1 - 1)) continue;
                BlockState state = blocks.block(x, y, z);
                if (state == null) return new Contact(false, 0, 0, Double.NaN);
                if (state.getBlock() instanceof LilyPadBlock) continue;
                BlockPos pos = new BlockPos(x, y, z);
                boolean touches = false;
                for (AABB shape : state.getCollisionShape(view, pos).toAabbs()) {
                    if (shape.move(pos).intersects(support)) { touches = true; break; }
                }
                if (!touches) continue;
                if (BoatTerrainGeometry.isIce(state)) ice++; else other++;
                friction += state.getBlock().getFriction();
            }
        }
        return new Contact(true, ice, other, ice + other == 0 ? Double.NaN : friction / (ice + other));
    }

    /**
     * Gap fallback only: every sampled foot must retain actual same-height ice
     * contact. One-air-cell bridges can pass; unsupported wider holes cannot.
     * The route still needs a separate full hull/rider collision sweep.
     */
    public static boolean sweepSupported(Blocks blocks, Vec3 from, Vec3 to, double halfWidth) {
        if (!finite(from) || !finite(to) || Math.abs(from.y - to.y) > 1e-5) return false;
        double length = from.distanceTo(to);
        if (length > SWEEP_STEP * 32768) return false;
        int steps = Math.max(1, (int)Math.ceil(length / SWEEP_STEP));
        for (int sample = 0; sample <= steps; sample++)
            if (!contact(blocks, from.lerp(to, (double)sample / steps), halfWidth).iceOnly()) return false;
        return true;
    }

    /** True only when the complete hull footprint plus the requested margin remains over clear ice. */
    public static boolean fullIceMargin(Blocks blocks, Vec3 foot, double halfWidth, double margin, int airLayers) {
        if (!finite(foot) || !Double.isFinite(halfWidth) || halfWidth <= 0 || !Double.isFinite(margin) || margin < 0) return false;
        int floorY = Mth.floor(foot.y - 1e-6);
        if (Math.abs(floorY + 1 - foot.y) > 1e-4) return false;
        double extent = halfWidth + margin;
        for (int x = Mth.floor(foot.x - extent + 1e-7); x <= Mth.floor(foot.x + extent - 1e-7); x++)
            for (int z = Mth.floor(foot.z - extent + 1e-7); z <= Mth.floor(foot.z + extent - 1e-7); z++)
                if (cell(blocks, x, floorY, z, airLayers) != Cell.ICE) return false;
        return true;
    }

    private static boolean finite(Vec3 point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }

    /** Pure fixtures read the same states; the live adapter should return its Level instead. */
    private record ObservationView(Blocks blocks) implements BlockGetter {
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) {
            BlockState state = blocks.block(pos.getX(), pos.getY(), pos.getZ());
            return state == null ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState() : state;
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getMinY() { return -1_000_000; }
        @Override public int getHeight() { return 2_000_000; }
    }
}
