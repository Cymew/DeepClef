package adris.altoclef.util.helpers;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Rolling native-motion proposals. Every published prefix has been swept through the current world. */
public final class ElytraRoutePlanner {
    public enum Phase { CLIMB, CRUISE, LAND }
    public enum LandingPhase { APPROACH, SPIRAL, FLARE, TOUCHDOWN }
    public enum Trace { CLEAR, BLOCKED, UNKNOWN }
    public interface Terrain {
        Trace trace(Vec3 from, Vec3 to, boolean landingContact);
        default void beginFrame(int tick, long maxNanos) { }
    }
    public record State(Vec3 position, Vec3 velocity, float yaw, float pitch, double gravity, int boostTicks) { }
    public record Request(Vec3 destination, double cruiseAltitude, Phase phase, Vec3 landingPoint,
                          boolean allowRocket, int rocketMaxTicks) {
        public Request(Vec3 destination, double cruiseAltitude, Phase phase, Vec3 landingPoint, boolean allowRocket) {
            this(destination, cruiseAltitude, phase, landingPoint, allowRocket, 52);
        }
    }
    public record Guidance(float yaw, float pitch, boolean useRocket, List<Vec3> trajectory,
                           boolean blocked, boolean emergency, Vec3 landingPoint) {
        public Guidance { trajectory = List.copyOf(trajectory); }
    }
    private record Manoeuvre(float yawOffset, float pitchBias, boolean rocket) { }
    private record Candidate(Manoeuvre manoeuvre, float yaw, float pitch, List<Vec3> points,
                             double score, boolean complete, boolean landed, boolean obstruction) { }
    private static final int HORIZON = 44;
    private static final int MAX_PROPOSALS = 9;
    private static final int MAX_STEPS = HORIZON * MAX_PROPOSALS;
    private final LongSupplier clock;
    private Manoeuvre retained;
    private Phase phase;
    private Vec3 destination;
    private List<Vec3> path = List.of();
    private float cruisePitch = Float.NaN;
    private LandingPhase landingPhase = LandingPhase.APPROACH;
    private boolean landingKnown;
    private int orbitDirection = 1;
    private int steps, proposals;

    public ElytraRoutePlanner() { this(System::nanoTime); }
    ElytraRoutePlanner(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }
    public List<Vec3> path() { return path; }
    public int lastSimulationSteps() { return steps; }
    public int lastProposals() { return proposals; }
    public LandingPhase landingPhase() { return landingPhase; }
    public void setCruisePitch(float targetPitch) { cruisePitch = targetPitch; }
    public void reset() {
        retained = null; phase = null; destination = null; path = List.of();
        landingPhase = LandingPhase.APPROACH;
        landingKnown = false;
    }

    public Guidance update(State state, Request request, Terrain terrain, int tick, long maxNanos) {
        steps = proposals = 0;
        if (!valid(state) || request == null || !finite(request.destination()) || request.phase() == null
                || !Double.isFinite(request.cruiseAltitude()) || terrain == null || maxNanos <= 0) {
            reset();
            return new Guidance(state == null ? 0 : state.yaw(), state == null ? 0 : state.pitch(),
                    false, List.of(), true, true, request == null ? null : request.landingPoint());
        }
        terrain.beginFrame(tick, maxNanos);
        if (phase != request.phase() || !Objects.equals(destination, request.destination())) {
            retained = null; phase = request.phase(); destination = request.destination();
            landingPhase = LandingPhase.APPROACH;
            Vec3 center = finite(request.landingPoint()) ? request.landingPoint() : request.destination();
            Vec3 radial = state.position().subtract(center);
            double turn = radial.x * state.velocity().z - radial.z * state.velocity().x;
            orbitDirection = turn < 0 ? -1 : 1;
        }
        if (request.phase() == Phase.LAND) {
            boolean known = finite(request.landingPoint());
            LandingPhase prior = known && !landingKnown ? LandingPhase.APPROACH : landingPhase;
            LandingPhase next = nextLandingPhase(prior, state.position(), state.velocity(), request);
            if (next != landingPhase || known != landingKnown) retained = null;
            landingPhase = next; landingKnown = known;
        }
        long started = clock.getAsLong();
        List<Manoeuvre> choices = choices(state, request);
        Candidate best = null, shortPrefix = null;
        for (Manoeuvre manoeuvre : choices) {
            if (proposals >= MAX_PROPOSALS || steps >= MAX_STEPS || clock.getAsLong() - started >= maxNanos) break;
            Candidate candidate = simulate(state, request, terrain, manoeuvre, started, maxNanos);
            proposals++;
            if (candidate.complete || candidate.landed) {
                if (best == null || candidate.score < best.score - .15) best = candidate;
            } else if (!manoeuvre.rocket && !candidate.points.isEmpty()
                    && (shortPrefix == null || candidate.points.size() > shortPrefix.points.size()
                        || candidate.points.size() == shortPrefix.points.size() && candidate.score < shortPrefix.score)) {
                shortPrefix = candidate;
            }
        }
        Candidate selected = best == null ? shortPrefix : best;
        if (selected == null) {
            path = List.of();
            // This is an honest lack of a verified move, never an instruction to hover or stop gravity.
            return new Guidance(state.yaw(), Math.min(state.pitch(), -10), false, path, true, true,
                    request.landingPoint());
        }
        retained = new Manoeuvre(selected.manoeuvre.yawOffset, selected.manoeuvre.pitchBias, false);
        path = selected.points;
        boolean emergency = best == null || Math.abs(selected.manoeuvre.yawOffset) > 40
                || Math.abs(selected.manoeuvre.pitchBias) > 10;
        return new Guidance(selected.yaw, selected.pitch, selected.manoeuvre.rocket, path,
                best == null && path.size() < 8, emergency, request.landingPoint());
    }

