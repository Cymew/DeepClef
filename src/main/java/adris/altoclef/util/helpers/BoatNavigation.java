package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.control.BoatRiderInput;
import adris.altoclef.mixins.BoatAccessor;
import adris.altoclef.tasksystem.TaskChain;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Owns native paddle inputs; the vanilla boat alone changes heading and motion. */
public final class BoatNavigation {
    private static final Object IDLE = new Object();
    private static final Input[] KEYS = {Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT,
            Input.MOVE_RIGHT, Input.JUMP, Input.SNEAK, Input.SPRINT};
    private static LocalPlayer player;
    private static AbstractBoat boat;
    private static BoatRiderInput riderInput;
    private static Object owner;
    private static Goal goal, navigationGoal;
    private static final BoatRouteNavigator navigator = new BoatRouteNavigator();
    private static final IceRoadNavigator iceNavigator = new IceRoadNavigator();
    private static boolean iceRouting;
    private static boolean iceControl;
    private static Goal iceWaterExit;
    private static int nextWaterExitTick;
    private static int requestTick, lastProgress;
    private static Vec3 bestPosition;
    private static BoatSteering.Input intent = BoatSteering.Input.NONE;
    private static BoatManeuverPlanner.Mode maneuverMode = BoatManeuverPlanner.Mode.DRIVE;
    private static boolean dismount, safety, blocked, navigating;
    private static String debug = "Waiting for boat control.";
    private static long peakWorkNanos;
    private static double averageWorkNanos;
    private static int lastWorldReads;
    private static BoatTrajectoryPredictor.Budget forecastBudget;
    private static int lastForecastSteps;
    private static double lastRetention;
    private static final BoatPredictionMonitor predictionMonitor=new BoatPredictionMonitor();
    private static String controlDecision="idle";
    private static int nextControlDiagnostic;

    private BoatNavigation() { }

    public static AbstractBoat getControlledBoat(LocalPlayer rider) {
        if (rider == null) return null;
        return rider.getControlledVehicle() instanceof AbstractBoat vessel && vessel.isAlive() && !vessel.isRemoved()
                && vessel.getControllingPassenger() == rider && vessel.isLocalInstanceAuthoritative() ? vessel : null;
    }

    public static boolean isActive() {
        return owner != null && player != null && player == Minecraft.getInstance().player
                && boat != null && player.getVehicle() == boat && requestTick >= player.tickCount - 1;
    }
    public static boolean isBlocked(Object requester) { return owner == requester && blocked; }
    public static boolean isDismountRequested(Object requester) { return owner == requester && dismount; }
    public static boolean isDocking(Object requester) { return owner == requester && boat != null && navigating && isAtGoal(goal, boat.position()); }
    public static void retryRoute(Object requester) {
        if (owner != requester) return;
        navigator.retry();
        iceNavigator.retry();
        iceWaterExit = null;
        nextWaterExitTick = Integer.MIN_VALUE;
    }
    public static MountedTravelProgress.Phase travelPhase(Object requester) {
        if (owner != requester || boat == null) return MountedTravelProgress.Phase.PAUSED;
        if (((BoatAccessor)boat).altoclef$getStatus() == AbstractBoat.Status.IN_AIR) return MountedTravelProgress.Phase.AIRBORNE;
        if (boat.getControllingPassenger() != player) return MountedTravelProgress.Phase.BLOCKED;
        if (maneuverMode == BoatManeuverPlanner.Mode.ALIGN) return MountedTravelProgress.Phase.TURNING;
        return blocked ? MountedTravelProgress.Phase.BLOCKED : intent.equals(BoatSteering.Input.NONE)
                ? MountedTravelProgress.Phase.SETTLING : MountedTravelProgress.Phase.TRAVELLING;
    }
    public static String getDebugState(Object requester) { return owner == requester ? debug : "Waiting for boat control."; }
    public static String status(AltoClef mod) {
        if (mod.getPlayer() == null || !(mod.getPlayer().getVehicle() instanceof AbstractBoat vessel)) return "Not aboard a boat.";
        return "boat=" + vessel.getId() + " control=" + (getControlledBoat(mod.getPlayer()) != null)
                + " speed=" + String.format(java.util.Locale.ROOT, "%.2f", vessel.getDeltaMovement().horizontalDistance() * 20)
                + "m/s; " + (boat == vessel ? debug : "Idle")
                + String.format(java.util.Locale.ROOT, "; nav=%.2fms avg/%.2fms peak; reads=%d; retention=%.3f; forecast=%d",
                    averageWorkNanos / 1_000_000, peakWorkNanos / 1_000_000.0, lastWorldReads, lastRetention, lastForecastSteps)
                + "; decision="+controlDecision+" paddles="+intent
                + String.format(java.util.Locale.ROOT," boatYaw=%.2f cameraYaw=%.2f; ",vessel.getYRot(),mod.getPlayer().getYRot())
                +predictionMonitor.summary();
    }
    public static AbstractBoat getRenderBoat() { return isActive() && navigating && !safety ? boat : null; }
    public static List<Vec3> getRenderPath() {
        if (getRenderBoat() == null) return List.of();
        List<Vec3> points = new ArrayList<>();
        points.add(boat.position());
        points.addAll(iceRouting ? iceNavigator.path() : navigator.path());
        return List.copyOf(points);
    }
    public static Goal getRenderGoal() { return getRenderBoat() == null ? null : goal; }

    public static void prepareFrame(AltoClef mod, TaskChain chain, float priority) {
        boolean emergency = MountedNavigation.shouldYieldToSafety(chain, priority);
        if (emergency && !safety) releaseAll(mod);
        safety = emergency;
    }

    private static boolean acquire(AltoClef mod, Object requester, boolean passengerAllowed) {
        if (safety || mod.getPlayer() == null) return false;
        LocalPlayer rider = mod.getPlayer();
        AbstractBoat vessel = passengerAllowed && rider.getVehicle() instanceof AbstractBoat seated
                ? seated : getControlledBoat(rider);
        if (vessel == null || !vessel.isAlive() || vessel.isRemoved()) return false;
        if (player != rider || boat != vessel || owner != requester) {
            releaseAll(mod);
            player = rider;
            boat = vessel;
            owner = requester;
            riderInput = new BoatRiderInput();
            navigator.reset();
            iceNavigator.reset();
            iceWaterExit = null;
            nextWaterExitTick = Integer.MIN_VALUE;
            goal = navigationGoal = null;
            bestPosition = vessel.position();
            lastProgress = rider.tickCount;
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getPathingBehavior().forceCancel();
        }
        requestTick = rider.tickCount;
        intent = BoatSteering.Input.NONE;
        dismount = false;
        navigating = false;
        mod.getClientBaritone().getPathingBehavior().requestPause();
        return true;
    }

