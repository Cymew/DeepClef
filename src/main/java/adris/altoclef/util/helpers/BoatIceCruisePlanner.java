package adris.altoclef.util.helpers;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/** A bounded native-paddle search for moving along an already checked ice road. */
public final class BoatIceCruisePlanner {
    /**
     * Stateless world adapter. step must use native boat dynamics and reject the
     * complete movement sweep when its support, collision or cached road corridor
     * cannot be verified. No simulated state is written back to the live entity.
     */
    public interface Dynamics {
        double retention(BoatSteering.State state);
        BoatSteering.State step(BoatSteering.State state, BoatSteering.Input input);
    }

    private static final int WIDTH = 4, LAYERS = 7, BLOCK_TICKS = 3, FOLLOW_TICKS = 32;
    private static final BoatSteering.Input[] ACTIONS = {
            new BoatSteering.Input(false, false, true, false),
            new BoatSteering.Input(true, false, true, false),
            new BoatSteering.Input(false, true, true, false),
            BoatSteering.Input.NONE,
            new BoatSteering.Input(true, false, false, false),
            new BoatSteering.Input(false, true, false, false),
            new BoatSteering.Input(false, false, false, true),
            new BoatSteering.Input(true, false, false, true),
            new BoatSteering.Input(false, true, false, true)
    };
    private record Branch(BoatSteering.State state, BoatSteering.Input first,
                          double runningCost, double score, int turn,
                          Branch previous, BoatSteering.Input action, BoatSteering.State[] steps) { }
    private record Continuation(List<BoatSteering.Input> inputs,
                                List<BoatSteering.State> states, int next) { }
    private record Validated(BoatSteering.Input input, Continuation continuation) { }
    // Values contain only immutable controls and native predicted states. They
    // never retain a world, a terrain adapter, or their weak navigator key.
    private static final Map<IceRoadNavigator, Continuation> CONTINUATIONS = new WeakHashMap<>();

    private BoatIceCruisePlanner() { }

