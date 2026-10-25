package adris.altoclef.util.helpers;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Collision-free ordinary-horse jump prediction, along local positive Z.
 * The caller checks the swept horse/rider volumes, landing support and hazards.
 * This class never changes entities, inputs, attributes or packets.
 */
public final class MountedJumpPhysics {
    private static final double MOTION_EPSILON = 0.003;
    private static final int MAX_SIMULATION_TICKS = 1000;

    /**
     * launchPower already includes the actual charge, block jump factor and jump
     * boost. Friction/drag are the effective values after attribute modifiers.
     * forwardInput is the rider's positive getRiddenInput Z (normally 0.98), not
     * the horse movement attribute. Tick zero is the tick executing the jump.
     * forwardStartTick zero gives the vanilla forward launch impulse; a later
     * value starts with zero forward input and adds forward acceleration in air.
     */
    public record Parameters(double launchPower, double movementSpeed, double gravity,
                             double groundFriction, double airDrag, double verticalDrag,
                             double initialForwardSpeed, double forwardInput,
                             double chargeScale, int forwardStartTick, int maxTicks) { }

    /** positions[0] is the origin; subsequent entries are full movement ticks. */
    public record Trajectory(List<Vec3> positions, double apexHeight, int apexTick,
                             int landingTick, double landingDistance) {
        public Trajectory { positions = List.copyOf(positions); }
        public boolean landed() { return landingTick >= 0; }
    }

    private MountedJumpPhysics() { }

    /** Mirrors LivingEntity's protected getJumpPower(float) using readable inputs. */
    public static float launchPower(double jumpStrength, float chargeScale,
                                    float blockJumpFactor, float jumpBoostPower) {
        return (float) jumpStrength * chargeScale * blockJumpFactor + jumpBoostPower;
    }

    /** Mirrors the effective friction/air-drag modifier calculation. */
    public static float modifiedFriction(float base, float modifier) {
        return Math.max(0.0f, Math.min(1.0f, 1.0f - (1.0f - base) * modifier));
    }

    /** These environments require a different motion model and must not be guessed. */
    public static Optional<String> environmentRejection(boolean inFluid, boolean levitation,
                                                         boolean slowFalling, boolean noGravity) {
        if (inFluid) return Optional.of("Horse jumps in fluid need the fluid movement model.");
        if (levitation) return Optional.of("Levitation changes the vertical acceleration each tick.");
        if (slowFalling) return Optional.of("Slow Falling changes gravity during descent.");
        if (noGravity) return Optional.of("A no-gravity mount has no ordinary jump landing.");
        return Optional.empty();
    }

    /** Checks inputs rather than substituting fixed horse height/range limits. */
    public static Optional<String> unsupportedReason(Parameters p) {
        if (p == null) return Optional.of("Jump parameters are missing.");
        if (!finite(p.launchPower, p.movementSpeed, p.gravity, p.groundFriction,
                p.airDrag, p.verticalDrag, p.initialForwardSpeed, p.forwardInput, p.chargeScale)) {
            return Optional.of("Jump parameters must be finite.");
        }
        if (p.launchPower <= 0) return Optional.of("The actual launch power must be positive.");
        if (p.movementSpeed < 0) return Optional.of("Movement speed must not be negative.");
        if (p.gravity <= 0) return Optional.of("Nonpositive gravity is not an ordinary horse jump.");
        if (!unit(p.groundFriction) || !unit(p.airDrag) || !unit(p.verticalDrag)) {
            return Optional.of("Effective friction and drag must be in [0, 1].");
        }
        if (!unit(p.forwardInput)) return Optional.of("Only forward input in [0, 1] is modeled.");
        if (p.chargeScale < 0.4 || p.chargeScale > 1) {
            return Optional.of("Ordinary horse charge scale must be in [0.4, 1].");
        }
        if (p.maxTicks < 1 || p.maxTicks > MAX_SIMULATION_TICKS) {
            return Optional.of("Jump prediction needs 1 to 1000 bounded ticks.");
        }
        if (p.forwardStartTick < 0 || p.forwardStartTick > p.maxTicks) {
            return Optional.of("Forward input must begin inside the simulated tick interval.");
        }
        // Vanilla attribute ranges fit floats. Reject overflow instead of
        // producing infinite positions that could bypass collision checks.
        if (!Float.isFinite((float) p.launchPower) || !Float.isFinite((float) p.movementSpeed)
                || !Float.isFinite((float) p.gravity) || !Float.isFinite((float) p.initialForwardSpeed)) {
            return Optional.of("Jump parameters exceed the finite movement calculation range.");
        }
        return Optional.empty();
    }

    /**
     * Integrates movement before gravity/drag, just like travelInAir. The first
     * jump tick still uses its starting grounded friction and acceleration;
     * subsequent ticks use the ridden airborne speed (movementSpeed * 0.1).
     * Includes the first point at/below takeoff height, or stops at maxTicks.
     */
    public static Trajectory simulate(Parameters p) {
        return simulateToHeight(p, 0);
    }

    /** Includes the first descending sample crossing the requested relative height. */
    public static Trajectory simulateToHeight(Parameters p, double landingHeight) {
        unsupportedReason(p).ifPresent(reason -> { throw new IllegalArgumentException(reason); });
        if (!Double.isFinite(landingHeight)) throw new IllegalArgumentException("Landing height must be finite.");
        List<Vec3> positions = new ArrayList<>(p.maxTicks + 1);
        positions.add(Vec3.ZERO);
        float speed = (float) p.movementSpeed;
        float friction = (float) p.groundFriction;
        float airDrag = (float) p.airDrag;
        float verticalDrag = (float) p.verticalDrag;
        double forward = (float) p.forwardInput;
        float charge = (float) p.chargeScale;
        double vy = (float) p.launchPower;
        double vz = Math.abs(p.initialForwardSpeed) < MOTION_EPSILON ? 0 : p.initialForwardSpeed;
        if (p.forwardStartTick == 0 && forward > 0) vz += 0.4f * charge;
        double y = 0;
        double z = 0;
        double apex = 0;
        int apexTick = 0;
        boolean reachedLandingHeight = landingHeight <= 0;
        for (int tick = 0; tick < p.maxTicks; tick++) {
            if (tick > 0) {
                if (Math.abs(vy) < MOTION_EPSILON) vy = 0;
                if (Math.abs(vz) < MOTION_EPSILON) vz = 0;
            }
            float acceleration;
            if (tick == 0) {
                acceleration = (double) friction > 0.6
                        ? speed * (0.21600002f / (friction * friction * friction)) : speed;
            } else {
                acceleration = speed * 0.1f;
            }
            if (tick >= p.forwardStartTick && forward * forward >= 1.0e-7) {
                vz += forward * acceleration;
            }
            double previousY = y;
            double previousZ = z;
            y += vy;
            z += vz;
            positions.add(new Vec3(0, y, z));
            if (y > apex) {
                apex = y;
                apexTick = tick + 1;
            }
            if (y >= landingHeight) reachedLandingHeight = true;
            if (reachedLandingHeight && vy <= 0 && y <= landingHeight) {
                double fraction = previousY == y ? 1 : (previousY - landingHeight) / (previousY - y);
                double landingDistance = previousZ + (z - previousZ) * fraction;
                return new Trajectory(positions, apex, apexTick, tick + 1, landingDistance);
            }
            vy = (vy - p.gravity) * verticalDrag;
            vz *= tick == 0 ? (double) (friction * airDrag) : airDrag;
        }
        return new Trajectory(positions, apex, apexTick, -1, Double.NaN);
    }

    private static boolean unit(double value) { return value >= 0 && value <= 1; }
    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
}
