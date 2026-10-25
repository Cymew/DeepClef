package adris.altoclef.util.helpers;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Collision geometry shared by the live surface adapter and native-shape regressions. */
public final class BoatTerrainGeometry {
    private BoatTerrainGeometry() { }

    /**
     * Whether a translating box penetrates an obstacle anywhere from t=0 to
     * t=1. A diagonal sweep is a swept volume, not expandTowards' enclosing
     * rectangular prism. The three open slab intervals must overlap at the
     * same time; touching a face, edge or corner alone is not penetration.
     */
    public static boolean sweptIntersects(AABB moving, Vec3 delta, AABB obstacle) {
        if (moving == null || delta == null || obstacle == null
                || !finite(moving) || !finite(obstacle)
                || !Double.isFinite(delta.x) || !Double.isFinite(delta.y) || !Double.isFinite(delta.z))
            throw new IllegalArgumentException("Swept collision requires finite boxes and movement");
        if (moving.minX >= moving.maxX || moving.minY >= moving.maxY || moving.minZ >= moving.maxZ
                || obstacle.minX >= obstacle.maxX || obstacle.minY >= obstacle.maxY || obstacle.minZ >= obstacle.maxZ)
            return false;
        double enter = 0, exit = 1;
        for (int axis = 0; axis < 3; axis++) {
            double min = axis == 0 ? moving.minX : axis == 1 ? moving.minY : moving.minZ;
            double max = axis == 0 ? moving.maxX : axis == 1 ? moving.maxY : moving.maxZ;
            double low = axis == 0 ? obstacle.minX : axis == 1 ? obstacle.minY : obstacle.minZ;
            double high = axis == 0 ? obstacle.maxX : axis == 1 ? obstacle.maxY : obstacle.maxZ;
            double speed = axis == 0 ? delta.x : axis == 1 ? delta.y : delta.z;
            if (speed == 0) {
                if (max <= low || min >= high) return false;
                continue;
            }
            double first = (low - max) / speed, last = (high - min) / speed;
            if (first > last) { double swap = first; first = last; last = swap; }
            enter = Math.max(enter, first);
            exit = Math.min(exit, last);
            if (enter >= exit) return false;
        }
        return true;
    }

    private static boolean finite(AABB box) {
        return Double.isFinite(box.minX) && Double.isFinite(box.minY) && Double.isFinite(box.minZ)
                && Double.isFinite(box.maxX) && Double.isFinite(box.maxY) && Double.isFinite(box.maxZ);
    }

    public static boolean isIce(BlockState state) {
        return state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE)
                || state.is(Blocks.BLUE_ICE) || state.is(Blocks.FROSTED_ICE);
    }

    public static AABB riderEnvelope(AABB hull, AABB actualRider, Vec3 origin) {
        AABB actual = actualRider.deflate(.015);
        double half = Math.max(hull.getXsize() * .5, actual.getXsize() * .5 + .25);
        // Vanilla places a seated player's feet below the hull bottom (boat:
        // .5625/3 - .6 = -.4125). Those legs are not another land collision body.
        // The whole hull still sweeps the floor and the rider's torso/head stay checked.
        double bottom = Math.max(hull.minY, actual.minY);
        return new AABB(origin.x - half, bottom, origin.z - half,
                origin.x + half, Math.max(bottom + .01, actual.maxY + .12), origin.z + half);
    }

    public static double supportHeight(AABB footprint, double nearY, Iterable<VoxelShape> collisions) {
        double floor = -Double.MAX_VALUE;
        for (VoxelShape shape : collisions) for (AABB box : shape.toAabbs()) {
            if (box.maxX > footprint.minX && box.minX < footprint.maxX
                    && box.maxZ > footprint.minZ && box.minZ < footprint.maxZ
                    && box.maxY >= nearY - .8 && box.maxY <= nearY + 1e-5)
                floor = Math.max(floor, box.maxY);
        }
        return floor;
    }
}
