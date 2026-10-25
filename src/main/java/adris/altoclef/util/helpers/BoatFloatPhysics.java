package adris.altoclef.util.helpers;

import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

/** The safe, non-submerged branches of 26.3 AbstractBoat.floatBoat, before control/move. */
public final class BoatFloatPhysics {
    public enum Status { IN_WATER, ON_LAND, IN_AIR }

    /** Position includes only the native air-to-water snap, never normal movement. */
    public record Step(Vec3 position, double verticalVelocity, double retention, Status status, boolean snapped) { }

    private BoatFloatPhysics() { }

    /**
     * waterLevel is the status sampler's highest water surface. waterLevelAbove
     * is the float result of the native falling-water query, not always the same
     * value. Height likewise stays float to preserve the entry subtraction.
     * The caller detects status using the actual full hull, rejects submerged or
     * dangerous fluids, and resolves the eventual Y/XZ movement with native
     * collisions. canEnterWaterAt tests the candidate snap without moving anything.
     * Horizontal/angular retention is consumed exactly once by BoatSteering.step.
     */
    public static Step beforeControl(Vec3 position, double verticalVelocity, Status oldStatus, Status status,
                                     float height, double waterLevel, float waterLevelAbove,
                                     double gravity, double defaultGravity, double groundFriction,
                                     Predicate<Vec3> canEnterWaterAt) {
        if (position == null || !Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)
                || !Double.isFinite(verticalVelocity) || oldStatus == null || status == null
                || !Float.isFinite(height) || height <= 0 || !Double.isFinite(gravity) || gravity < 0
                || !Double.isFinite(defaultGravity) || defaultGravity < 0) {
            throw new IllegalArgumentException("Invalid boat float state");
        }
        if (oldStatus == Status.IN_AIR && status == Status.IN_WATER) {
            if (!Float.isFinite(waterLevelAbove) || canEnterWaterAt == null) {
                throw new IllegalArgumentException("Air-to-water entry needs a known clear snap query");
            }
            Vec3 snapped = new Vec3(position.x, (double) (waterLevelAbove - height) + .101, position.z);
            // This entire native branch bypasses friction, gravity and buoyancy,
            // even if noCollision refuses the position change.
            boolean accepted = canEnterWaterAt.test(snapped);
            return new Step(accepted ? snapped : position, accepted ? 0 : verticalVelocity,
                    1, Status.IN_WATER, accepted);
        }
        double velocity = verticalVelocity - gravity;
        double retention;
        if (status == Status.IN_WATER) {
            if (!Double.isFinite(waterLevel)) throw new IllegalArgumentException("Water surface is unknown");
            retention = (double) .9f;
            double buoyancy = (waterLevel - position.y) / (double) height;
            if (buoyancy > 0) velocity = (velocity + buoyancy * (defaultGravity / .65)) * .75;
        } else if (status == Status.ON_LAND) {
            if (!Double.isFinite(groundFriction) || groundFriction < 0 || groundFriction > 1) {
                throw new IllegalArgumentException("Ground friction is unknown");
            }
            // In vanilla the player's landFriction/=2 assignment happens after
            // invFriction has been selected. It does not halve this tick's drag.
            retention = (double) (float) groundFriction;
        } else {
            retention = (double) .9f;
        }
        return new Step(position, velocity, retention, status, false);
    }
}