    public static void tick(AltoClef mod, Object requester, Goal destination) {
        long started = System.nanoTime();
        Terrain terrain = null;
        try {
        if (!acquire(mod, requester, false)) return;
        forecastBudget = null;
        iceRouting = false;
        navigating = true;
        terrain = new Terrain(mod, boat);
        BoatSteering.State state = state();
        predictionMonitor.observed(boat.tickCount,state);
        double retention = terrain.retention(boat.position());
        iceControl = terrain.onIce(boat.position());
        lastRetention = retention;
        if (destination == null || !Double.isFinite(retention) || boat.isUnderWater()) {
            blocked = true;
            debug = "Boat cannot navigate submerged or unsupported terrain.";
            brake(state, retention);
            return;
        }
        if (goal != destination) {
            goal = destination; navigationGoal = surfaceGoal(destination);
            navigator.reset();
            iceNavigator.reset();
            iceWaterExit = null;
            nextWaterExitTick = Integer.MIN_VALUE;
        }
        if (bestPosition.distanceToSqr(boat.position()) >= .25) {
            bestPosition = boat.position(); lastProgress = player.tickCount;
        }
        if (!iceControl && isAtGoal(goal, boat.position())) {
            blocked = false;
            debug = "Braking and docking at boat goal.";
            Vec3 docking = dockingPoint(terrain, goal, boat.position());
            chooseInput(terrain, state, docking == null ? boat.position() : docking, retention, .12);
            return;
        }
        // Extend a rolling, checked corridor while travelling. Local detour
        // searches are needed only when that corridor meets an obstruction.
        boolean onIce = iceControl;
        if (!onIce) iceWaterExit = null;
        IceRoadNavigator.Guidance iceGuidance = onIce && iceWaterExit == null
                ? iceNavigator.update(boat.position(), boat.getDeltaMovement(), navigationGoal,
                    new IceRoadTerrain(terrain), player.tickCount, 2_000_000) : null;
        if (iceGuidance != null && !iceGuidance.planning() && !iceGuidance.terminal()) {
            List<Vec3> icePath = iceNavigator.path();
            boolean atRoadEnd = iceGuidance.target() == null || !icePath.isEmpty()
                    && boat.position().distanceToSqr(icePath.getLast()) < 16;
            if (atRoadEnd && player.tickCount >= nextWaterExitTick) {
                nextWaterExitTick = player.tickCount + 20;
                Vec3 exit = terrain.waterExit();
                if (exit != null) { iceWaterExit = new GoalXZ(Mth.floor(exit.x), Mth.floor(exit.z)); iceGuidance = null; }
            }
        }
        iceRouting = iceGuidance != null;
        var guidance = iceRouting
                ? new BoatRouteNavigator.Guidance(iceGuidance.target(), iceGuidance.terminal(), iceGuidance.planning(), iceGuidance.blocked())
                : navigator.update(boat.position(), iceWaterExit == null ? navigationGoal : iceWaterExit,
                    terrain, player.tickCount, 32, 2_000_000);
        if (guidance.target() == null) {
            blocked = guidance.blocked();
            debug = guidance.planning() ? iceRouting ? "Mapping loaded ice roads before sailing; holding within known clearance."
                    : "Planning a boat detour; braking within known clearance."
                    : "No safe boat route; braking before replanning.";
            brake(state, retention);
            return;
        }
        Vec3 target = guidance.target();
        if (iceWaterExit == null && guidance.terminal() && (goal instanceof GoalXZ || goal instanceof GoalBlock)) {
            Vec3 docking = dockingPoint(terrain, goal, boat.position());
            if (docking != null) target = docking;
        }
        double distance = target.subtract(boat.position()).horizontalDistance();
        double stoppingRetention = retention > .95 ? Math.max(retention, .989f) : Math.max(retention, .9f);
        double speedLimit = limitForDistance(Math.max(0, distance - .08), stoppingRetention);
        speedLimit = Math.min(iceRouting ? 4 : retention > .95 ? 1.6 : .4, speedLimit);
        if (iceRouting && !guidance.terminal()) speedLimit = Math.min(4, iceGuidance.speedLimit());
        else if (iceRouting) speedLimit = Math.min(speedLimit, iceGuidance.speedLimit());
        if (!chooseInput(terrain, state, target, retention, speedLimit, guidance.terminal() || !iceRouting)) {
            blocked = true;
            debug = "Braking boat for forward clearance or ice drift.";
        } else {
            blocked = false;
            debug = maneuverMode == BoatManeuverPlanner.Mode.ALIGN ? "Turning boat with drift compensation."
                    : iceRouting ? "Following the ice-road centre curve with rolling lookahead." : "Sailing to goal.";
        }
        if (player.tickCount - lastProgress > 240) {
            blocked = true;
            debug = "Boat route made no progress; try another destination or dismount.";
        }
        } finally {
            long elapsed = System.nanoTime() - started;
            averageWorkNanos = averageWorkNanos == 0 ? elapsed : averageWorkNanos * .95 + elapsed * .05;
            peakWorkNanos = Math.max(peakWorkNanos, elapsed);
            lastWorldReads = terrain == null ? 0 : terrain.blocks.size() + terrain.chunks.size();
            lastForecastSteps = forecastBudget == null ? 0 : forecastBudget.usedSteps();
            if (blocked && forecastBudget!=null && player!=null && player.tickCount>=nextControlDiagnostic) {
                nextControlDiagnostic=player.tickCount+100;
                adris.altoclef.Debug.logInternal("Boat control: "+controlDecision+"; steps="+lastForecastSteps
                        +"; selected="+intent+"; "+predictionMonitor.summary());
            }
        }
    }

    private static Goal surfaceGoal(Goal target) {
        if (!(target instanceof GoalBlock block)) return target;
        return new GoalBlock(block.x, block.y, block.z) {
            @Override public boolean isInGoal(int x, int y, int z) {
                return x == block.x && z == block.z && Math.abs(y - block.y) <= 1;
            }
            @Override public double heuristic(int x, int y, int z) {
                double dx = x - block.x, dz = z - block.z;
                return Math.sqrt(dx * dx + dz * dz) * 4 + Math.max(0, Math.abs(y - block.y) - 1);
            }
        };
    }