    private List<Manoeuvre> choices(State state, Request request) {
        List<Manoeuvre> result = new ArrayList<>();
        if (retained != null) result.add(retained);
        add(result, new Manoeuvre(0, 0, false));
        if (request.phase() != Phase.LAND && request.allowRocket() && state.boostTicks() <= 0)
            add(result, new Manoeuvre(0, 0, true));
        // Probe climb and both banks before increasingly large escape turns.
        add(result, new Manoeuvre(0, -22, false));
        add(result, new Manoeuvre(-35, -8, false));
        add(result, new Manoeuvre(35, -8, false));
        add(result, new Manoeuvre(-80, 0, false));
        add(result, new Manoeuvre(80, 0, false));
        add(result, new Manoeuvre(180, request.phase() == Phase.LAND ? -8 : -12, false));
        return result;
    }

    private static void add(List<Manoeuvre> choices, Manoeuvre value) {
        if (!choices.contains(value)) choices.add(value);
    }

    private Candidate simulate(State state, Request request, Terrain terrain, Manoeuvre manoeuvre,
                               long started, long budget) {
        int maximum = Mth.clamp(request.rocketMaxTicks(), 21, 64);
        Candidate primary = rollout(state, request, terrain, manoeuvre, started, budget, maximum, 2, false);
        // An upper lifetime/late inventory acknowledgement cannot be treated as guaranteed thrust.
        // Launches retain their guaranteed minimum boost, while a landing must also survive its early end.
        boolean alternative = manoeuvre.rocket || request.phase() == Phase.LAND && state.boostTicks() > 0;
        if (!alternative || primary.points.isEmpty()) return primary;
        Candidate other = rollout(state, request, terrain, manoeuvre, started, budget,
                Math.max(1, maximum - 11), 0, !manoeuvre.rocket);
        int checked = Math.min(primary.points.size(), other.points.size());
        return new Candidate(manoeuvre, primary.yaw, primary.pitch,
                primary.points.subList(0, checked), Math.max(primary.score, other.score),
                primary.complete && other.complete, primary.landed && other.landed,
                primary.obstruction || other.obstruction);
    }

