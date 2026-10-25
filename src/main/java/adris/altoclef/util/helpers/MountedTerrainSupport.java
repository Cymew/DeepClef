package adris.altoclef.util.helpers;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Surface support must cover the same footprint as the collision volume. */
public final class MountedTerrainSupport {
    public interface Query {
        double supportTop(double x, double z, double nearY);
        boolean clearAt(Vec3 point);
    }
    public interface SurfaceSampler {
        Vec3 sample(double x, double z, double nearY);
    }

    private MountedTerrainSupport() { }

    public static Vec3 nearestSurface(Vec3 requested, int range, SurfaceSampler sampler) {
        Vec3 best = null;
        for (int dy = -range; dy <= range; dy++) {
            Vec3 point = sampler.sample(requested.x, requested.z, requested.y + dy);
            if (point != null && Math.abs(point.y - requested.y) <= range
                    && (best == null || Math.abs(point.y - requested.y) < Math.abs(best.y - requested.y))) best = point;
        }
        return best;
    }

    /** Keeps the requested column first; only an unsupported column uses bounded neighboring block centers. */
    public static Vec3 nearestSurface(Vec3 requested, int verticalRange, int horizontalRange,
                                      SurfaceSampler sampler) {
        Vec3 sameColumn = nearestSurface(requested, verticalRange, sampler);
        if (sameColumn != null) return sameColumn;
        double baseX = Math.floor(requested.x), baseZ = Math.floor(requested.z);
        for (int radius = 1; radius <= horizontalRange; radius++) {
            Vec3 best = null;
            double bestDistance = Double.POSITIVE_INFINITY;
            // Stable X-then-Z order resolves equal-distance candidates without alternating goals.
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    Vec3 column = new Vec3(baseX + dx + 0.5, requested.y, baseZ + dz + 0.5);
                    Vec3 candidate = nearestSurface(column, verticalRange, sampler);
                    if (candidate == null) continue;
                    double distance = candidate.distanceToSqr(requested);
                    if (distance < bestDistance) {
                        best = candidate;
                        bestDistance = distance;
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    public static Vec3 findSurface(double x, double z, double nearY, AABB body,
                                   double stepHeight, Query query) {
        return findSurface(x, z, nearY, body, stepHeight, 1, query);
    }

    /** Partial foot contact for landing/local adjustment; the caller still owns strict collision and hazard checks. */
    public static Vec3 findContactSurface(double x, double z, double nearY, AABB body,
                                          double stepHeight, double maxDrop, Query query) {
        double halfX = body.getXsize() * 0.5;
        double halfZ = body.getZsize() * 0.5;
        double highest = -Double.MAX_VALUE;
        for (double dx : new double[]{-halfX, 0, halfX}) {
            for (double dz : new double[]{-halfZ, 0, halfZ}) {
                double top = query.supportTop(x + dx, z + dz, nearY);
                if (Double.isFinite(top) && top >= nearY - maxDrop && top <= nearY + stepHeight) {
                    highest = Math.max(highest, top);
                }
            }
        }
        if (highest == -Double.MAX_VALUE) return null;
        Vec3 point = new Vec3(x, highest, z);
        return query.clearAt(point) ? point : null;
    }

    public static Vec3 findSurface(double x, double z, double nearY, AABB body,
                                   double stepHeight, double maxDrop, Query query) {
        // body already carries the navigation collision tolerance. Shrinking
        // these probes again loses rear support before the body clears a ledge.
        double halfX = body.getXsize() * 0.5;
        double halfZ = body.getZsize() * 0.5;
        double highest = -Double.MAX_VALUE, lowest = Double.MAX_VALUE;
        for (double dx : new double[]{-halfX, 0, halfX}) {
            for (double dz : new double[]{-halfZ, 0, halfZ}) {
                double top = query.supportTop(x + dx, z + dz, nearY);
                if (!Double.isFinite(top)) return null;
                highest = Math.max(highest, top);
                lowest = Math.min(lowest, top);
            }
        }
        if (highest - lowest > Math.max(stepHeight, maxDrop) + 0.01
                || highest > nearY + stepHeight + 0.01 || highest < nearY - maxDrop - 0.01) return null;
        Vec3 point = new Vec3(x, highest, z);
        return query.clearAt(point) ? point : null;
    }
}