    private static boolean isAtGoal(Goal target, Vec3 point) {
        if (target == null) return false;
        return target instanceof GoalBlock block ? Math.abs(point.y - block.y) <= 1.25
                && target.isInGoal(Mth.floor(point.x), block.y, Mth.floor(point.z)) : target.isInGoal(BlockPos.containing(point));
    }

    private static Vec3 dockingPoint(Terrain terrain, Goal target, Vec3 current) {
        double x, z;
        if (target instanceof GoalXZ block) { x = block.getX() + .5; z = block.getZ() + .5; }
        else if (target instanceof GoalBlock block) { x = block.x + .5; z = block.z + .5; }
        else return current;
        Vec3 best = null;
        double score = Double.POSITIVE_INFINITY;
        for (double dx : new double[]{0, -.25, .25, -.49, .49}) for (double dz : new double[]{0, -.25, .25, -.49, .49}) {
            double error = dx * dx + dz * dz;
            if (error >= score) continue;
            Vec3 candidate = terrain.surface(x + dx, z + dz, current.y);
            if (candidate != null && terrain.traversable(current, candidate)) {
                best = candidate; score = error;
                if (score == 0) return best;
            }
        }
        return best;
    }

    public static boolean isGoalReached(AltoClef mod, Goal target) {
        AbstractBoat vessel = getControlledBoat(mod.getPlayer());
        if (vessel == null || !isAtGoal(target, vessel.position()) || vessel.isUnderWater()) return false;
        double retention = new Terrain(mod, vessel).retention(vessel.position());
        if (!Double.isFinite(retention) || retention >= 1) return false;
        retention = Math.max(retention, .989f);
        // Completion includes the remaining unpowered glide, so stopping a task
        // on blue ice cannot immediately drift the boat out of its target cell.
        Vec3 coastEnd = vessel.position().add(vessel.getDeltaMovement().multiply(retention / (1 - retention), 0, retention / (1 - retention)));
        return vessel.getDeltaMovement().horizontalDistance() < .015
                && Math.abs(angularVelocity(vessel)) < .15 && isAtGoal(target, coastEnd);
    }

    private static double limitForDistance(double distance, double retention) {
        double low = 0, high = 2;
        for (int sample = 0; sample < 16; sample++) {
            double candidate = (low + high) * .5;
            if (BoatSteering.stoppingDistance(candidate, retention) < distance) low = candidate; else high = candidate;
        }
        return low;
    }

    private static BoatSteering.State state() {
        return new BoatSteering.State(boat.position(), boat.getDeltaMovement(), boat.getYRot(),
                angularVelocity(boat));
    }

    private static float angularVelocity(AbstractBoat vessel) {
        return ((BoatAccessor)vessel).altoclef$getAngularVelocity();
    }

    private static void brake(BoatSteering.State state, double retention) {
        maneuverMode = BoatManeuverPlanner.Mode.BRAKE;
        intent = Double.isFinite(retention) ? iceControl
                ? BoatSteering.brakeRoad(state, retention)
                : BoatSteering.steer(state, state.position(), retention, 0, true)
                : BoatSteering.Input.NONE;
    }

    private static boolean chooseInput(Terrain terrain, BoatSteering.State state, Vec3 target, double retention, double speedLimit) {
        return chooseInput(terrain, state, target, retention, speedLimit, true);
    }

    private static boolean chooseInput(Terrain terrain, BoatSteering.State state, Vec3 target, double retention,
                                       double speedLimit, boolean stopAtTarget) {
        if (forecastBudget == null) forecastBudget = new BoatTrajectoryPredictor.Budget(768, 8_000_000);
        BoatIceCruisePlanner.Dynamics roadDynamics=iceRouting?roadDynamics(terrain):null;
        var immediate=iceRouting?BoatIceCruisePlanner.emergencyBrake(state,roadDynamics,forecastBudget)
                :java.util.Optional.<BoatSteering.Input>empty();
        if(iceRouting && !stopAtTarget && iceNavigator.isGentleRoute()) {
            var cruise=BoatIceCruisePlanner.choose(state,iceNavigator,roadDynamics,forecastBudget);
            if(cruise.isPresent()) {
                intent=cruise.get(); maneuverMode=BoatManeuverPlanner.Mode.DRIVE;
                controlDecision=forecastBudget.hasTime()?"checked ice continuation":"checked ice continuation; optimisation deadline";
                return true;
            }
        }
        java.util.function.BiPredicate<BoatManeuverPlanner.Mode, BoatSteering.Input> forecast =
                (mode, first) -> safeTrajectory(terrain, state, first, target, speedLimit, mode);
        var choice = iceControl ? BoatManeuverPlanner.chooseRoad(state, target, retention, speedLimit, stopAtTarget, forecast)
                : BoatManeuverPlanner.choose(state, target, retention, speedLimit, stopAtTarget, forecast);
        if (choice.isEmpty()) {
            controlDecision=!forecastBudget.hasTime()?"forecast deadline":forecastBudget.exhausted()
                    ?"forecast step limit":"no checked trajectory";
            if(immediate.isPresent()) { intent=immediate.get(); maneuverMode=BoatManeuverPlanner.Mode.BRAKE; }
            else brake(state,retention);
            return false;
        }
        intent = choice.get().input(); maneuverMode = choice.get().mode();
        controlDecision="checked "+maneuverMode;
        return true;
    }

    private static BoatIceCruisePlanner.Dynamics roadDynamics(Terrain terrain) {
        return new BoatIceCruisePlanner.Dynamics() {
                public double retention(BoatSteering.State current) {
                    double flat=terrain.flatIceRetention(current.position());
                    return Double.isFinite(flat)?flat:terrain.onIce(current.position())
                            ?terrain.groundFriction(current.position()):Double.NaN;
                }
                public BoatSteering.State step(BoatSteering.State current,BoatSteering.Input input) {
                    double friction=retention(current);
                    if(!Double.isFinite(friction) || Math.abs(current.velocity().y)>1e-9) return null;
                    var next=BoatSteering.step(current,input,friction);
                    boolean clear=terrain.flatIceSweep(current.position(),next.position())
                            || terrain.iceSegment(current.position(),next.position())
                                && !Double.isFinite(terrain.waterLevel(next.position()))
                                && terrain.clearAxes(current.position(),next.position())
                                && terrain.unclipped(current.position(),next.velocity());
                    return clear && iceNavigator.corridor(current.position(),next.position())?next:null;
                }
            };
    }

