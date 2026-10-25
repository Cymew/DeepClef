package adris.altoclef.util.helpers;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Horizontal paddle dynamics from 26.3 AbstractBoat.floatBoat/controlBoat. */
public final class BoatSteering {
    public record State(Vec3 position, Vec3 velocity, float yaw, float angularVelocity) { }
    public record Input(boolean left, boolean right, boolean forward, boolean back) {
        public static final Input NONE = new Input(false, false, false, false);
    }

    private BoatSteering() { }

    /**
     * One horizontal tick: damping, paddle torque, heading change, thrust, movement.
     * Retention is the actual surface value (water .9; mean touching block friction
     * on land), not horse friction and not half the land friction. Buoyancy and
     * vertical collisions remain the caller's terrain responsibility.
     */
    public static State step(State state, Input input, double retention) {
        if (!valid(state) || input == null || !validRetention(retention)) {
            throw new IllegalArgumentException("Invalid boat prediction state");
        }
        float turn = state.angularVelocity * (float) retention;
        if (input.left) turn -= 1.0f;
        if (input.right) turn += 1.0f;
        float yaw = state.yaw + turn;
        float thrust = 0;
        if (input.left != input.right && !input.forward && !input.back) thrust += .005f;
        if (input.forward) thrust += .04f;
        if (input.back) thrust -= .005f;
        float radians = yaw * ((float) Math.PI / 180);
        Vec3 velocity = new Vec3(state.velocity.x * retention + Mth.sin(-radians) * thrust,
                0, state.velocity.z * retention + Mth.cos(radians) * thrust);
        return new State(state.position.add(velocity), velocity, yaw, turn);
    }

    /**
     * Distance spent braking a forward, collinear velocity with S, including the
     * current tick's damping. Sideways velocity must additionally be swept by the
     * caller; this scalar is not a promise that an arbitrarily facing boat stops.
     */
    public static double stoppingDistance(double speed, double retention) {
        if (!Double.isFinite(speed) || speed < 0 || !validRetention(retention)) return Double.POSITIVE_INFINITY;
        if (speed == 0) return 0;
        // At retention==1 this closed form avoids an input-dependent loop bound.
        if (retention == 1) {
            double ticks = Math.max(0, Math.ceil(speed / (double) .005f) - 1);
            return Math.max(0, ticks * speed - (double) .005f * ticks * (ticks + 1) / 2);
        }
        if (retention == 0 || speed * retention <= (double) .005f) return 0;
        // v(n) = r^n * (v(0) + b / (1-r)) - b / (1-r).
        // There are ceil(crossing)-1 strictly positive post-braking velocities.
        double scaledSpeed = speed * ((1 - retention) / (double) .005f);
        double logRatio = Double.isFinite(scaledSpeed) ? Math.log1p(scaledSpeed)
                : Math.log(speed) + Math.log1p(-retention) - Math.log((double) .005f);
        double crossing = logRatio / -Math.log(retention);
        if (crossing > 4096.5) return Double.POSITIVE_INFINITY;
        if (crossing >= 4095.5) {
            // Preserve the old 4096-tick guard at its floating-point boundary.
            // This exceptional case is outside ordinary <=4 block/tick control.
            double distance = 0;
            for (int tick = 0; tick < 4096; tick++) {
                speed = speed * retention - (double) .005f;
                if (speed <= 0) return distance;
                distance += speed;
            }
            return Double.POSITIVE_INFINITY;
        }
        int ticks = Math.max(0, (int) Math.ceil(crossing) - 1);
        if (ticks == 0) return speed * retention - (double) .005f;

        // A segment maps v -> a*v-b*q and adds c*v-b*d to distance.
        // Compose powers of two rather than subtracting nearly equal geometric
        // sums when r approaches 1. At most 12 small iterations replace a full
        // braking replay inside every speed-limit bisection and forecast tick.
        double a = retention, q = 1, c = retention, d = 1;
        double totalA = 1, totalQ = 0, totalC = 0, totalD = 0;
        for (int remaining = ticks; remaining != 0; remaining >>>= 1) {
            if ((remaining & 1) != 0) {
                totalC += c * totalA;
                totalD += c * totalQ + d;
                totalQ = a * totalQ + q;
                totalA *= a;
            }
            d = 2 * d + c * q;
            c *= 1 + a;
            q *= 1 + a;
            a *= a;
        }
        double forwardDistance = speed * totalC;
        double brakeDistance = (double) .005f * totalD;
        double distance = Math.max(0, forwardDistance - brakeDistance);
        // Cover accumulated rounding of both the original tick recurrence and
        // segment arithmetic, including a near-zero final velocity. Keep the
        // result conservative so speed selection never spends extra clearance.
        double roundingReserve = Math.ulp(Math.max(forwardDistance, brakeDistance)) * (4.0 * ticks + 32);
        return distance + roundingReserve;
    }

