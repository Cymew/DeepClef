package adris.altoclef.util.helpers;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Chooses an exposed part of a target without changing player rotation or world state. */
public final class SpearAim {
    private static final double[] HORIZONTAL_SAMPLES = {0.08, 0.5, 0.92};
    private static final double[] VERTICAL_SAMPLES = {0.08, 0.25, 0.5, 0.75, 0.92};
    private static final double POINT_EPSILON_SQUARED = 1.0e-12;

    public record Solution(Vec3 point, boolean hittable, boolean visible) { }

    private SpearAim() { }

    /** Callbacks receive world-space aim points and own all range/occlusion decisions. */
    public static Solution select(Vec3 eye, Vec3 look, AABB box, Vec3 preferred,
                                  Predicate<Vec3> canHit, Predicate<Vec3> canSee) {
        Vec3 canonical = SpearHelper.aimPoint(eye, box);
        boolean hasPreferred = preferred != null && box.contains(preferred);
        if (hasPreferred && canHit.test(preferred)) return new Solution(preferred, true, true);
        if (canHit.test(canonical)) return new Solution(canonical, true, true);

        List<Vec3> candidates = new ArrayList<>();
        addUnique(candidates, canonical);
        if (hasPreferred) addUnique(candidates, preferred);
        Vec3 direction = look.normalize();
        if (direction.lengthSqr() < POINT_EPSILON_SQUARED) {
            direction = canonical.subtract(eye).normalize();
        }
        double rayLength = eye.distanceTo(box.getCenter())
                + Math.sqrt(box.getXsize() * box.getXsize() + box.getYsize() * box.getYsize()
                + box.getZsize() * box.getZsize()) + 1;
        box.clip(eye, eye.add(direction.scale(rayLength)))
                .ifPresent(point -> addUnique(candidates, point.lerp(box.getCenter(), 0.001)));
        for (double x : HORIZONTAL_SAMPLES) {
            for (double z : HORIZONTAL_SAMPLES) {
                double sampleX = box.minX + box.getXsize() * x;
                double sampleZ = box.minZ + box.getZsize() * z;
                addUnique(candidates, new Vec3(sampleX, canonical.y, sampleZ));
                for (double y : VERTICAL_SAMPLES) {
                    addUnique(candidates, new Vec3(sampleX, box.minY + box.getYsize() * y, sampleZ));
                }
            }
        }
        Vec3 rankingDirection = direction;
        candidates.sort(Comparator.comparingDouble(point ->
                -point.subtract(eye).normalize().dot(rankingDirection)));
        for (Vec3 point : candidates) {
            if (samePoint(point, canonical) || hasPreferred && samePoint(point, preferred)) continue;
            if (canHit.test(point)) return new Solution(point, true, true);
        }
        // Beyond attack range, retain the stable body point whenever it is visible.
        if (canSee.test(canonical)) return new Solution(canonical, false, true);
        for (Vec3 point : candidates) {
            if (!samePoint(point, canonical) && canSee.test(point)) return new Solution(point, false, true);
        }
        return new Solution(canonical, false, false);
    }

    /** Converts a point into a clamped, normalized position inside the target box. */
    public static Vec3 anchor(Vec3 point, AABB box) {
        return new Vec3(fraction(point.x, box.minX, box.getXsize()),
                fraction(point.y, box.minY, box.getYsize()),
                fraction(point.z, box.minZ, box.getZsize()));
    }

    /** Reconstructs the same relative point after the target moves or changes size. */
    public static Vec3 pointAt(AABB box, Vec3 anchor) {
        return new Vec3(box.minX + box.getXsize() * clampUnit(anchor.x),
                box.minY + box.getYsize() * clampUnit(anchor.y),
                box.minZ + box.getZsize() * clampUnit(anchor.z));
    }

    private static double fraction(double value, double minimum, double extent) {
        return extent > 1.0e-12 ? clampUnit((value - minimum) / extent) : 0.5;
    }

    private static double clampUnit(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static void addUnique(List<Vec3> points, Vec3 point) {
        for (Vec3 existing : points) {
            if (samePoint(existing, point)) return;
        }
        points.add(point);
    }

    private static boolean samePoint(Vec3 first, Vec3 second) {
        return first.distanceToSqr(second) < POINT_EPSILON_SQUARED;
    }
}