    private static boolean safeTrajectory(Terrain terrain, BoatSteering.State initial, BoatSteering.Input first,
                                          Vec3 target, double speedLimit, BoatManeuverPlanner.Mode mode) {
        int horizon = terrain.retention(initial.position()) > .95 ? 45 : 20;
        if (iceControl) return BoatTrajectoryPredictor.predictRoad(initial, first, current -> {
            if (!iceRouting) return new BoatTrajectoryPredictor.Guidance(target, speedLimit, true);
            var guidance = iceNavigator.preview(current.position(), current.velocity());
            return guidance.target() == null ? null : new BoatTrajectoryPredictor.Guidance(guidance.target(),
                    guidance.speedLimit(), guidance.terminal(),iceNavigator.isGentleRoute());
        }, mode, 16, new ForecastDynamics(terrain, initial), forecastBudget);
        return BoatTrajectoryPredictor.predict(initial, first, target, speedLimit, mode, horizon,
                new ForecastDynamics(terrain, initial), forecastBudget);
    }

    /** One candidate owns its native vertical history; all candidates share only the read-only terrain cache. */
    private static final class ForecastDynamics implements BoatTrajectoryPredictor.Dynamics {
        private final Terrain terrain;
        private final Vec3 start;
        private BoatFloatPhysics.Status previousStatus;
        private double verticalVelocity, lastVerticalMovement;
        private BoatSteering.State preparedState;
        private BoatFloatPhysics.Step floating;
        private boolean flat;

        ForecastDynamics(Terrain terrain, BoatSteering.State initial) {
            this.terrain = terrain; start = initial.position();
            AbstractBoat.Status nativeStatus = ((BoatAccessor)boat).altoclef$getStatus();
            previousStatus = nativeStatus == AbstractBoat.Status.IN_AIR ? BoatFloatPhysics.Status.IN_AIR
                    : nativeStatus == AbstractBoat.Status.ON_LAND ? BoatFloatPhysics.Status.ON_LAND
                    : nativeStatus == AbstractBoat.Status.IN_WATER ? BoatFloatPhysics.Status.IN_WATER : terrain.status(start);
            verticalVelocity = initial.velocity().y;
            lastVerticalMovement = ((BoatAccessor)boat).altoclef$getLastVerticalMovement();
        }

        private boolean prepare(BoatSteering.State current) {
            if (preparedState == current) return floating != null;
            preparedState = current; floating = null; flat = false;
            if (iceRouting && previousStatus == BoatFloatPhysics.Status.ON_LAND
                    && Math.abs(verticalVelocity) < 1e-9) {
                double friction = terrain.flatIceRetention(current.position());
                if (Double.isFinite(friction)) {
                    floating = BoatFloatPhysics.beforeControl(current.position(), verticalVelocity,
                            previousStatus, BoatFloatPhysics.Status.ON_LAND, boat.getBbHeight(), Double.NaN,
                            Float.NaN, boat.getGravity(), .04, friction, ignored -> false);
                    flat = true;
                    return true;
                }
            }
            SurfaceSample surface = terrain.sample(current.position());
            BoatFloatPhysics.Status currentStatus = surface.status();
            double waterLevel = surface.water();
            float entryWaterLevel = previousStatus == BoatFloatPhysics.Status.IN_AIR
                    && currentStatus == BoatFloatPhysics.Status.IN_WATER
                    ? terrain.waterLevelAbove(current.position(), lastVerticalMovement) : Float.NaN;
            if (previousStatus == BoatFloatPhysics.Status.IN_AIR && currentStatus == BoatFloatPhysics.Status.IN_WATER
                    && !Float.isFinite(entryWaterLevel)) return false;
            BoatFloatPhysics.Step candidate = BoatFloatPhysics.beforeControl(current.position(), verticalVelocity,
                    previousStatus, currentStatus, boat.getBbHeight(), waterLevel,
                    entryWaterLevel, boat.getGravity(), .04, surface.ground(), point -> terrain.clear(point, point));
            if (!Double.isFinite(candidate.retention()) || !terrain.clear(current.position(), candidate.position())) return false;
            floating = candidate;
            return true;
        }

        @Override public double retention(BoatSteering.State current) {
            return prepare(current) ? floating.retention() : Double.NaN;
        }

        @Override public BoatSteering.State step(BoatSteering.State current, BoatSteering.Input input) {
            if (!prepare(current)) return null;
            BoatSteering.State horizontal = BoatSteering.step(new BoatSteering.State(floating.position(), current.velocity(),
                    current.yaw(), current.angularVelocity()), input, floating.retention());
            if (flat && terrain.flatIceSweep(floating.position(), horizontal.position())
                    && iceNavigator.corridor(floating.position(), horizontal.position())) {
                // A verified full-cube floor clips this native gravity step to
                // zero; the air/entity proof leaves both horizontal axes intact.
                verticalVelocity = 0;
                lastVerticalMovement = floating.verticalVelocity();
                previousStatus = BoatFloatPhysics.Status.ON_LAND;
                return new BoatSteering.State(horizontal.position(), horizontal.velocity(),
                        horizontal.yaw(), horizontal.angularVelocity());
            }
            Vec3 movement = new Vec3(horizontal.velocity().x, floating.verticalVelocity(), horizontal.velocity().z);
            Vec3 clipped = terrain.clip(floating.position(), movement);
            if (clipped == null || Math.abs(clipped.x - movement.x) > 1e-7 || Math.abs(clipped.z - movement.z) > 1e-7) return null;
            Vec3 next = floating.position().add(clipped);
            if (start.y - next.y > .8 || !terrain.clearAxes(floating.position(), next)) return null;
            if (iceRouting && (!iceNavigator.corridor(floating.position(), next)
                    || !terrain.iceStep(floating.position(), next))) return null;
            verticalVelocity = Math.abs(clipped.y - movement.y) > 1e-7 ? 0 : movement.y;
            lastVerticalMovement = movement.y;
            previousStatus = floating.status();
            return new BoatSteering.State(next, new Vec3(horizontal.velocity().x, verticalVelocity, horizontal.velocity().z),
                    horizontal.yaw(), horizontal.angularVelocity());
        }