    /**
     * Native-key steering only. Rate feedback brakes angular inertia; velocity
     * feedback selects a paddle pulse. Sharp turns first brake along actual motion.
     * A changing speedLimit should account for upcoming bends and full-volume
     * collision clearance; no position, yaw or velocity is assigned to the entity.
     */
    public static Input steer(State state, Vec3 target, double retention, double speedLimit, boolean stopAtTarget) {
        if (!valid(state) || !finite(target) || !validRetention(retention)
                || !Double.isFinite(speedLimit) || speedLimit < 0) return Input.NONE;
        if (!stopAtTarget) return cruise(state, target, retention, speedLimit);
        Vec3 delta = target.subtract(state.position).multiply(1, 0, 1);
        double distance = delta.horizontalDistance();
        double speed = state.velocity.horizontalDistance();
        if (stopAtTarget && retention < 1 && speed < .035) {
            Vec3 coastEnd = state.position.add(state.velocity.multiply(retention / (1 - retention), 0, retention / (1 - retention)));
            if (coastEnd.subtract(target).horizontalDistance() < .28) {
                // Translation already has a safe unpowered endpoint. Do not
                // chase its changing close-range bearing while a fast angular
                // velocity spins the hull around that endpoint. Brake rotation
                // using native paddles and the smaller translation impulse.
                float dampedTurn = state.angularVelocity * (float) retention;
                if (Math.abs(dampedTurn) <= .5f) return Input.NONE;
                boolean left = dampedTurn > 0, right = dampedTurn < 0;
                Input free = new Input(left, right, false, false);
                Input reverse = new Input(left, right, false, true);
                double freeSpeed = step(state, free, retention).velocity.horizontalDistanceSqr();
                double reverseSpeed = step(state, reverse, retention).velocity.horizontalDistanceSqr();
                return reverseSpeed < freeSpeed - 1e-12 ? reverse : free;
            }
        }
        double wantedSpeed = Math.min(speedLimit, 4);
        if (stopAtTarget) wantedSpeed = Math.min(wantedSpeed, safeSpeed(Math.max(0, distance - .10), retention));
        if (distance < .18 && stopAtTarget) wantedSpeed = 0;
        float targetYaw = distance > 1e-7 ? yawOf(delta) : state.yaw;
        float motionYaw = speed > 1e-7 ? yawOf(state.velocity) : state.yaw;
        double motionError = Math.abs(Mth.wrapDegrees(targetYaw - motionYaw));
        boolean braking = speed > wantedSpeed + .005
                || speed > .08 && motionError > 40
                || wantedSpeed == 0 && speed > .004;
        float desiredYaw = braking && speed > .004 ? motionYaw : targetYaw;
        if (wantedSpeed == 0 && speed <= .004) desiredYaw = state.yaw;
        Vec3 desiredVelocity = braking ? Vec3.ZERO
                : distance < 1e-7 ? Vec3.ZERO : delta.scale(wantedSpeed / distance);
        if (!braking && speed > .02) {
            // Face the acceleration needed to cancel drift, rather than chasing
            // the target bearing while an old lateral velocity persists on ice.
            Vec3 correction = desiredVelocity.subtract(state.velocity.multiply(retention, 0, retention));
            if (correction.horizontalDistance() > .01) desiredYaw = yawOf(correction);
        }
        float error = Mth.wrapDegrees(desiredYaw - state.yaw);
        double torque = error * .30 - state.angularVelocity * retention * 1.15;
        boolean left = torque < -.5, right = torque > .5;
        // Native A/D without W/S produces a small forward impulse. Compare that
        // real impulse against S as well instead of treating a turn as stationary.
        Input best = new Input(left, right, false, false);
        double bestScore = velocityError(step(state, best, retention).velocity, desiredVelocity);
        Input reverse = new Input(left, right, false, true);
        double reverseScore = velocityError(step(state, reverse, retention).velocity, desiredVelocity);
        if (reverseScore < bestScore - 1e-12) { best = reverse; bestScore = reverseScore; }
        // W while the hull is facing across the intended path creates ice drift.
        if (!braking && Math.abs(error) < 16 && Math.abs(state.angularVelocity) < 3.5) {
            Input forward = new Input(left, right, true, false);
            if (velocityError(step(state, forward, retention).velocity, desiredVelocity) < bestScore - 1e-12) best = forward;
        }
        return best;
    }