    private Candidate rollout(State state, Request request, Terrain terrain, Manoeuvre manoeuvre,
                              long started, long budget, int rocketDuration, int attachDelay,
                              boolean endedCurrentBoost) {
        Vec3 position = state.position(), velocity = state.velocity();
        float yaw = state.yaw(), pitch = state.pitch(), firstYaw = yaw, firstPitch = pitch;
        List<Vec3> points = new ArrayList<>();
        boolean complete = false, landed = false, obstruction = false;
        LandingPhase localLanding = landingPhase;
        Vec3 center = finite(request.landingPoint()) ? request.landingPoint() : request.destination();
        double largestRadius = horizontal(position, center);
        for (int index = 0; index < HORIZON && steps < MAX_STEPS; index++) {
            if (clock.getAsLong() - started >= budget) break;
            if (request.phase() == Phase.LAND)
                localLanding = nextLandingPhase(localLanding, position, velocity, request);
            float targetYaw = yawFor(position, velocity, request, localLanding) + manoeuvre.yawOffset;
            float targetPitch = pitchFor(position, velocity, request, localLanding) + manoeuvre.pitchBias;
            yaw = Mth.approachDegrees(yaw, targetYaw, request.phase() == Phase.LAND ? 10 : 7);
            pitch = Mth.approach(pitch, Mth.clamp(targetPitch, -55, 50), 3);
            if (index == 0) { firstYaw = yaw; firstPitch = pitch; }
            // A newly used native rocket may attach after this movement or the next one.
            boolean boosted = !endedCurrentBoost && index < state.boostTicks()
                    || manoeuvre.rocket && index >= attachDelay && index < attachDelay + rocketDuration;
            Vec3 nextVelocity = ElytraPhysics.step(velocity, yaw, pitch, state.gravity(), boosted);
            Vec3 next = position.add(nextVelocity);
            steps++;
            boolean contact = landingContact(position, next, nextVelocity, request);
            Trace trace = terrain.trace(position, next, contact);
            if (trace != Trace.CLEAR) { obstruction = trace == Trace.BLOCKED; break; }
            if (contact) {
                next = new Vec3(next.x, request.landingPoint().y, next.z);
                landed = complete = true;
            }
            points.add(next);
            position = next; velocity = nextVelocity;
            largestRadius = Math.max(largestRadius, horizontal(position, center));
            if (landed) break;
            if (index == HORIZON - 1) complete = true;
        }
        double score = score(state, request, position, velocity, manoeuvre, landed, localLanding)
                + (request.phase() == Phase.LAND
                    ? Math.max(0, largestRadius - Math.max(64, horizontal(state.position(), center) + 12)) * 6 : 0)
                + (complete ? 0 : (HORIZON - points.size()) * 20);
        return new Candidate(manoeuvre, firstYaw, firstPitch, points, score, complete, landed, obstruction);
    }

    private float pitchFor(Vec3 position, Vec3 velocity, Request request, LandingPhase landingStage) {
        if (request.phase() != Phase.LAND) {
            if (Float.isFinite(cruisePitch)) return cruisePitch;
            double error = request.cruiseAltitude() - position.y;
            return (float)Mth.clamp(-error * .7 + velocity.y * 11,
                    request.phase() == Phase.CLIMB ? -38 : -22, 22);
        }
        // The terrain probe may need several frames. Keep a real, bounded glide
        // around the destination while it works, rather than flying past it or inventing a floor.
        if (!finite(request.landingPoint())) return landingStage == LandingPhase.SPIRAL ? -8 : 0;
        double height = position.y - request.landingPoint().y;
        double distance = horizontal(position, request.landingPoint());
        double speed = velocity.horizontalDistance();
        if (landingStage == LandingPhase.APPROACH)
            return (float)Mth.clamp(Math.toDegrees(Math.atan2(Math.max(0, height - 65), Math.max(24, distance))), 0, 30);
        if (landingStage == LandingPhase.SPIRAL) return height > 80 ? 42 : 35;
        if (velocity.y < -.35) return speed > 1.05 ? -22 : -12;
        if (height < 12 && speed > 1.05) return -20;
        if (height < 4 && speed > .65) return -8;
        if (height < 3) return 8;
        return height > 5 ? -2 : 8;
    }

    private float yawFor(Vec3 position, Vec3 velocity, Request request, LandingPhase landingStage) {
        Vec3 center = finite(request.landingPoint()) ? request.landingPoint() : request.destination();
        if (request.phase() != Phase.LAND) return bearing(position, request.destination());
        if (landingStage != LandingPhase.SPIRAL)
            return bearing(position, center.subtract(velocity.multiply(8, 0, 8)));
        Vec3 radial = position.subtract(center).multiply(1, 0, 1);
        double distance = radial.horizontalDistance();
        if (distance < .1) {
            Vec3 forward = velocity.multiply(1, 0, 1).normalize();
            if (forward.lengthSqr() < .01) forward = new Vec3(0, 0, 1);
            radial = new Vec3(forward.z * orbitDirection, 0, -forward.x * orbitDirection);
        } else radial = radial.scale(1 / distance);
        double radius = orbitRadius(position, request);
        Vec3 tangent = new Vec3(-radial.z * orbitDirection, 0, radial.x * orbitDirection);
        double correction = Mth.clamp((radius - distance) * .12, -1.5, 1.5);
        Vec3 desired = tangent.scale(.85).add(radial.scale(correction));
        // The native horizontal velocity follows the look vector with a ten-percent lag.
        Vec3 steering = desired.scale(1.8).subtract(velocity.multiply(.8, 0, .8));
        return bearing(Vec3.ZERO, steering);
    }

