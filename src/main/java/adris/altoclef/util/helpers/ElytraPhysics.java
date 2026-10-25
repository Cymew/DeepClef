package adris.altoclef.util.helpers;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Pure 26.3 glide and single attached-rocket integration; never changes an entity. */
public final class ElytraPhysics {
    private static final float DEGREES_TO_RADIANS = (float) Math.PI / 180;

    private ElytraPhysics() { }

    /** Exact Entity.calculateViewVector arithmetic, with yaw/pitch in native degrees. */
    public static Vec3 look(float yaw, float pitch) {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) throw new IllegalArgumentException("Nonfinite flight rotation");
        float lean = pitch * DEGREES_TO_RADIANS;
        float heading = -yaw * DEGREES_TO_RADIANS;
        float yCos = Mth.cos(heading), ySin = Mth.sin(heading);
        float xCos = Mth.cos(lean), xSin = Mth.sin(lean);
        return new Vec3(ySin * xCos, -xSin, yCos * xCos);
    }

    /** LivingEntity.updateFallFlyingMovement, before native collision movement. */
    public static Vec3 stepGlide(Vec3 velocity, float yaw, float pitch, double gravity) {
        validate(velocity);
        if (!Double.isFinite(gravity) || gravity < 0) throw new IllegalArgumentException("Invalid flight gravity");
        Vec3 look = look(yaw, pitch);
        float lean = pitch * DEGREES_TO_RADIANS;
        double lookHorizontal = Math.sqrt(look.x * look.x + look.z * look.z);
        double moveHorizontal = velocity.horizontalDistance();
        // 26.3 uses double Math.cos here, unlike the older Baritone solver.
        double lift = Mth.square(Math.cos(lean));
        Vec3 next = velocity.add(0, gravity * (-1 + lift * .75), 0);
        if (next.y < 0 && lookHorizontal > 0) {
            double convert = next.y * -.1 * lift;
            next = next.add(look.x * convert / lookHorizontal, convert, look.z * convert / lookHorizontal);
        }
        if (lean < 0 && lookHorizontal > 0) {
            double convert = moveHorizontal * (double) (-Mth.sin(lean)) * .04;
            next = next.add(-look.x * convert / lookHorizontal, convert * 3.2,
                    -look.z * convert / lookHorizontal);
        }
        if (lookHorizontal > 0) {
            next = next.add((look.x / lookHorizontal * moveHorizontal - next.x) * .1, 0,
                    (look.z / lookHorizontal * moveHorizontal - next.z) * .1);
        }
        return next.multiply(.99f, .98f, .99f);
    }

    /** One attached FireworkRocketEntity.tick: native .5 velocity + .85 view acceleration. */
    public static Vec3 stepBoost(Vec3 velocity, float yaw, float pitch) {
        validate(velocity);
        Vec3 look = look(yaw, pitch);
        return velocity.add(look.x * .1 + (look.x * 1.5 - velocity.x) * .5,
                look.y * .1 + (look.y * 1.5 - velocity.y) * .5,
                look.z * .1 + (look.z * 1.5 - velocity.z) * .5);
    }

    /** Player glide followed by an attached rocket tick. The other ordering is expressible with the two steps. */
    public static Vec3 step(Vec3 velocity, float yaw, float pitch, double gravity, boolean boosted) {
        Vec3 next = stepGlide(velocity, yaw, pitch, gravity);
        return boosted ? stepBoost(next, yaw, pitch) : next;
    }

    private static void validate(Vec3 vector) {
        if (vector == null || !Double.isFinite(vector.x) || !Double.isFinite(vector.y)
                || !Double.isFinite(vector.z)) throw new IllegalArgumentException("Nonfinite flight velocity");
    }
}