    /** A moving curve target is a bearing reference, never another docking point. */
    public static Input steerRoad(State state, Vec3 target, double retention, double speedLimit, boolean terminal) {
        if (!valid(state) || !finite(target) || !validRetention(retention)
                || !Double.isFinite(speedLimit) || speedLimit < 0) return Input.NONE;
        if (terminal) {
            Vec3 delta=target.subtract(state.position).multiply(1,0,1);
            double distance=delta.horizontalDistance(), speed=state.velocity.horizontalDistance();
            Vec3 coast=retention<1 ? state.position.add(state.velocity.multiply(retention/(1-retention),0,retention/(1-retention))) : state.position;
            if(coast.subtract(target).horizontalDistance()<.28) return settleRoad(state,retention);
            float bearing=distance>1e-7?yawOf(delta):state.yaw;
            double angle=Math.abs(Mth.wrapDegrees(bearing-state.yaw));
            if(speed>.025 && (angle>20 || stoppingDistance(speed,retention)+.12>=distance))
                return brakeRoad(state,retention);
            if(angle>8 || Math.abs(state.angularVelocity)>.5) return turnRoad(state,bearing,retention);
            if(distance<.18) return brakeRoad(state,retention);
            double wanted=Math.min(.30,Math.min(speedLimit,safeSpeed(Math.max(0,distance-.12),retention)));
            Vec3 desired=delta.scale(wanted/distance);
            Input best=Input.NONE;
            double bestScore=velocityError(step(state,best,retention).velocity,desired);
            for(Input input:new Input[]{new Input(false,false,false,true),new Input(false,false,true,false)}) {
                double score=velocityError(step(state,input,retention).velocity,desired);
                if(score<bestScore-1e-12) { best=input; bestScore=score; }
            }
            return best;
        }
        return cruiseRoad(state, target, retention, speedLimit);
    }

    /** Keep the strong forward paddle through ordinary counter-steering. S is
     * reserved for a genuine speed reduction, not a one-tick lateral-error win. */
    private static Input cruiseRoad(State state,Vec3 target,double retention,double speedLimit) {
        Vec3 delta=target.subtract(state.position).multiply(1,0,1);
        double distance=delta.horizontalDistance(),speed=state.velocity.horizontalDistance();
        if(distance<1e-6 || speedLimit<=.01) return brakeRoad(state,retention);
        float bearing=yawOf(delta);
        double heading=Math.abs(Mth.wrapDegrees(bearing-state.yaw));
        double drift=speed<.02?0:Math.abs(Mth.wrapDegrees(bearing-yawOf(state.velocity)));
        if(speedLimit>1.1 && heading<3 && drift<3 && Math.abs(state.angularVelocity)<.5) {
            // On a straight, tiny correction pulses must not cancel the strong
            // forward paddle. Shed a small excess by drag rather than S.
            double torque=Mth.wrapDegrees(bearing-state.yaw)*.12-state.angularVelocity*retention;
            if(speed<=speedLimit+.02) return new Input(torque<-.5,torque>.5,true,false);
            if(speed<speedLimit+.20) return new Input(torque<-.5,torque>.5,false,false);
        }
        return cruise(state,target,retention,speedLimit);
    }

    public static Input brakeRoad(State state,double retention) {
        if(!valid(state)||!validRetention(retention)) return Input.NONE;
        if(state.velocity.horizontalDistance()<.035) return settleRoad(state,retention);
        return turnRoad(state,yawOf(state.velocity),retention);
    }