    private static LandingPhase nextLandingPhase(LandingPhase previous, Vec3 position, Vec3 velocity, Request request) {
        Vec3 center = finite(request.landingPoint()) ? request.landingPoint() : request.destination();
        double distance = horizontal(position, center);
        if (!finite(request.landingPoint()))
            return distance <= 64 || previous == LandingPhase.SPIRAL ? LandingPhase.SPIRAL : LandingPhase.APPROACH;
        double height = position.y - center.y;
        double flareHeight = Math.max(24, 12 + Math.max(0, -velocity.y) * 20 + velocity.horizontalDistance() * 6);
        if (previous == LandingPhase.TOUCHDOWN || height < 4 && distance < 4) return LandingPhase.TOUCHDOWN;
        if (previous == LandingPhase.FLARE || distance <= 64 && height <= flareHeight) return LandingPhase.FLARE;
        if (previous == LandingPhase.SPIRAL || distance <= 64) return LandingPhase.SPIRAL;
        return LandingPhase.APPROACH;
    }

    private static double orbitRadius(Vec3 position, Request request) {
        return finite(request.landingPoint()) ? Mth.clamp((position.y - request.landingPoint().y) * .035 + 10, 10, 20) : 24;
    }

    private static boolean landingContact(Vec3 from, Vec3 to, Vec3 velocity, Request request) {
        Vec3 landing = request.landingPoint();
        return request.phase() == Phase.LAND && finite(landing) && from.y >= landing.y - .001
                && to.y <= landing.y + .001 && velocity.y >= -.45 && velocity.y <= 0
                && velocity.horizontalDistance() <= .65 && horizontal(to, landing) <= 1.2;
    }

    private double score(State start, Request request, Vec3 position, Vec3 velocity,
                                Manoeuvre manoeuvre, boolean landed, LandingPhase landingStage) {
        if (request.phase() == Phase.LAND && !finite(request.landingPoint())) {
            double distance = horizontal(position, request.destination());
            return (landingStage == LandingPhase.SPIRAL ? Math.abs(distance - 24) * 2 : distance)
                    + Math.max(0, distance - 64) * 3 + Math.abs(manoeuvre.yawOffset) * .03;
        }
        if (request.phase() == Phase.LAND && finite(request.landingPoint())) {
            double distance = horizontal(position, request.landingPoint());
            if (landingStage == LandingPhase.APPROACH)
                return distance + Math.abs(position.y - request.landingPoint().y) * .25
                        + Math.abs(velocity.horizontalDistance() - 1.3) * 2
                        + Math.abs(manoeuvre.yawOffset) * .03;
            if (landingStage == LandingPhase.SPIRAL)
                return Math.abs(position.y - request.landingPoint().y) * .8
                        + Math.abs(distance - orbitRadius(position, request)) * 2
                        + Math.max(0, distance - 48) * 5 + Math.abs(velocity.y + .6) * 3
                        + Math.abs(manoeuvre.yawOffset) * .03;
            return (landed ? -2000 : 0) + distance + Math.abs(position.y - request.landingPoint().y) * .55
                    + velocity.horizontalDistance() * 8 + Math.abs(velocity.y) * 12
                    + Math.abs(manoeuvre.yawOffset) * .012;
        }
        double progress = horizontal(position, request.destination()) - horizontal(start.position(), request.destination());
        if (Float.isFinite(cruisePitch)) {
            // The cruise policy deliberately trades height for distance inside its
            // hysteresis band. Do not make MPC undo that decision by levelling early.
            double outsideBand = Math.max(0, request.cruiseAltitude() - 32 - position.y)
                    + Math.max(0, position.y - request.cruiseAltitude() - 8);
            return progress + outsideBand * .25 + Math.abs(manoeuvre.yawOffset) * .03
                    + Math.abs(manoeuvre.pitchBias) * 2 + (manoeuvre.rocket ? 2 : 0);
        }
        double heightError = Math.abs(position.y - request.cruiseAltitude());
        return progress + heightError * .65 + Math.abs(velocity.y) * 4
                + Math.abs(manoeuvre.yawOffset) * .015 + Math.abs(manoeuvre.pitchBias) * .04
                + (manoeuvre.rocket ? 2 : 0);
    }

    private static float bearing(Vec3 from, Vec3 to) {
        return (float)Math.toDegrees(Math.atan2(-(to.x - from.x), to.z - from.z));
    }
    private static double horizontal(Vec3 first, Vec3 second) {
        return Math.hypot(first.x - second.x, first.z - second.z);
    }
    private static boolean valid(State state) {
        return state != null && finite(state.position()) && finite(state.velocity()) && Float.isFinite(state.yaw())
                && Float.isFinite(state.pitch()) && Double.isFinite(state.gravity()) && state.gravity() >= 0;
    }
    private static boolean finite(Vec3 point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }
}