        @Override public boolean safeCoast(BoatSteering.State current) {
            double coastRetention = Math.max(.989f, terrain.retention(current.position()));
            if (!Double.isFinite(coastRetention) || coastRetention >= 1) return false;
            Vec3 stopped = current.position().add(current.velocity().multiply(coastRetention / (1 - coastRetention), 0,
                    coastRetention / (1 - coastRetention)));
            if (iceRouting && iceNavigator.corridor(current.position(), stopped)
                    && terrain.flatIceSweep(current.position(), stopped)) return true;
            return (!iceRouting || iceNavigator.corridor(current.position(), stopped)
                    && terrain.iceSegment(current.position(), stopped))
                    && terrain.traversable(current.position(), stopped);
        }
        @Override public boolean rollingCorridor(BoatSteering.State current) {
            return iceRouting && iceNavigator.canCruise(current.position(),current.velocity());
        }
    }

    public static void hold(AltoClef mod, Object requester) {
        if (!acquire(mod, requester, true)) return;
        if (getControlledBoat(player) != null) brake(state(), new Terrain(mod, boat).retention(boat.position()));
        debug = getControlledBoat(player) == null ? "Passenger seat cannot steer this boat." : "Holding boat; braking residual glide.";
    }

    public static void requestDismount(AltoClef mod, Object requester) {
        if (!acquire(mod, requester, true)) return;
        if (getControlledBoat(player) != null) {
            brake(state(), new Terrain(mod, boat).retention(boat.position()));
            if (boat.getDeltaMovement().horizontalDistance() > .015
                    || Math.abs(angularVelocity(boat)) > .2) {
                debug = "Stopping boat before dismount."; return;
            }
        }
        if (BoatDismountHelper.safeLocation(mod, boat).isEmpty()) {
            debug = "Waiting for safe footing or water to dismount.";
            if (player.tickCount % 20 == 0) player.setYRot(player.getYRot() + 90);
            return;
        }
        intent = BoatSteering.Input.NONE;
        dismount = true;
        debug = "Dismounting boat through native Shift input.";
    }

    public static void release(AltoClef mod, Object requester) { if (owner == requester) releaseAll(mod); }

    public static void releaseAll(AltoClef mod) {
        if (boat != null && player != null && boat.getControllingPassenger() == player) boat.setInput(false, false, false, false);
        if (player != null) {
            for (Input key : KEYS) {
                mod.getInputControls().release(key);
                mod.getClientBaritone().getInputOverrideHandler().setInputForceState(key, false);
            }
            if (player.input == riderInput) player.input = new KeyboardInput(Minecraft.getInstance().options);
        }
        player = null; boat = null; owner = null; goal = navigationGoal = null; riderInput = null;
        navigator.reset(); intent = BoatSteering.Input.NONE;
        iceNavigator.reset(); iceRouting = false;
        iceControl = false;
        iceWaterExit = null;
        nextWaterExitTick = Integer.MIN_VALUE;
        dismount = navigating = blocked = false;
        forecastBudget = null; lastForecastSteps = 0;
        predictionMonitor.reset(); controlDecision="idle"; nextControlDiagnostic=0;
    }

    public static void endTick(AltoClef mod) {
        if (safety) return;
        if (!mod.getTaskRunner().isActive() || mod.getPlayer() == null
                || !(mod.getPlayer().getVehicle() instanceof AbstractBoat)) { releaseAll(mod); return; }
        if (requestTick != mod.getPlayer().tickCount || player != mod.getPlayer() || player.getVehicle() != boat) {
            if (getControlledBoat(mod.getPlayer()) != null) hold(mod, IDLE);
            else { releaseAll(mod); return; }
        }
        // Native boat paddling occupies the rider's hands. A foreground meal
        // must receive a real idle sample before it can begin using its food.
        if (mod.getFoodChain().isTryingToEat() || player.isUsingItem()) {
            intent = BoatSteering.Input.NONE;
            BoatIceCruisePlanner.reset(iceNavigator);
            controlDecision="item use; neutral paddles";
        }
        applyInputs(mod);
    }

    /** Late Baritone IN: submit paddles before boat.tick, then vanilla samples the same retained rider input. */
    public static void applyInputs(AltoClef mod) {
        if (safety || !isActive()) return;
        for (Input key : KEYS) {
            boolean down = key == Input.MOVE_FORWARD && intent.forward() || key == Input.MOVE_BACK && intent.back()
                    || key == Input.MOVE_LEFT && intent.left() || key == Input.MOVE_RIGHT && intent.right()
                    || key == Input.SNEAK && dismount;
            if (down) mod.getInputControls().hold(key); else mod.getInputControls().release(key);
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(key, down);
        }
        riderInput.setIntent(intent, dismount);
        player.input = riderInput;
        if (boat.getControllingPassenger() == player) boat.setInput(!dismount && intent.left(), !dismount && intent.right(),
                !dismount && intent.forward(), !dismount && intent.back());
        if (boat.getControllingPassenger()==player && !dismount && iceControl && Double.isFinite(lastRetention))
            predictionMonitor.submitted(boat.tickCount,state(),intent,lastRetention);
    }

    private record SurfaceSample(double water, double ground) {
        BoatFloatPhysics.Status status() {
            return Double.isFinite(water) ? BoatFloatPhysics.Status.IN_WATER
                    : Double.isFinite(ground) ? BoatFloatPhysics.Status.ON_LAND : BoatFloatPhysics.Status.IN_AIR;
        }
        double retention() { return Double.isFinite(ground) && !Double.isFinite(water) ? ground : .9f; }
    }

    /** Plan on a single ice layer; exact hull/rider sweeps still validate every accepted edge. */
    private static final class IceRoadTerrain implements IceRoadNavigator.Terrain {
        private final Terrain terrain;
        private final int floorY, airLayers;
        IceRoadTerrain(Terrain terrain) {
            this.terrain = terrain;
            floorY = Mth.floor(terrain.origin.y - .001);
            airLayers = Math.max(2, Mth.ceil(terrain.rider.maxY - terrain.origin.y));
        }
        @Override public Cell cell(int x, int z) {
            return switch (IceRoadGeometry.cell(terrain.iceBlocks, x, floorY, z, airLayers)) {
                case ICE -> Cell.ICE;
                case AIR -> Cell.AIR;
                case BLOCKED -> Cell.BLOCKED;
                case UNKNOWN -> Cell.UNKNOWN;
            };
        }
        @Override public boolean traversable(Vec3 from, Vec3 to) {
            return Math.abs(from.y - to.y) < .02 && Math.abs(from.y - terrain.origin.y) < .02
                    && terrain.iceSegment(from, to) && terrain.clear(from, to);
        }
    }