    private static Input settleRoad(State state,double retention) {
        float turn=state.angularVelocity*(float)retention;
        boolean left=turn>.5,right=turn<-.5;
        Input free=new Input(left,right,false,false),back=new Input(left,right,false,true);
        return step(state,back,retention).velocity.horizontalDistanceSqr()<step(state,free,retention).velocity.horizontalDistanceSqr()-1e-12?back:free;
    }

    private static Input turnRoad(State state,float bearing,double retention) {
        double rate=Mth.clamp(Mth.wrapDegrees(bearing-state.yaw)*.12,-3,3);
        double torque=rate-state.angularVelocity*retention;
        boolean left=torque<-.5,right=torque>.5;
        Input free=new Input(left,right,false,false),back=new Input(left,right,false,true);
        return step(state,back,retention).velocity.horizontalDistanceSqr()<step(state,free,retention).velocity.horizontalDistanceSqr()-1e-12?back:free;
    }

    private static Input cruise(State state, Vec3 target, double retention, double speedLimit) {
        Vec3 delta = target.subtract(state.position).multiply(1, 0, 1);
        double distance = delta.horizontalDistance(), speed = state.velocity.horizontalDistance();
        double wanted = Math.min(speedLimit, 4);
        float bearing = distance > 1e-7 ? yawOf(delta) : state.yaw;
        float motion = speed > .004 ? yawOf(state.velocity) : state.yaw;
        boolean wrongDirection = speed > .12 && Math.abs(Mth.wrapDegrees(bearing - motion)) > 60;
        boolean braking = speed > wanted + .01 || wrongDirection;
        Vec3 desiredVelocity = wrongDirection || distance < 1e-7 ? Vec3.ZERO : delta.scale(wanted / distance);
        float desiredYaw = bearing;
        if (braking && speed > .004) {
            Vec3 excess = state.velocity.subtract(desiredVelocity);
            desiredYaw = motion + Mth.clamp(Mth.wrapDegrees(yawOf(excess) - motion), -45, 45);
        } else if (speed > .02) {
            // Friction feed-forward plus a damped velocity response. Trying to
            // correct the entire lateral error in one tick causes ice slalom.
            Vec3 correction = desiredVelocity.scale(1 - retention)
                    .add(desiredVelocity.subtract(state.velocity).scale(.12));
            if (correction.horizontalDistance() > .01) {
                // Unbounded acceleration bearings turn a nearly correct ice
                // velocity into repeated ninety-degree counter-steering.
                desiredYaw = bearing + Mth.clamp(Mth.wrapDegrees(yawOf(correction) - bearing), -35, 35);
            }
        }
        if (wanted == 0 && speed <= .004) desiredYaw = state.yaw;
        float error = Mth.wrapDegrees(desiredYaw - state.yaw);
        double desiredRate = Mth.clamp(error * .12, -3, 3);
        double torque = desiredRate - state.angularVelocity * retention;
        boolean left = torque < -.5, right = torque > .5;
        Input best = new Input(left, right, false, false);
        double score = velocityError(step(state, best, retention).velocity, desiredVelocity);
        Input reverse = new Input(left, right, false, true);
        double reverseScore = velocityError(step(state, reverse, retention).velocity, desiredVelocity);
        if (reverseScore < score - 1e-12) { best = reverse; score = reverseScore; }
        if (!braking && Math.abs(error) < 25 && Math.abs(state.angularVelocity) < 3.5) {
            Input forward = new Input(left, right, true, false);
            if (velocityError(step(state, forward, retention).velocity, desiredVelocity) < score - 1e-12) best = forward;
        }
        return best;
    }

    private static double safeSpeed(double distance, double retention) {
        double low = 0, high = 4;
        for (int i = 0; i < 20; i++) {
            double middle = (low + high) * .5;
            if (stoppingDistance(middle, retention) <= distance) low = middle;
            else high = middle;
        }
        return low;
    }

    public static float yawOf(Vec3 direction) {
        return (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
    }

    private static double velocityError(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private static boolean validRetention(double retention) {
        return Double.isFinite(retention) && retention >= 0 && retention <= 1;
    }

    private static boolean valid(State state) {
        return state != null && finite(state.position) && finite(state.velocity)
                && Float.isFinite(state.yaw) && Float.isFinite(state.angularVelocity);
    }

    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