    public static void reset(IceRoadNavigator road) {
        CONTINUATIONS.remove(road);
    }
    /** Validate the immediate brake before longer searches consume the budget. */
    public static Optional<BoatSteering.Input> emergencyBrake(BoatSteering.State state,Dynamics dynamics,
                                                               BoatTrajectoryPredictor.Budget budget) {
        if(!valid(state) || dynamics==null || budget==null) return Optional.empty();
        double retention=dynamics.retention(state);
        if(!Double.isFinite(retention)) return Optional.empty();
        BoatSteering.Input desired=BoatSteering.brakeRoad(state,retention),best=null;
        double score=Double.POSITIVE_INFINITY;
        for(int index=-1;index<ACTIONS.length;index++) {
            BoatSteering.Input input=index<0?desired:ACTIONS[index];
            if(!budget.claim()) break;
            BoatSteering.State next=dynamics.step(state,input);
            if(!valid(next)) continue;
            if(index<0) return Optional.of(input);
            double candidate=next.velocity().horizontalDistanceSqr()+next.angularVelocity()*next.angularVelocity()*.0001;
            if(candidate<score) { score=candidate; best=input; }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Returns the first native input of a fully checked 21-tick drift rollout
     * or a 32-tick road follow with a rolling reserve certificate.
     * The drift search requests at most 675 steps; the 32-tick follow and
     * any cached-tail revalidation also consume the same caller budget.
     * every one consumes the caller's shared forecast budget. A partial final
     * layer cannot produce a new action. If that search fails, a continuation
     * from an earlier complete rollout is usable only after its next movement
     * has been checked again against the current terrain and forecast state.
     *
     * The search verifies rolling geometry rather than demanding a straight
     * stopping tail from each bend. Replanning next tick can rotate the retained
     * velocity around the road instead of first trying to erase it using S.
     */
    public static Optional<BoatSteering.Input> choose(BoatSteering.State initial,
                                                      IceRoadNavigator road,
                                                      Dynamics dynamics,
                                                      BoatTrajectoryPredictor.Budget budget) {
        if (!valid(initial) || road == null || dynamics == null || budget == null
                || road.path().size() < 2 || budget.exhausted()) {
            reset(road);
            return Optional.empty();
        }
        Validated fallback = validateContinuation(initial, road, dynamics, budget);
        IceRoadNavigator.Guidance entry = road.preview(initial.position(), initial.velocity());
        if (entry.target() == null || entry.blocked()) {
            reset(road);
            return Optional.empty();
        }
        if (entry.terminal()) return useFallback(road, fallback);
        double initialRemaining = road.remainingDistance(initial.position());
        if (!Double.isFinite(initialRemaining) || initialRemaining <= 0) return useFallback(road, fallback);

        // Establish a cheap, complete rolling follow before spending the frame
        // on drift optimisation. Otherwise a 675-step search which repeatedly
        // misses the live deadline consumes its 21 saved inputs, then brakes
        // forever despite a valid road. No incomplete beam is accepted.
        boolean narrow=road.clearance(entry.target())<2;
        if (fallback == null || fallback.continuation().inputs().size()-fallback.continuation().next()<=3) {
            double heading=Math.abs(net.minecraft.util.Mth.wrapDegrees(BoatSteering.yawOf(entry.target().subtract(initial.position()))-initial.yaw()));
            boolean needsAlignment=heading>8 || Math.abs(initial.angularVelocity())>.5;
            boolean alignFirst=narrow && initial.velocity().horizontalDistance()<.1 && needsAlignment;
            Continuation following=alignFirst?follow(initial,road,dynamics,budget,BoatManeuverPlanner.Mode.ALIGN,false)
                    :follow(initial,road,dynamics,budget,BoatManeuverPlanner.Mode.DRIVE,false);
            // A small native angular pulse need not settle below .5 before
            // moving. Try a fully swept slow departure before another stationary
            // alignment; quantised A/D otherwise keeps renewing ALIGN forever.
            if (following==null)
                following=follow(initial,road,dynamics,budget,BoatManeuverPlanner.Mode.DRIVE,true);
            if (following==null && alignFirst)
                following=follow(initial,road,dynamics,budget,BoatManeuverPlanner.Mode.ALIGN,false,false);
            if (following==null && !alignFirst && initial.velocity().horizontalDistance()<.25 && needsAlignment)
                following=follow(initial,road,dynamics,budget,BoatManeuverPlanner.Mode.ALIGN,false);
            if (following!=null) fallback=new Validated(following.inputs().getFirst(),following);
        }
        if (narrow || road.isStraightRoute() && road.crossTrackDistance(initial.position())<=.2)
            return useFallback(road,fallback);

        List<Branch> beam = List.of(new Branch(initial, null, 0, 0, 0, null, null, null));
        for (int layer = 0; layer < LAYERS; layer++) {
            List<Branch> candidates = new ArrayList<>(WIDTH * ACTIONS.length);
            for (Branch branch : beam) {
                for (BoatSteering.Input action : ACTIONS) {
                    BoatSteering.State state = branch.state();
                    BoatSteering.State[] steps = new BoatSteering.State[BLOCK_TICKS];
                    boolean safe = true;
                    for (int tick = 0; tick < BLOCK_TICKS; tick++) {
                        if (!budget.claim()) return useFallback(road, fallback);
                        state = dynamics.step(state, action);
                        if (!valid(state)) { safe = false; break; }
                        steps[tick] = state;
                        if (!budget.hasTime()) return useFallback(road, fallback);
                    }
                    if (!safe) continue;
                    double running = branch.runningCost() + inputCost(action)
                            +(branch.first()==null && !action.forward()?3:0);
                    double score = score(state, road, dynamics, running, initialRemaining);
                    if (!Double.isFinite(score)) continue;
                    BoatSteering.Input first=branch.first()==null?action:branch.first();
                    candidates.add(new Branch(state, first, running, score,
                            first.left()?-1:first.right()?1:0, branch, action, steps));
                }
            }
            if (candidates.isEmpty() || !budget.hasTime()) return useFallback(road, fallback);
            candidates.sort(Comparator.comparingDouble(Branch::score));
            // Keep a turning continuation on either side. Three nearly equal
            // straight branches otherwise prune every early turn before inertia
            // has had time to change position, then discover the bend too late.
            List<Branch> diverse=new ArrayList<>(WIDTH);
            for(int turn=-1;turn<=1;turn++) {
                for(Branch candidate:candidates) if(candidate.turn()==turn) { diverse.add(candidate); break; }
            }
            for(Branch candidate:candidates) if(!candidate.first().forward() && !diverse.contains(candidate)) {
                diverse.add(candidate); break;
            }
            diverse.sort(Comparator.comparingDouble(Branch::score));
            beam=diverse;
        }
        if (!budget.hasTime()) return useFallback(road, fallback);
        Branch best = null;
        for (Branch candidate:beam) {
            // A loaded prefix is not the final destination. Its short rolling
            // rollout must leave enough distance for braking and the next
            // planning window; a soft score alone can spend that reserve.
            if ((road.reachesGoal() || road.canCruise(candidate.state().position(),candidate.state().velocity()))
                    && settledLateral(initial,candidate.state(),road)) {
                best=candidate; break;
            }
        }
        if (best==null || !budget.hasTime()) return useFallback(road,fallback);
        // A geometrically valid loop has no value as a cruise recovery.
        if (road.remainingDistance(best.state().position()) >= initialRemaining - .08
                && initial.velocity().horizontalDistance() < .025) return useFallback(road, fallback);
        CONTINUATIONS.put(road, continuation(initial, best));
        return Optional.of(best.first());
    }

    private static Validated validateContinuation(BoatSteering.State initial, IceRoadNavigator road,
                                                   Dynamics dynamics, BoatTrajectoryPredictor.Budget budget) {
        Continuation cached = CONTINUATIONS.remove(road);
        if (cached == null || cached.next() >= cached.inputs().size()) return null;
        BoatSteering.State expected=cached.states().get(cached.next());
        if (!matches(initial,expected)) {
            // Small native rounding/server corrections do not turn an old
            // trajectory into a proof. Re-simulate its entire remaining tail
            // from the observed boat state, with current collision/support.
            if (!near(initial,expected)) return null;
            List<BoatSteering.Input> inputs=cached.inputs().subList(cached.next(),cached.inputs().size());
            List<BoatSteering.State> states=new ArrayList<>(); states.add(initial);
            BoatSteering.State state=initial;
            for (BoatSteering.Input input:inputs) {
                if (!budget.claim()) return null;
                state=dynamics.step(state,input);
                if (!valid(state) || !budget.hasTime()) return null;
                states.add(state);
            }
            cached=new Continuation(List.copyOf(inputs),List.copyOf(states),0);
        }
        if (!budget.claim()) return null;
        BoatSteering.Input input = cached.inputs().get(cached.next());
        BoatSteering.State next = dynamics.step(initial, input);
        if (!valid(next) || !matches(next, cached.states().get(cached.next() + 1)) || !budget.hasTime()
                || !road.reachesGoal() && !road.canCruise(next.position(),next.velocity())) return null;
        return new Validated(input, cached);
    }

    private static Continuation follow(BoatSteering.State initial,IceRoadNavigator road,
                                        Dynamics dynamics,BoatTrajectoryPredictor.Budget budget,
                                        BoatManeuverPlanner.Mode mode,boolean limited) {
        return follow(initial,road,dynamics,budget,mode,limited,true);
    }

    private static Continuation follow(BoatSteering.State initial,IceRoadNavigator road,
                                        Dynamics dynamics,BoatTrajectoryPredictor.Budget budget,
                                        BoatManeuverPlanner.Mode mode,boolean limited,boolean allowDeparture) {
        List<BoatSteering.Input> inputs=new ArrayList<>(FOLLOW_TICKS);
        List<BoatSteering.State> states=new ArrayList<>(FOLLOW_TICKS+1); states.add(initial);
        BoatSteering.State state=initial;
        boolean departed=false;
        for (int tick=0;tick<FOLLOW_TICKS;tick++) {
            var guidance=road.preview(state.position(),state.velocity());
            if (guidance.target()==null || guidance.terminal() || guidance.blocked() || !budget.claim()) return null;
            double retention=dynamics.retention(state);
            if (!Double.isFinite(retention)) return null;
            double speed=guidance.speedLimit();
            if (limited) {
                double low=0,high=Math.min(4,speed),remaining=road.remainingDistance(state.position());
                for (int sample=0;sample<10;sample++) {
                    double middle=(low+high)*.5;
                    double reserve=BoatSteering.stoppingDistance(middle,Math.max(retention,.989f))
                            +middle*FOLLOW_TICKS*2+(road.reachesGoal()?.5:8);
                    if (reserve<=remaining) low=middle; else high=middle;
                }
                speed=Math.min(Math.max(.25,initial.velocity().horizontalDistance()),Math.min(speed,low));
            }
            if (mode==BoatManeuverPlanner.Mode.ALIGN && allowDeparture && road.clearance(guidance.target())<2) {
                double heading=Math.abs(net.minecraft.util.Mth.wrapDegrees(
                        BoatSteering.yawOf(guidance.target().subtract(state.position()))-state.yaw()));
                if (heading<=8 && Math.abs(state.angularVelocity())<=1) departed=true;
            }
            // Once aligned, verify a low-speed departure in the same rollout.
            // Native torque is quantised; demanding |omega|<.5 can renew an
            // otherwise safe stationary ALIGN cache without ever pressing W.
            var policy=departed?BoatManeuverPlanner.Mode.DRIVE:mode;
            var input=BoatManeuverPlanner.continuationRoad(policy,state,guidance.target(),retention,
                    departed?Math.min(.25,speed):speed,false);
            state=dynamics.step(state,input);
            if (!valid(state) || !budget.hasTime()) return null;
            inputs.add(input); states.add(state);
        }
        if (!road.canFollow(state.position(),state.velocity())
                || !settledLateral(initial,state,road,mode==BoatManeuverPlanner.Mode.ALIGN) || !budget.hasTime()) return null;
        // next=0: unlike a beam result, input zero has not yet been submitted.
        return new Continuation(List.copyOf(inputs),List.copyOf(states),0);
    }

    private static Optional<BoatSteering.Input> useFallback(IceRoadNavigator road, Validated fallback) {
        if (fallback == null) return Optional.empty();
        Continuation cached = fallback.continuation();
        int next = cached.next() + 1;
        if (next < cached.inputs().size())
            CONTINUATIONS.put(road, new Continuation(cached.inputs(), cached.states(), next));
        return Optional.of(fallback.input());
    }

    private static Continuation continuation(BoatSteering.State initial, Branch best) {
        List<Branch> reverse = new ArrayList<>(LAYERS);
        for (Branch branch = best; branch.previous() != null; branch = branch.previous()) reverse.add(branch);
        List<BoatSteering.Input> inputs = new ArrayList<>(LAYERS * BLOCK_TICKS);
        List<BoatSteering.State> states = new ArrayList<>(LAYERS * BLOCK_TICKS + 1);
        states.add(initial);
        for (int index = reverse.size() - 1; index >= 0; index--) {
            Branch branch = reverse.get(index);
            for (BoatSteering.State state : branch.steps()) {
                inputs.add(branch.action());
                states.add(state);
            }
        }
        // The caller is about to execute input zero; the next live call must
        // therefore match state one before it can continue this sequence.
        return new Continuation(List.copyOf(inputs), List.copyOf(states), 1);
    }

    private static boolean matches(BoatSteering.State actual, BoatSteering.State expected) {
        if (!valid(actual) || !valid(expected)
                || actual.position().distanceToSqr(expected.position()) > 1e-8
                || actual.velocity().distanceToSqr(expected.velocity()) > 1e-10
                || Math.abs(actual.angularVelocity() - expected.angularVelocity()) > .001f) return false;
        double yaw = (actual.yaw() - expected.yaw()) % 360;
        if (yaw > 180) yaw -= 360;
        if (yaw < -180) yaw += 360;
        return Math.abs(yaw) <= .001;
    }

    private static boolean near(BoatSteering.State actual,BoatSteering.State expected) {
        if (!valid(actual) || !valid(expected)) return false;
        double yaw=net.minecraft.util.Mth.wrapDegrees(actual.yaw()-expected.yaw());
        return actual.position().distanceToSqr(expected.position())<=.125*.125
                && actual.velocity().distanceToSqr(expected.velocity())<=.02*.02
                && Math.abs(actual.position().y-expected.position().y)<1e-6
                && Math.abs(yaw)<=2 && Math.abs(actual.angularVelocity()-expected.angularVelocity())<=.5;
    }

    private static double inputCost(BoatSteering.Input input) {
        // Progress dominates. Among comparable trajectories prefer the strong
        // paddle, while S remains available when it actually saves a trajectory.
        return input.back() ? .75 : input.forward() ? -.15 : .15;
    }

    private static boolean settledLateral(BoatSteering.State initial,BoatSteering.State end,IceRoadNavigator road) {
        return settledLateral(initial,end,road,false);
    }

    private static boolean settledLateral(BoatSteering.State initial,BoatSteering.State end,IceRoadNavigator road,boolean alignment) {
        double before=Math.abs(road.lateralLead(initial.position(),initial.velocity(),20));
        double after=Math.abs(road.lateralLead(end.position(),end.velocity(),20));
        // A curved road's tangent moves during these twenty ticks. Its lateral
        // lead includes that curvature as well as drift; the native swept
        // corridor remains the hard geometric limit at every predicted step.
        return Double.isFinite(after) && (!road.isStraightRoute()
                || after<=Math.max(1.2,before+(alignment?.25:-.25)));
    }

    private static double score(BoatSteering.State state, IceRoadNavigator road,
                                Dynamics dynamics, double running, double initialRemaining) {
        double remaining = road.remainingDistance(state.position());
        double cross = road.crossTrackDistance(state.position());
        IceRoadNavigator.Guidance guidance = road.preview(state.position(), state.velocity());
        double retention = dynamics.retention(state);
        if (guidance.target() == null || guidance.blocked() || !Double.isFinite(remaining)
                || !Double.isFinite(cross) || !Double.isFinite(retention) || retention < 0 || retention > 1)
            return Double.POSITIVE_INFINITY;
        Vec3 direction = guidance.target().subtract(state.position()).multiply(1, 0, 1);
        double speed = state.velocity().horizontalDistance();
        Vec3 along = direction.horizontalDistance() > 1e-7 ? direction.normalize() : Vec3.ZERO;
        double advance = state.velocity().dot(along);
        double sideways = Math.max(0, state.velocity().horizontalDistanceSqr() - advance * advance);
        double excess = Math.max(0, speed - Math.max(.15, guidance.speedLimit()));
        double reserve = BoatSteering.stoppingDistance(speed, retention) + speed * 4 + 1;
        double shortfall = Math.max(0, reserve - remaining);
        double edgeRisk=Math.max(0,1.4-road.clearance(state.position().add(state.velocity().multiply(12,0,12))));
        double lateralLead=road.lateralLead(state.position(),state.velocity(),20);
        // This is deliberately a soft reserve: a long checked curved road can
        // keep turning, whereas a straight stopping certificate cuts its edge.
        double progress = Math.max(0, initialRemaining - remaining);
        return remaining + cross * cross * .65 + sideways * 1.8
                + excess * excess * 3 + shortfall * shortfall * .05
                + edgeRisk*edgeRisk*40 + lateralLead*lateralLead*2
                + Math.max(0, -advance) * 2 + state.angularVelocity() * state.angularVelocity() * .012
                + running - Math.min(1.5, progress * .025);
    }

    private static boolean valid(BoatSteering.State state) {
        return state != null && finite(state.position()) && finite(state.velocity())
                && Float.isFinite(state.yaw()) && Float.isFinite(state.angularVelocity()) && Math.abs(state.angularVelocity())<=8;
    }
    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