    /**
     * A conservative full-cube proof, shared with native-block regression tests.
     * Bounds include the native collision iterator's one-block shape halo.
     * Sparse floors, water, other blocks and unknown columns take the general path.
     */
    static boolean clearFlatIcePrism(IceRoadGeometry.Blocks blocks, int minX, int maxX,
                                    int minZ, int maxZ, int feetY, int top) {
        for(int x=minX;x<maxX;x++) for(int z=minZ;z<maxZ;z++) {
            if(!blocks.loaded(x,z)) return false;
            BlockState floor=blocks.block(x,feetY-1,z);
            if(floor==null || !BoatTerrainGeometry.isIce(floor)) return false;
            for(int y=feetY;y<top;y++) {
                BlockState state=blocks.block(x,y,z);
                if(state==null || !state.isAir()) return false;
            }
        }
        return true;
    }

    /** Native float accumulation over exactly the full ice cubes touching the bottom slab. */
    static double flatIceFriction(IceRoadGeometry.Blocks blocks, Vec3 foot, double half) {
        if(Math.abs(foot.y-Math.rint(foot.y))>1e-7) return Double.NaN;
        int y=(int)Math.rint(foot.y)-1, count=0; float sum=0;
        for(int x=Mth.floor(foot.x-half);x<Mth.ceil(foot.x+half);x++)
            for(int z=Mth.floor(foot.z-half);z<Mth.ceil(foot.z+half);z++) {
                if(!blocks.loaded(x,z)) return Double.NaN;
                BlockState state=blocks.block(x,y,z);
                if(state==null || !BoatTerrainGeometry.isIce(state)) return Double.NaN;
                sum+=state.getBlock().getFriction(); count++;
            }
        return count==0 ? Double.NaN : (double)(sum/count);
    }

    /** Loaded collision/fluid queries shared by planning and forward simulation. */
    private static final class Terrain implements BoatPathPlanner.Terrain {
        private record SurfaceKey(double x, double z, long y) { }
        private record SweepKey(Vec3 from, Vec3 to) { }
        private record FlatBox(int minX, int maxX, int minZ, int maxZ, int top) { }
        private record FloorBox(int minX, int maxX, int minZ, int maxZ) { }
        private final AltoClef mod;
        private final AbstractBoat vessel;
        private final Vec3 origin;
        private final AABB hull, rider;
        // A Terrain lives for one synchronous navigation tick. Never retain its
        // world/entity observations in the multi-tick search frontier.
        private final Map<SurfaceKey, Vec3> surfaces = new HashMap<>();
        private final Map<Long, BlockState> blocks = new HashMap<>();
        private final Map<Long, Boolean> chunks = new HashMap<>();
        private final Map<Vec3, SurfaceSample> samples = new HashMap<>();
        private final Map<Vec3, IceRoadGeometry.Contact> iceContacts = new HashMap<>();
        private final Map<FlatBox, Boolean> flatGeometry = new HashMap<>();
        private final Map<FloorBox, Double> flatFriction = new HashMap<>();
        private final Map<Long, Boolean> flatEntities = new HashMap<>();
        private final IceRoadGeometry.Blocks iceBlocks;
        private final Map<SweepKey, Boolean> sweeps = new HashMap<>();
        private final Map<AABB, Boolean> hullClear = new HashMap<>(), riderClear = new HashMap<>();

        Terrain(AltoClef mod, AbstractBoat vessel) {
            this.mod = mod; this.vessel = vessel; origin = vessel.position();
            hull = vessel.getBoundingBox();
            // Sweep the rider around both seat offsets when the hull turns.
            rider = BoatTerrainGeometry.riderEnvelope(hull, mod.getPlayer().getBoundingBox(), origin);
            iceBlocks = new IceRoadGeometry.Blocks() {
                @Override public boolean loaded(int x, int z) {
                    return loadedPosition(new BlockPos(x, Mth.floor(origin.y - .001), z));
                }
                @Override public BlockState block(int x, int y, int z) { return blockAt(new BlockPos(x, y, z)); }
                @Override public net.minecraft.world.level.BlockGetter collisionView() { return mod.level(); }
            };
        }

        @Override public Vec3 surface(double x, double z, double nearY) {
            SurfaceKey key = new SurfaceKey(x, z, Math.round(nearY * 16));
            if (surfaces.containsKey(key)) return surfaces.get(key);
            Vec3 found = findSurface(x, z, nearY);
            surfaces.put(key, found); return found;
        }

        private Vec3 findSurface(double x, double z, double nearY) {
            // A boat may contact a narrow ice strip or either side of a one-block
            // ice-road gap. Do not demand all nine foot probes have support.
            double half = vessel.getBbWidth() * .5;
            AABB footprint = new AABB(x - half, nearY - 1.05, z - half, x + half, nearY + 1.05, z + half);
            if (!loaded(footprint)) return null;
            double water = -Double.MAX_VALUE;
            for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(footprint.minX, footprint.minY, footprint.minZ),
                    BlockPos.containing(footprint.maxX, footprint.maxY, footprint.maxZ))) {
                var state = blockAt(pos);
                var fluid = state.getFluidState();
                if (fluid.is(FluidTags.WATER) && !state.is(Blocks.BUBBLE_COLUMN)) {
                    double level = pos.getY() + fluid.getHeight(mod.level(), pos);
                    // Steady float equilibrium from floatBoat's .65 buoyancy,
                    // not the lower, one-time IN_AIR -> IN_WATER reset height.
                    double y = level - vessel.getBbHeight() * .65;
                    if (Math.abs(y - nearY) <= .8) water = Math.max(water, y);
                }
            }
            if (water != -Double.MAX_VALUE) {
                Vec3 point = new Vec3(x, water, z);
                if (clear(point, point)) return point;
            }
            double floor = BoatTerrainGeometry.supportHeight(footprint, nearY,
                    mod.level().getBlockCollisions(vessel, footprint));
            Vec3 point = new Vec3(x, floor, z);
            return floor != -Double.MAX_VALUE && clear(point, point) ? point : null;
        }

        double waterLevel(Vec3 point) {
            AABB raw = vessel.getBoundingBox().move(point.subtract(origin));
            if (!loaded(raw.inflate(0, .02, 0))) return Double.NaN;
            double highest = Double.NaN;
            for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(raw.minX, raw.minY, raw.minZ),
                    BlockPos.containing(Math.nextDown(raw.maxX), raw.minY + .001, Math.nextDown(raw.maxZ)))) {
                var fluid = blockAt(pos).getFluidState();
                if (fluid.is(FluidTags.WATER)) {
                    float level = (float)pos.getY() + fluid.getHeight(mod.level(), pos);
                    if (raw.minY < level) highest = Double.isNaN(highest) ? level : Math.max(highest, level);
                }
            }
            return highest;
        }

        double groundFriction(Vec3 point) {
            var contact = iceContact(point);
            return contact.loaded() ? contact.friction() : Double.NaN;
        }

        IceRoadGeometry.Contact iceContact(Vec3 point) {
            return iceContacts.computeIfAbsent(point,
                    at -> IceRoadGeometry.contact(iceBlocks, at, vessel.getBbWidth() * .5));
        }

        boolean onIce(Vec3 point) {
            return !Double.isFinite(waterLevel(point)) && iceContact(point).loaded() && iceContact(point).ice() > 0;
        }

        double flatIceRetention(Vec3 point) {
            if (!flatIceSweep(point, point)) return Double.NaN;
            double half = vessel.getBbWidth() * .5;
            FloorBox key = new FloorBox(Mth.floor(point.x-half), Mth.ceil(point.x+half),
                    Mth.floor(point.z-half), Mth.ceil(point.z+half));
            return flatFriction.computeIfAbsent(key, ignored -> flatIceFriction(iceBlocks, point, half));
        }

        boolean flatIceSweep(Vec3 from, Vec3 to) {
            if (Math.abs(from.y-origin.y)>1e-7 || Math.abs(to.y-origin.y)>1e-7
                    || Math.abs(origin.y-Math.rint(origin.y))>1e-7) return false;
            Vec3 offset=from.subtract(origin), movement=to.subtract(from);
            AABB body=hull.move(offset).expandTowards(movement), passenger=rider.move(offset).expandTowards(movement);
            AABB occupied=body.minmax(passenger);
            FlatBox key=new FlatBox(Mth.floor(occupied.minX)-1, Mth.ceil(occupied.maxX)+1,
                    Mth.floor(occupied.minZ)-1, Mth.ceil(occupied.maxZ)+1, Mth.ceil(occupied.maxY)+1);
            boolean clear=flatGeometry.computeIfAbsent(key, ignored -> clearFlatIcePrism(iceBlocks,
                    key.minX(),key.maxX(),key.minZ(),key.maxZ(),(int)Math.rint(origin.y),key.top()));
            if (!clear) return false;
            int minTileX=Mth.floor(occupied.minX/4),maxTileX=Mth.floor(Math.nextDown(occupied.maxX)/4);
            int minTileZ=Mth.floor(occupied.minZ/4),maxTileZ=Mth.floor(Math.nextDown(occupied.maxZ)/4);
            for(int x=minTileX;x<=maxTileX;x++) for(int z=minTileZ;z<=maxTileZ;z++) {
                long tile=(long)x<<32 ^ (z&0xffffffffL);
                Boolean empty=flatEntities.get(tile);
                if(empty==null) {
                    AABB patch=new AABB(x*4,origin.y,z*4,x*4+4,
                            Math.max(hull.maxY,rider.maxY),z*4+4);
                    empty=mod.level().getEntityCollisions(vessel,patch).isEmpty()
                            && mod.level().getEntityCollisions(mod.getPlayer(),patch).isEmpty();
                    flatEntities.put(tile,empty);
                }
                if(!empty) return false;
            }
            return true;
        }

        boolean iceStep(Vec3 from, Vec3 to) {
            if (Math.abs(to.y - origin.y) > .02) return false;
            var before = iceContact(from);
            var after = iceContact(to);
            // Starting against a non-ice border must allow recovery toward the
            // centre. Once clear, forecasts cannot reintroduce that drag.
            return IceRoadGeometry.allowsRecovery(before, after);
        }

        boolean iceSegment(Vec3 from, Vec3 to) {
            Vec3 previous = from;
            int count = Math.max(1, (int)Math.ceil(from.distanceTo(to) / .25));
            for (int index = 1; index <= count; index++) {
                Vec3 next = from.lerp(to, (double)index / count);
                if (!iceStep(previous, next)) return false;
                previous = next;
            }
            return true;
        }

        Vec3 waterExit() {
            Vec3 best = null;
            double bestScore = Double.POSITIVE_INFINITY;
            for (int radius = 2; radius <= 5; radius++) for (int direction = 0; direction < 8; direction++) {
                double angle = direction * Math.PI / 4;
                // The temporary GoalXZ drives to the block centre. Validate that
                // exact point: rounding a fractional shore sample afterwards can
                // otherwise leave the actual goal still perched on the ice.
                double x = Mth.floor(origin.x + Math.cos(angle) * radius) + .5;
                double z = Mth.floor(origin.z + Math.sin(angle) * radius) + .5;
                Vec3 candidate = surface(x, z, origin.y);
                if (candidate == null || !Double.isFinite(waterLevel(candidate)) || !traversable(origin, candidate)) continue;
                double score = candidate.distanceToSqr(origin);
                if (goal instanceof GoalXZ target) score += .2 * Math.hypot(candidate.x - target.getX() - .5, candidate.z - target.getZ() - .5);
                else if (goal instanceof GoalBlock target) score += .2 * Math.hypot(candidate.x - target.x - .5, candidate.z - target.z - .5);
                if (score < bestScore) { bestScore = score; best = candidate; }
            }
            return best;
        }

        SurfaceSample sample(Vec3 point) {
            SurfaceSample cached = samples.get(point);
            if (cached != null) return cached;
            double water = waterLevel(point);
            // Native status gives water precedence. Land's collision-shape
            // average is irrelevant while floating and was the largest repeated
            // per-prediction query on an open lake.
            SurfaceSample found = new SurfaceSample(water, Double.isFinite(water) ? Double.NaN : groundFriction(point));
            samples.put(point, found);
            return found;
        }

        BoatFloatPhysics.Status status(Vec3 point) {
            return sample(point).status();
        }

        double retention(Vec3 point) {
            return sample(point).retention();
        }

        float waterLevelAbove(Vec3 point, double lastYd) {
            AABB raw = vessel.getBoundingBox().move(point.subtract(origin));
            int minY = Mth.floor(raw.maxY), maxY = Mth.ceil(raw.maxY - lastYd);
            for (int y = minY; y < maxY; y++) {
                float height = 0;
                for (int x = Mth.floor(raw.minX); x < Mth.ceil(raw.maxX); x++) {
                    for (int z = Mth.floor(raw.minZ); z < Mth.ceil(raw.maxZ); z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!loadedPosition(pos)) return Float.NaN;
                        var fluid = blockAt(pos).getFluidState();
                        if (fluid.is(FluidTags.WATER)) height = Math.max(height, fluid.getHeight(mod.level(), pos));
                    }
                }
                if (height < 1) return y + height;
            }
            return maxY + 1;
        }

        Vec3 clip(Vec3 point, Vec3 movement) {
            AABB box = vessel.getBoundingBox().move(point.subtract(origin));
            if (!loaded(box.expandTowards(movement))) return null;
            return Entity.collideBoundingBox(vessel, movement, box, mod.level(),
                    mod.level().getEntityCollisions(vessel, box.expandTowards(movement)));
        }

        boolean unclipped(Vec3 point,Vec3 movement) {
            Vec3 clipped=clip(point,movement);
            return clipped!=null && Math.abs(clipped.x-movement.x)<1e-7
                    && Math.abs(clipped.z-movement.z)<1e-7;
        }

        boolean clearAxes(Vec3 from, Vec3 to) {
            Vec3 delta = to.subtract(from), cursor = from;
            for (Direction.Axis axis : Direction.axisStepOrder(delta)) {
                if (delta.get(axis) == 0) continue;
                Vec3 next = cursor.with(axis, to.get(axis));
                if (!clear(cursor, next)) return false;
                cursor = next;
            }
            return true;
        }

        @Override public boolean traversable(Vec3 from, Vec3 to) {
            int samples = Math.max(1, (int)Math.ceil(from.distanceTo(to) / .3));
            Vec3 previous = from;
            for (int sample = 1; sample <= samples; sample++) {
                Vec3 along = from.lerp(to, (double)sample / samples);
                Vec3 next = surface(along.x, along.z, previous.y);
                if (next == null || next.y - previous.y > .125 || previous.y - next.y > .8) return false;
                double high = Math.max(previous.y, next.y);
                Vec3 raisedFrom = new Vec3(previous.x, high, previous.z), raisedTo = new Vec3(next.x, high, next.z);
                if (previous.y != high && !clear(previous, raisedFrom)
                        || !clear(raisedFrom, raisedTo)
                        || next.y != high && !clear(raisedTo, next)) return false;
                previous = next;
            }
            return Math.abs(previous.y - to.y) <= .2;
        }

        private boolean clear(Vec3 from, Vec3 to) {
            SweepKey key = new SweepKey(from, to);
            Boolean cached = sweeps.get(key);
            if (cached != null) return cached;
            boolean found = computeClear(from, to);
            sweeps.put(key, found);
            return found;
        }

        private boolean computeClear(Vec3 from, Vec3 to) {
            Vec3 offset = from.subtract(origin), movement = to.subtract(from);
            AABB bodyStart=hull.move(offset),passengerStart=rider.move(offset);
            AABB body = bodyStart.expandTowards(movement), passenger = passengerStart.expandTowards(movement);
            if (!loaded(body.minmax(passenger))
                    || !hullClear.computeIfAbsent(body, box -> mod.level().noCollision(vessel, box))
                        && !sweepClear(vessel,bodyStart,movement)
                    || !riderClear.computeIfAbsent(passenger, box -> mod.level().noCollision(mod.getPlayer(), box))
                        && !sweepClear(mod.getPlayer(),passengerStart,movement)) return false;
            AABB occupied = body.minmax(passenger).inflate(0, .025, 0);
            AABB hazardBody=bodyStart.inflate(0,.025,0),hazardRider=passengerStart.inflate(0,.025,0);
            for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(occupied.minX, occupied.minY - .02, occupied.minZ),
                    BlockPos.containing(occupied.maxX, occupied.maxY, occupied.maxZ))) {
                AABB voxel=new AABB(pos);
                if (!BoatTerrainGeometry.sweptIntersects(hazardBody,movement,voxel)
                        && !BoatTerrainGeometry.sweptIntersects(hazardRider,movement,voxel)) continue;
                var state = blockAt(pos);
                var fluid = state.getFluidState();
                if (fluid.is(FluidTags.LAVA) || state.is(Blocks.BUBBLE_COLUMN)
                        || !fluid.is(FluidTags.WATER) && !BoatTerrainGeometry.isIce(state)
                            && MovementHelper.avoidWalkingInto(state)) return false;
                if (fluid.is(FluidTags.WATER) && pos.getY() + fluid.getHeight(mod.level(), pos) > Math.min(from.y, to.y) + vessel.getBbHeight() + .01) return false;
            }
            return true;
        }

        private boolean sweepClear(Entity actor,AABB start,Vec3 movement) {
            AABB bounds=start.expandTowards(movement);
            if (!mod.level().noBorderCollision(actor,bounds)) return false;
            // The expanded box is a query bound, not the swept body: its empty
            // diagonal corners can contain banks which the boat never touches.
            for (var shape:mod.level().getCollisions(actor,bounds))
                for (AABB obstacle:shape.toAabbs())
                    if (BoatTerrainGeometry.sweptIntersects(start,movement,obstacle)) return false;
            return true;
        }

        private boolean loaded(AABB box) {
            int minY = Mth.floor(box.minY), maxY = Mth.floor(box.maxY);
            if (mod.level().isOutsideBuildHeight(new BlockPos(0, minY, 0))
                    || mod.level().isOutsideBuildHeight(new BlockPos(0, maxY, 0))) return false;
            // Chunk presence depends on XZ, not every Y voxel in the volume.
            for (int x = Mth.floor(box.minX) >> 4; x <= (Mth.floor(box.maxX) >> 4); x++) {
                for (int z = Mth.floor(box.minZ) >> 4; z <= (Mth.floor(box.maxZ) >> 4); z++) {
                    if (!loadedChunk(x, z, minY)) return false;
                }
            }
            return true;
        }

        private boolean loadedPosition(BlockPos position) {
            return !mod.level().isOutsideBuildHeight(position)
                    && loadedChunk(position.getX() >> 4, position.getZ() >> 4, position.getY());
        }

        private boolean loadedChunk(int x, int z, int y) {
            long key = (long)x << 32 ^ (z & 0xffffffffL);
            Boolean found = chunks.get(key);
            if (found == null) {
                found = mod.level().hasChunkAt(new BlockPos(x << 4, y, z << 4));
                chunks.put(key, found);
            }
            return found;
        }

        private BlockState blockAt(BlockPos position) {
            long key = position.asLong();
            BlockState cached = blocks.get(key);
            if (cached == null) {
                cached = mod.level().getBlockState(position);
                blocks.put(key, cached);
            }
            return cached;
        }
    }
}
