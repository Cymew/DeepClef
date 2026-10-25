package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.control.MountedRiderInput;
import adris.altoclef.chains.MLGBucketFallChain;
import adris.altoclef.chains.WorldSurvivalChain;
import adris.altoclef.tasksystem.TaskChain;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** One rider input owner. Routes use the horse's feet and the joint rider/mount volume. */
public final class MountedNavigation {
    private static final Input[] INPUTS = {Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT,
            Input.MOVE_RIGHT, Input.JUMP, Input.SNEAK, Input.SPRINT};
    private static final int SEARCH_BUDGET = 384;
    private static final int SEARCH_RADIUS = 16;
    private static final Object IDLE = new Object();
    private static final MountedJumpFeedback JUMP_FEEDBACK = new MountedJumpFeedback();
    private static UUID _feedbackPlayerId, _feedbackHorseId;
    private static LocalPlayer _player;
    private static AbstractHorse _horse;
    private static MountedRiderInput _riderInput;
    private static Object _owner;
    private static Goal _goal;
    private static List<Vec3> _route = List.of();
    private static Map<Integer, MountedPathPlanner.JumpEdge> _routeJumps = Map.of();
    private static MountedPathPlanner.JumpEdge _jump;
    private static int _jumpPhase, _jumpTick, _jumpReleaseTick, _jumpSteady, _jumpRetryTick;
    private static boolean _jumpHeld, _sawJumpAir;
    private static boolean _dismountHeld;
    private static int _cursor, _lastPlanTick = -100, _requestTick = -1, _frameTick = -1;
    private static Vec3 _bestPosition;
    private static Vec3 _routeStart;
    private static int _progressTick;
    private static boolean _blocked, _lateInputs, _render;
    private static boolean _safetyFrame;
    private static boolean _navigating;
    private static boolean _precisionBraking;
    private static int _precisionPulseTick;
    private static String _status = "Waiting for mounted control.";
    private static int _lastDiagnosticTick = -100;
    private static Input _direction;
    private static float _yaw, _pitch, _fromYaw, _fromPitch, _toYaw, _toPitch;

    private MountedNavigation() { }

    public static AbstractHorse getControlledHorse(LocalPlayer player) {
        if (player == null) return null;
        Entity vehicle = player.getControlledVehicle();
        if (vehicle instanceof AbstractHorse horse && !(horse instanceof Camel)
                && horse.isAlive() && !horse.isRemoved() && horse.isSaddled()
                && horse.getControllingPassenger() == player && horse.isLocalInstanceAuthoritative()) return horse;
        return null;
    }

    public static boolean isActive() {
        return _owner != null && _owner != IDLE && _player == Minecraft.getInstance().player
                && getControlledHorse(_player) == _horse && _requestTick >= _player.tickCount - 1;
    }

    public static boolean isBlocked(Object owner) { return _owner == owner && _blocked; }
    public static boolean isJumping() { return isActive() && _jump != null && _jumpPhase == 2; }

    public static MountedTravelProgress.Phase travelPhase(Object owner) {
        if (_owner != owner || _horse == null) return MountedTravelProgress.Phase.PAUSED;
        if (_jump != null) return _jumpPhase == 2 ? MountedTravelProgress.Phase.AIRBORNE
                : _jumpPhase == 1 ? MountedTravelProgress.Phase.CHARGING : MountedTravelProgress.Phase.TURNING;
        if (!_horse.onGround()) return MountedTravelProgress.Phase.AIRBORNE;
        if (_precisionBraking || _dismountHeld) return MountedTravelProgress.Phase.SETTLING;
        if (_blocked) return MountedTravelProgress.Phase.BLOCKED;
        return _direction == null ? MountedTravelProgress.Phase.TURNING : MountedTravelProgress.Phase.TRAVELLING;
    }

    public static double remainingDistance(Object owner) {
        if (_owner != owner || _horse == null || _goal == null) return Double.NaN;
        Goal resolved = _goal instanceof MountedBlockGoal block ? block.resolved() : _goal;
        Vec3 point = _horse.position();
        if (resolved instanceof GoalBlock block) {
            double dx = Math.max(0, Math.abs(point.x - block.x - 0.5) - 0.5);
            double dz = Math.max(0, Math.abs(point.z - block.z - 0.5) - 0.5);
            double dy = Math.max(0, Math.max(block.y - point.y, point.y - block.y - 1));
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return resolved.heuristic(BlockPos.containing(point)) / 4;
    }

    public static void retryRoute(Object owner) {
        if (_owner != owner || _jump != null && _jumpPhase == 2) return;
        cancelJump();
        _route = List.of();
        _routeJumps = Map.of();
        _lastPlanTick = -100;
        _direction = null;
    }

    /** Request the native server-side dismount through a normally sampled Shift input. */
    public static void requestDismount(AltoClef mod, Object owner) {
        if (!acquire(mod, owner)) return;
        if (_jump != null && _jumpPhase == 2) { continueJump(mod); return; }
        cancelJump();
        _direction = null;
        _precisionBraking = false;
        if (!_horse.onGround() || _horse.getDeltaMovement().horizontalDistance() > 0.005
                || Math.abs(_player.zza) > 0.001) {
            _status = "Stopping mount before continuing on foot.";
            return;
        }
        if (MountedDismountHelper.safeLocation(mod, _horse).isEmpty()) {
            _status = "Looking for safe footing to dismount.";
            // Changing the stopped mount's facing changes the native side
            // candidates; the server still chooses and executes the dismount.
            if (_player.tickCount % 20 == 0) {
                _player.setYRot(_player.getYRot() + 90);
                _player.setYHeadRot(_player.getYRot());
            }
            return;
        }
        _dismountHeld = true;
        _status = "Dismounting to continue the goal on foot.";
    }

    public static String getDebugState(Object owner) {
        return _owner == owner ? _status : "Waiting for mounted control.";
    }

    public static Entity getRenderHorse() { return isActive() && !_safetyFrame ? _horse : null; }
    public static Goal getRenderGoal() {
        if (getRenderHorse() == null) return null;
        return _goal instanceof MountedBlockGoal block ? block.resolved() : _goal;
    }
    private record RenderRoute(List<Vec3> points, Set<Integer> jumps) { }
    private static RenderRoute renderRoute() {
        if (getRenderHorse() == null || !_navigating) return new RenderRoute(List.of(), Set.of());
        List<Vec3> points = new ArrayList<>();
        Set<Integer> jumps = new HashSet<>();
        points.add(_horse.position());
        for (int index = _cursor; index < _route.size(); index++) {
            var edge = index == _cursor && _jump != null ? _jump : _routeJumps.get(index);
            if (edge != null) {
                int start = edge == _jump && _jumpPhase == 2
                        ? Math.min(edge.arc().size() - 1, Math.max(1, _player.tickCount - _jumpReleaseTick)) : 1;
                for (int sample = start; sample < edge.arc().size(); sample++) {
                    jumps.add(points.size() - 1);
                    points.add(edge.arc().get(sample));
                }
            } else points.add(_route.get(index));
        }
        return new RenderRoute(List.copyOf(points), Set.copyOf(jumps));
    }
    public static List<Vec3> getRenderPath() { return renderRoute().points(); }
    public static Set<Integer> getRenderJumpIndices() { return renderRoute().jumps(); }

    public static BlockPos findStandNear(AltoClef mod, BlockPos requested, int range) {
        AbstractHorse horse = getControlledHorse(mod.getPlayer());
        if (horse == null || !mod.level().hasChunkAt(requested)) return null;
        Terrain terrain = new Terrain(mod, horse);
        Vec3 best = MountedTerrainSupport.nearestSurface(new Vec3(requested.getX() + 0.5,
                requested.getY(), requested.getZ() + 0.5), range, 2, terrain::surface);
        return best == null ? null : BlockPos.containing(best);
    }

    public static boolean shouldYieldToSafety(TaskChain selected, float priority) {
        return priority >= 90 && (selected instanceof MLGBucketFallChain || selected instanceof WorldSurvivalChain);
    }

    /** Must run after old-chain cleanup and before the new winner writes its keys. */
    public static void prepareFrame(AltoClef mod, TaskChain selected, float priority) {
        if (mod == null) return;
        boolean safety = shouldYieldToSafety(selected, priority);
        if (safety && !_safetyFrame) releaseAll(mod);
        _safetyFrame = safety;
    }

    public static boolean isSafetyFrame() { return _safetyFrame; }

    private static boolean acquire(AltoClef mod, Object owner) {
        if (_safetyFrame) return false;
        LocalPlayer player = mod.getPlayer();
        AbstractHorse horse = getControlledHorse(player);
        if (horse == null) return false;
        if (_player == player && _horse == horse && _owner != owner && _jump != null && _jumpPhase == 2) {
            continueJump(mod);
            return false;
        }
        if (_player != player || _horse != horse || _owner != owner) {
            cancelJump();
            if (_player != player || _horse != horse) {
                _precisionBraking = false;
                if (!player.getUUID().equals(_feedbackPlayerId) || !horse.getUUID().equals(_feedbackHorseId)) {
                    JUMP_FEEDBACK.clear();
                    _feedbackPlayerId = player.getUUID();
                    _feedbackHorseId = horse.getUUID();
                }
                _render = false;
                _frameTick = -1;
                _jumpRetryTick = 0;
                if (_player != null && _player.input == _riderInput) {
                    _player.input = new KeyboardInput(Minecraft.getInstance().options);
                }
                _riderInput = new MountedRiderInput();
            }
            clearMovement(mod);
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            _owner = owner;
            _player = player;
            _horse = horse;
            _route = List.of();
            _routeJumps = Map.of();
            _goal = null;
            _lastPlanTick = -100;
            _bestPosition = horse.position();
            _progressTick = player.tickCount;
            _blocked = false;
            _lastDiagnosticTick = player.tickCount - 100;
        }
        _requestTick = player.tickCount;
        _lateInputs = true;
        _direction = null;
        _jumpHeld = false;
        _dismountHeld = false;
        _navigating = false;
        mod.getClientBaritone().getPathingBehavior().requestPause();
        return true;
    }

    public static boolean tick(AltoClef mod, Object owner, Goal goal) {
        if (!acquire(mod, owner)) return false;
        _navigating = true;
        if (goal == null) { _blocked = true; _status = "Mounted goal is missing."; return true; }
        if (_goal != goal) {
            // Most task goals are stable objects. Dynamic goals must preserve
            // identity and update their target position through their own fields.
            _goal = goal;
            _route = List.of();
            _routeJumps = Map.of();
            _lastPlanTick = -100;
            _bestPosition = _horse.position();
            _progressTick = _player.tickCount;
        }
        if (_jump != null) { tickJump(mod, new Terrain(mod, _horse)); return true; }
        if (goal.isInGoal(BlockPos.containing(_horse.position()))) {
            _route = List.of();
            _blocked = false;
            _status = "Stopping at mounted goal.";
            return true;
        }
        if (_horse.isInWater() || _horse.isInLava()) {
            _blocked = true;
            _status = "Mounted navigation paused in liquid.";
            return true;
        }
        if (!_horse.onGround()) { _status = "Waiting for mount to land."; return true; }
        if (_precisionBraking) {
            // Horse movement consumes the preceding rider sample. Commit only
            // one W sample, then allow that sample and its momentum to expire.
            if (_player.tickCount - _precisionPulseTick < 3
                    || _horse.getDeltaMovement().horizontalDistance() > 0.005 || Math.abs(_player.zza) > 0.001) {
                _status = "Settling after mounted launch alignment.";
                return true;
            }
            _precisionBraking = false;
            _route = List.of();
            _routeJumps = Map.of();
            _lastPlanTick = _player.tickCount - 25;
        }
        Vec3 position = _horse.position();
        if (_bestPosition == null || position.distanceToSqr(_bestPosition) > 0.16) {
            _bestPosition = position;
            _progressTick = _player.tickCount;
        }
        // Route refresh already runs every 25 ticks. Keep the motion clock for
        // diagnosis without invalidating a newly refreshed route every frame.
        Terrain terrain = new Terrain(mod, _horse);
        while (_cursor < _route.size() && !_routeJumps.containsKey(_cursor)
                && passed(position, _route.get(_cursor), _cursor == 0 ? _routeStart : _route.get(_cursor - 1))) _cursor++;
        if ((_route.isEmpty() || _cursor >= _route.size() || _player.tickCount - _lastPlanTick >= 25)
                && _player.tickCount - _lastPlanTick >= (_blocked ? 20 : 5)) {
            MountedPathPlanner.Route result = MountedPathPlanner.plan(position, goal, terrain, SEARCH_BUDGET, SEARCH_RADIUS);
            if (!result.reachedGoal() && result.points().size() < 4 && _player.tickCount >= _jumpRetryTick) {
                var withJumps = MountedPathPlanner.plan(position, goal, terrain, SEARCH_BUDGET, SEARCH_RADIUS, true);
                // The launch-alignment pass can also find a purely walking
                // sequence on a narrow slope, without needing a jump at all.
                if (!withJumps.points().isEmpty()) result = withJumps;
                if (result.points().isEmpty()) {
                    result = MountedPathPlanner.plan(position, goal, new Terrain(mod, _horse, 64),
                            1024, SEARCH_RADIUS, true);
                }
            }
            _route = result.points();
            _routeJumps = result.jumps();
            _routeStart = position;
            _cursor = 0;
            _lastPlanTick = _player.tickCount;
            _blocked = _route.isEmpty();
        }
        if (_cursor >= _route.size()) {
            _status = _blocked ? "No safe mounted route; waiting." : "Planning the next mounted route segment.";
            return true;
        }
        if (_routeJumps.containsKey(_cursor)) {
            _jump = _routeJumps.get(_cursor);
            _jumpPhase = 0;
            _jumpTick = _player.tickCount;
            _jumpSteady = 0;
            tickJump(mod, terrain);
            return true;
        }
        Vec3 waypoint = _route.get(_cursor);
        // Look ahead only through an actually traversable joint-volume corridor.
        // A private cursor advances with the horse, independent of paused Baritone.
        int selected = _cursor;
        for (int index = _cursor + 1; index < Math.min(_route.size(), _cursor + 4); index++) {
            if (_routeJumps.containsKey(index)) break;
            Vec3 candidate = _route.get(index);
            Vec3 first = waypoint.subtract(position).multiply(1, 0, 1).normalize();
            Vec3 next = candidate.subtract(waypoint).multiply(1, 0, 1).normalize();
            if (first.dot(next) < 0.85 || !terrain.corridor(position, candidate)) break;
            selected = index;
        }
        waypoint = _route.get(selected);
        if (!terrain.corridor(position, waypoint)) {
            _route = List.of();
            _blocked = true;
            _status = "Mounted route clearance blocked; waiting.";
            return true;
        }
        Vec3 delta = waypoint.subtract(position);
        float desired = (float)Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90;
        float yaw = MountedPathPlanner.steeringYaw(_player.getYRot(), desired);
        _player.setYRot(yaw);
        _player.setYHeadRot(yaw);
        _player.setXRot(0);
        double speed = _horse.getDeltaMovement().horizontalDistance();
        boolean cornerOrEnd = selected == _route.size() - 1;
        if (!cornerOrEnd) {
            Vec3 next = _route.get(selected + 1).subtract(waypoint).multiply(1, 0, 1).normalize();
            cornerOrEnd = delta.multiply(1, 0, 1).normalize().dot(next) < 0.85;
        }
        double lookAhead = Math.max(0.6, MountedPathPlanner.stoppingDistance(speed));
        boolean precisionLeg = waypoint.subtract(selected == 0 ? _routeStart : _route.get(selected - 1))
                .horizontalDistance() < 0.5;
        if (precisionLeg) {
            double pulseDistance = terrain.singlePulseDistance();
            if (!Double.isFinite(pulseDistance)) {
                _blocked = true;
                _status = "Mounted launch alignment has no bounded stopping distance.";
                return true;
            }
            lookAhead = Math.max(lookAhead, pulseDistance);
        }
        Vec3 forward = Vec3.directionFromRotation(0, yaw);
        boolean safeAhead = precisionLeg ? terrain.precisionCorridor(position, forward, lookAhead)
                : terrain.corridor(position, position.add(forward.scale(lookAhead)));
        if (!safeAhead && precisionLeg && waypoint.y < position.y - 0.05
                && speed < 0.005 && Math.abs(_player.zza) < 0.001) {
            safeAhead = terrain.descendingPulse(position, forward);
        }
        float yawError = Mth.wrapDegrees(desired - yaw);
        if (!safeAhead) {
            _blocked = true;
            _status = "Mounted forward clearance blocked; waiting.";
        } else if (Math.abs(yawError) > (precisionLeg ? 3 : 22)) {
            _blocked = false;
            _status = "Turning mount toward route.";
        } else if (precisionLeg) {
            if (speed < 0.005 && Math.abs(_player.zza) < 0.001 && delta.horizontalDistance() > 0.08) {
                _direction = Input.MOVE_FORWARD;
                _precisionBraking = true;
                _precisionPulseTick = _player.tickCount;
                _status = "Adjusting mounted launch position.";
            } else {
                _status = "Settling before mounted launch alignment.";
            }
            _blocked = false;
        } else if (!MountedPathPlanner.canAdvance(yawError, speed, delta.horizontalDistance(), cornerOrEnd)) {
            _blocked = false;
            _status = "Slowing mount before waypoint.";
        } else {
            _blocked = false;
            _direction = Input.MOVE_FORWARD;
            _status = "Riding to goal.";
        }
        return true;
    }

    private static boolean passed(Vec3 position, Vec3 waypoint, Vec3 previous) {
        if (Math.abs(position.y - waypoint.y) > 0.65) return false;
        double leg = previous == null ? 1 : waypoint.subtract(previous).horizontalDistance();
        double tolerance = Math.min(0.45, Math.max(0.06, leg * 0.35));
        if (position.subtract(waypoint).horizontalDistance() < tolerance) return true;
        if (previous == null) return false;
        Vec3 segment = waypoint.subtract(previous).multiply(1, 0, 1);
        return position.subtract(waypoint).multiply(1, 0, 1).dot(segment) >= 0
                && position.subtract(waypoint).horizontalDistance() < Math.min(1.1, Math.max(0.3, leg));
    }

    public static boolean isGoalReached(AltoClef mod, Goal goal) {
        AbstractHorse horse = getControlledHorse(mod.getPlayer());
        return horse != null && goal != null && horse.onGround()
                && horse.getDeltaMovement().horizontalDistance() < 0.08
                && goal.isInGoal(BlockPos.containing(horse.position()));
    }

    public static void hold(AltoClef mod, Object owner) {
        if (acquire(mod, owner)) {
            if (_jump != null && _jumpPhase == 2) { continueJump(mod); return; }
            cancelJump();
            _direction = null;
            _status = "Holding mounted position.";
        }
    }

    public static void combatInput(AltoClef mod, Object owner, Input direction) {
        if (acquire(mod, owner)) {
            if (_jump != null && _jumpPhase == 2) { continueJump(mod); return; }
            cancelJump();
            _direction = direction == Input.MOVE_FORWARD || direction == Input.MOVE_BACK ? direction : null;
        }
    }

    public static void release(AltoClef mod, Object owner) {
        if (_owner != owner) return;
        // Ordinary task changes defer until a committed safe jump lands. Stop,
        // world changes and emergency safety frames still call releaseAll.
        if (_jump != null && _jumpPhase == 2 && mod.getTaskRunner().isActive()) return;
        releaseAll(mod);
    }

    public static void releaseAll(AltoClef mod) {
        cancelJump();
        if (!mod.getTaskRunner().isActive() || mod.getPlayer() == null || getControlledHorse(mod.getPlayer()) != _horse) {
            JUMP_FEEDBACK.clear();
            _feedbackPlayerId = null;
            _feedbackHorseId = null;
        }
        if (_player != null) clearMovement(mod);
        if (_player != null && _player.input == _riderInput) {
            _player.input = new KeyboardInput(Minecraft.getInstance().options);
        }
        _riderInput = null;
        _owner = null;
        _horse = null;
        _player = null;
        _goal = null;
        _route = List.of();
        _routeJumps = Map.of();
        _direction = null;
        _dismountHeld = false;
        _lateInputs = false;
        _render = false;
        _safetyFrame = false;
        _navigating = false;
        _precisionBraking = false;
        _jumpRetryTick = 0;
    }

    /** Called after the winning task and background helpers, before Baritone's IN tick. */
    public static void endTick(AltoClef mod) {
        if (_safetyFrame) return;
        if (!mod.getTaskRunner().isActive()) { releaseAll(mod); return; }
        AbstractHorse horse = getControlledHorse(mod.getPlayer());
        if (horse == null) { releaseAll(mod); return; }
        if (_jump != null && _jumpPhase == 2 && _requestTick != mod.getPlayer().tickCount) continueJump(mod);
        if (_player != mod.getPlayer() || _horse != horse || _requestTick != mod.getPlayer().tickCount) {
            acquire(mod, IDLE);
            _direction = null;
        }
        if (_jump != null && _jumpPhase == 2) {
            Vec3 direction = _jump.target().subtract(_jump.launch());
            _player.setYRot((float)Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90);
            _player.setYHeadRot(_player.getYRot());
            _player.setXRot(0);
        }
        // Snapshot the winning action's final view. hold() intentionally never
        // revives a previous navigation yaw over a bow/spear/block-interaction aim.
        boolean consecutive = _render && _frameTick == _player.tickCount - 1;
        _fromYaw = consecutive ? _toYaw : _player.getYRot();
        _fromPitch = consecutive ? _toPitch : _player.getXRot();
        _yaw = _toYaw = _player.getYRot();
        _pitch = _toPitch = _player.getXRot();
        _frameTick = _player.tickCount;
        _render = true;
        applyInputs(mod);
        if (_navigating && _player.tickCount - _progressTick > 40
                && _player.tickCount - _lastDiagnosticTick >= 100) {
            _lastDiagnosticTick = _player.tickCount;
            Debug.logInternal("Mounted navigation stalled: " + _status + " position=" + horse.position()
                    + " ground=" + horse.onGround() + " route=" + _cursor + "/" + _route.size()
                    + " direction=" + _direction + " forcedForward="
                    + mod.getClientBaritone().getInputOverrideHandler().isInputForcedDown(Input.MOVE_FORWARD)
                    + " input=" + _player.input.getClass().getSimpleName() + " move="
                    + _player.input.getMoveVector().x + "," + _player.input.getMoveVector().y
                    + " sampledJump=" + _player.input.keyPresses.jump()
                    + " rider=" + _player.xxa + "," + _player.zza);
        }
    }

    /** Late Baritone listener: its pause/executor must run before our actual input commit. */
    public static void applyInputs(AltoClef mod) {
        if (_safetyFrame || !_lateInputs || _player != mod.getPlayer() || getControlledHorse(_player) != _horse) return;
        for (Input input : INPUTS) {
            boolean down = input == _direction || input == Input.JUMP && _jumpHeld
                    || input == Input.SNEAK && _dismountHeld;
            if (down) mod.getInputControls().hold(input); else mod.getInputControls().release(input);
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(input, down);
        }
        if (_riderInput == null) _riderInput = new MountedRiderInput();
        _riderInput.setIntent(_direction, _jumpHeld);
        _riderInput.setDismount(_dismountHeld);
        // InputOverrideHandler may just have replaced player.input after Jump
        // was released. Restore the same sampled object, without ticking it:
        // LocalPlayer needs its previous true bit to detect true -> false.
        _player.input = _riderInput;
    }

    /** Also runs after LookBehavior's PRE and pig() updates. */
    public static void applyLook(AltoClef mod) {
        if (!_render || mod.getPlayer() == null || _frameTick < mod.getPlayer().tickCount - 1
                || _frameTick > mod.getPlayer().tickCount || getControlledHorse(mod.getPlayer()) != _horse) return;
        _player.setYRot(_yaw);
        _player.setYHeadRot(_yaw);
        _player.setXRot(_pitch);
    }

    private static void clearMovement(AltoClef mod) {
        for (Input input : INPUTS) {
            mod.getInputControls().release(input);
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(input, false);
        }
    }

    private static void cancelJump() {
        // A charged jump is launched on key release. Clear its previous sampled
        // bit when interrupted so a new owner does not accidentally launch it.
        if (_jump != null && _jumpPhase == 1 && _player != null) {
            if (_riderInput != null) _riderInput.cancelCharge();
            var keys = _player.input.keyPresses;
            _player.input.keyPresses = new net.minecraft.world.entity.player.Input(keys.forward(), keys.backward(),
                    keys.left(), keys.right(), false, keys.shift(), keys.sprint());
        }
        _jump = null;
        _jumpHeld = false;
        _jumpSteady = 0;
        _sawJumpAir = false;
    }

    private static void abortJump(AltoClef mod, String reason) {
        if (_jump != null) JUMP_FEEDBACK.recordFailure(_jump, _player.tickCount);
        cancelJump();
        _direction = null;
        _route = List.of();
        _routeJumps = Map.of();
        _jumpRetryTick = _player.tickCount + 40;
        _blocked = true;
        _status = reason;
    }

    public static void continueJump(AltoClef mod) {
        if (_safetyFrame || _jump == null || _jumpPhase != 2 || _player != mod.getPlayer()
                || getControlledHorse(_player) != _horse) return;
        _requestTick = _player.tickCount;
        _lateInputs = true;
        _navigating = true;
        _direction = null;
        _jumpHeld = false;
        mod.getClientBaritone().getPathingBehavior().requestPause();
        tickJump(mod, new Terrain(mod, _horse));
    }

    private static void tickJump(AltoClef mod, Terrain terrain) {
        int tick = _player.tickCount;
        Vec3 direction = _jump.target().subtract(_jump.launch()).multiply(1, 0, 1).normalize();
        float desired = (float)Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90;
        float yaw = _jumpPhase == 0 ? MountedPathPlanner.steeringYaw(_player.getYRot(), desired) : desired;
        _player.setYRot(yaw);
        _player.setYHeadRot(yaw);
        _player.setXRot(0);
        _blocked = false;
        if (_horse.isInWater() || _horse.isInLava() || tick - _jumpTick > 100) {
            abortJump(mod, "Mounted jump interrupted; replanning.");
            return;
        }
        if (_jumpPhase == 0) {
            _status = "Stopping and aligning for mounted jump.";
            if (!_horse.onGround() || _horse.getDeltaMovement().horizontalDistance() > 0.005
                    || Math.abs(Mth.wrapDegrees(desired - yaw)) > 3) { _jumpSteady = 0; return; }
            if (++_jumpSteady < 2) return;
            var checked = terrain.jumpAlong(_horse.position(), direction, _jump.forwardStartTick());
            if (checked.isEmpty()) { abortJump(mod, "Mounted jump is no longer clear; replanning."); return; }
            _jump = checked.get();
            _jumpPhase = 1;
            _jumpTick = tick;
        }
        if (_jumpPhase == 1) {
            _status = "Charging mounted jump.";
            _jumpHeld = true;
            int held = tick - _jumpTick;
            if (!(held >= 9 && Mth.floor(_player.getJumpRidingScale() * 100) >= 90)) {
                if (held >= 12) abortJump(mod, "Mounted jump charge was not ready; replanning.");
                return;
            }
            var checked = terrain.jumpAlong(_horse.position(), direction, _jump.forwardStartTick());
            if (checked.isEmpty()) { abortJump(mod, "Mounted jump landing changed; replanning."); return; }
            _jump = checked.get();
            _jumpHeld = false;
            _jumpPhase = 2;
            _jumpReleaseTick = tick;
            Debug.logInternal("Mounted jump release: bar=" + _player.getJumpRidingScale()
                    + " previousJump=" + _player.input.keyPresses.jump()
                    + " forwardDelay=" + _jump.forwardStartTick()
                    + " launch=" + _jump.launch() + " target=" + _jump.target());
        }
        _status = "Jumping along mounted route.";
        _direction = tick - _jumpReleaseTick >= _jump.forwardStartTick() ? Input.MOVE_FORWARD : null;
        if (!_sawJumpAir && !_horse.onGround() && (_horse.getDeltaMovement().y > 0.05
                || _horse.getY() > _jump.launch().y + 0.05)) {
            _sawJumpAir = true;
            Debug.logInternal("Mounted jump takeoff confirmed: rise=" + (_horse.getY() - _jump.launch().y));
        }
        if (_sawJumpAir && _horse.onGround()) {
            if (!MountedJumpFeedback.landedAt(_jump, _horse.position())) {
                Debug.logInternal("Mounted jump missed landing: launch=" + _jump.launch()
                        + " target=" + _jump.target() + " actual=" + _horse.position());
                abortJump(mod, "Mounted jump made insufficient progress; replanning.");
                return;
            }
            cancelJump();
            _direction = null;
            _route = List.of();
            _routeJumps = Map.of();
            _lastPlanTick = tick - 25;
            _bestPosition = _horse.position();
            _progressTick = tick;
            _status = "Mounted jump landed; refreshing route.";
        } else if (!_sawJumpAir && tick - _jumpReleaseTick > 6) {
            abortJump(mod, "Mounted jump did not start; replanning.");
        }
    }

    public static float getRenderYaw(Entity entity, float partial, float fallback) {
        return canRender(entity) ? SpearHelper.interpolateYaw(_fromYaw, _toYaw, partial) : fallback;
    }

    public static float getRenderPitch(Entity entity, float partial, float fallback) {
        return canRender(entity) ? Mth.lerp(Mth.clamp(partial, 0, 1), _fromPitch, _toPitch) : fallback;
    }

    private static boolean canRender(Entity entity) {
        return _render && entity == _player && entity == Minecraft.getInstance().player
                && getControlledHorse(_player) == _horse && !Minecraft.getInstance().isPaused()
                && _frameTick >= _player.tickCount - 1;
    }

    public static boolean safeFlatCorridor(AltoClef mod, Vec3 direction, double length) {
        AbstractHorse horse = getControlledHorse(mod.getPlayer());
        if (horse == null || direction.horizontalDistanceSqr() < 0.5 || !horse.onGround()) return false;
        Terrain terrain = new Terrain(mod, horse);
        Vec3 start = horse.position();
        Vec3 horizontal = direction.multiply(1, 0, 1).normalize();
        for (double step = 0; step <= length + 0.001; step += 0.25) {
            Vec3 point = start.add(horizontal.scale(step));
            Vec3 surface = terrain.surface(point.x, point.z, start.y);
            if (surface == null || Math.abs(surface.y - start.y) > 0.12) return false;
        }
        return terrain.corridor(start, start.add(horizontal.scale(length)));
    }

    private static final class Terrain implements MountedPathPlanner.Terrain, MountedTerrainSupport.Query, MountedCoastPlanner.World {
        private final AltoClef mod;
        private final AbstractHorse horse;
        private final Vec3 origin;
        private final AABB body, rider;
        private final double stepHeight;
        private final double maxDrop;
        private final int jumpSourceLimit;
        private final Map<String, List<MountedPathPlanner.JumpEdge>> jumpCache = new HashMap<>();
        private final Map<String, Vec3> surfaces = new HashMap<>();
        private final Set<String> missing = new HashSet<>();

        Terrain(AltoClef mod, AbstractHorse horse) {
            this(mod, horse, 24);
        }

        Terrain(AltoClef mod, AbstractHorse horse, int jumpSourceLimit) {
            this.mod = mod;
            this.horse = horse;
            this.jumpSourceLimit = jumpSourceLimit;
            origin = horse.position();
            body = horse.getBoundingBox().deflate(0.02);
            rider = mod.getPlayer().getBoundingBox().deflate(0.02);
            stepHeight = Math.min(1.0, horse.maxUpStep());
            maxDrop = Math.max(0, Math.min(3, horse.getAttributeValue(Attributes.SAFE_FALL_DISTANCE) - 0.25));
        }

        @Override public Vec3 stand(int x, int z, double nearY) {
            String key = x + ":" + z + ":" + Math.round(nearY * 16);
            if (missing.contains(key)) return null;
            Vec3 cached = surfaces.get(key);
            if (cached != null) return cached;
            Vec3 point = surface(x + 0.5, z + 0.5, nearY);
            if (point == null) missing.add(key); else surfaces.put(key, point);
            return point;
        }

        Vec3 surface(double x, double z, double nearY) {
            return MountedTerrainSupport.findSurface(x, z, nearY, body, stepHeight, maxDrop, this);
        }

        @Override public Vec3 standAt(double x, double z, double nearY) {
            String key = "contact:" + x + ":" + z + ":" + Math.round(nearY * 16);
            if (missing.contains(key)) return null;
            Vec3 cached = surfaces.get(key);
            if (cached != null) return cached;
            Vec3 point = MountedTerrainSupport.findContactSurface(x, z, nearY, body, stepHeight, maxDrop, this);
            if (point == null) missing.add(key); else surfaces.put(key, point);
            return point;
        }

        @Override public Vec3 landingSurface(Vec3 point, double nearY) {
            return MountedTerrainSupport.findContactSurface(point.x, point.z, nearY, body, 0, maxDrop, this);
        }

        @Override public Vec3 coastLandingSurface(Vec3 point, double nearY) {
            // Even a thin rear overlap keeps vanilla grounded for this tick.
            // Query actual full foot contact, including narrow collision shapes
            // between the path planner's support samples.
            double halfX = horse.getBoundingBox().getXsize() * 0.5;
            double halfZ = horse.getBoundingBox().getZsize() * 0.5;
            AABB probe = new AABB(point.x - halfX, nearY - maxDrop - 0.001, point.z - halfZ,
                    point.x + halfX, nearY + 0.001, point.z + halfZ);
            for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(probe.minX, probe.minY, probe.minZ),
                    BlockPos.containing(probe.maxX, probe.maxY, probe.maxZ))) {
                if (!mod.level().hasChunkAt(pos) || mod.level().isOutsideBuildHeight(pos)) return null;
            }
            double top = -Double.MAX_VALUE;
            for (var shape : mod.level().getBlockCollisions(horse, probe)) for (AABB box : shape.toAabbs()) {
                if (box.maxX > probe.minX && box.minX < probe.maxX && box.maxZ > probe.minZ && box.minZ < probe.maxZ
                        && box.maxY <= nearY + 1e-6 && box.maxY >= nearY - maxDrop) top = Math.max(top, box.maxY);
            }
            // Do not turn an obstructed contact into a missing floor: the
            // predictor must clip Y first, then reject its real swept movement.
            return top == -Double.MAX_VALUE ? null : new Vec3(point.x, top, point.z);
        }

        double singlePulseDistance() {
            var below = mod.level().getBlockState(horse.getBlockPosBelowThatAffectsMyMovement()).getBlock();
            float friction = MountedJumpPhysics.modifiedFriction(below.getFriction(),
                    (float)horse.getAttributeValue(Attributes.FRICTION_MODIFIER));
            float airDrag = MountedJumpPhysics.modifiedFriction(0.91f,
                    (float)horse.getAttributeValue(Attributes.AIR_DRAG_MODIFIER));
            double drag = (double)(friction * airDrag);
            double acceleration = horse.getAttributeValue(Attributes.MOVEMENT_SPEED);
            if (friction > 0.6) acceleration *= 0.21600002f / (friction * friction * friction);
            return drag >= 1 ? Double.POSITIVE_INFINITY
                    : (0.98 * acceleration + horse.getDeltaMovement().horizontalDistance()) / (1 - drag) + 0.1;
        }

        boolean precisionCorridor(Vec3 from, Vec3 direction, double minimumDistance) {
            var below = mod.level().getBlockState(horse.getBlockPosBelowThatAffectsMyMovement()).getBlock();
            float modifier = (float)horse.getAttributeValue(Attributes.FRICTION_MODIFIER);
            float friction = MountedJumpPhysics.modifiedFriction(below.getFriction(), modifier);
            float airDrag = MountedJumpPhysics.modifiedFriction(0.91f,
                    (float)horse.getAttributeValue(Attributes.AIR_DRAG_MODIFIER));
            double acceleration = horse.getAttributeValue(Attributes.MOVEMENT_SPEED);
            if (friction > 0.6) acceleration *= 0.21600002f / (friction * friction * friction);
            double impulse = 0.98 * acceleration + horse.getDeltaMovement().horizontalDistance();
            double drag = (double)(friction * airDrag);
            // Crossing smoother ground or briefly leaving a descending step
            // extends coasting. Expand the checked corridor until its bound is stable.
            for (int pass = 0; pass < 5; pass++) {
                double distance = Math.max(minimumDistance, impulse / (1 - drag) + 0.1);
                if (drag >= 1 || !Double.isFinite(distance) || distance > SEARCH_RADIUS) return false;
                double checkedDrag = drag;
                Vec3 previous = from;
                int count = Math.max(1, (int)Math.ceil(distance / 0.125));
                for (int sample = 1; sample <= count; sample++) {
                    Vec3 point = from.add(direction.scale(distance * sample / count));
                    Vec3 next = standAt(point.x, point.z, previous.y);
                    if (next == null || !traversable(previous, next)) return false;
                    if (next.y < previous.y - 0.001) checkedDrag = Math.max(checkedDrag, airDrag);
                    double halfX = horse.getBoundingBox().getXsize() * 0.5;
                    double halfZ = horse.getBoundingBox().getZsize() * 0.5;
                    for (double dx : new double[]{-halfX, 0, halfX}) for (double dz : new double[]{-halfZ, 0, halfZ}) {
                        var block = mod.level().getBlockState(BlockPos.containing(next.x + dx, next.y - 0.05, next.z + dz)).getBlock();
                        float groundFriction = MountedJumpPhysics.modifiedFriction(block.getFriction(), modifier);
                        checkedDrag = Math.max(checkedDrag, (double)(groundFriction * airDrag));
                    }
                    previous = next;
                }
                if (checkedDrag <= drag + 1e-9) return true;
                drag = checkedDrag;
            }
            return false;
        }

        @Override public double groundDrag(Vec3 point) {
            float modifier = (float)horse.getAttributeValue(Attributes.FRICTION_MODIFIER);
            float airDrag = MountedJumpPhysics.modifiedFriction(0.91f,
                    (float)horse.getAttributeValue(Attributes.AIR_DRAG_MODIFIER));
            float friction = 0;
            double halfX = horse.getBoundingBox().getXsize() * 0.5;
            double halfZ = horse.getBoundingBox().getZsize() * 0.5;
            for (double dx : new double[]{-halfX, 0, halfX}) for (double dz : new double[]{-halfZ, 0, halfZ}) {
                var block = mod.level().getBlockState(BlockPos.containing(point.x + dx, point.y - 0.05, point.z + dz)).getBlock();
                friction = Math.max(friction, MountedJumpPhysics.modifiedFriction(block.getFriction(), modifier));
            }
            return (double)(friction * airDrag);
        }

        boolean descendingPulse(Vec3 from, Vec3 direction) {
            if (horse.getDeltaMovement().y > 0
                    || MountedJumpPhysics.environmentRejection(horse.isInWater() || horse.isInLava(),
                    horse.hasEffect(MobEffects.LEVITATION), horse.hasEffect(MobEffects.SLOW_FALLING), horse.isNoGravity()).isPresent()) return false;
            var below = mod.level().getBlockState(horse.getBlockPosBelowThatAffectsMyMovement()).getBlock();
            float friction = MountedJumpPhysics.modifiedFriction(below.getFriction(),
                    (float)horse.getAttributeValue(Attributes.FRICTION_MODIFIER));
            float modifier = (float)horse.getAttributeValue(Attributes.AIR_DRAG_MODIFIER);
            double acceleration = horse.getAttributeValue(Attributes.MOVEMENT_SPEED);
            if (friction > 0.6) acceleration *= 0.21600002f / (friction * friction * friction);
            return MountedCoastPlanner.predict(from, horse.getDeltaMovement(), direction, acceleration, horse.getGravity(),
                    MountedJumpPhysics.modifiedFriction(0.91f, modifier), MountedJumpPhysics.modifiedFriction(0.98f, modifier),
                    maxDrop, 60, this).isPresent();
        }

        @Override public Vec3 stand(Vec3 point, double nearY) { return surface(point.x, point.z, nearY); }

        @Override public double supportTop(double x, double z, double nearY) {
            BlockPos pos = BlockPos.containing(x, nearY, z);
            if (!mod.level().hasChunkAt(pos) || mod.level().isOutsideBuildHeight(pos)) return Double.NaN;
            AABB probe = new AABB(x - 0.005, nearY - maxDrop - 0.05, z - 0.005,
                    x + 0.005, nearY + stepHeight + 0.01, z + 0.005);
            double top = -Double.MAX_VALUE;
            for (var shape : mod.level().getBlockCollisions(horse, probe)) {
                for (AABB box : shape.toAabbs()) {
                    if (box.maxY <= nearY + stepHeight + 0.01 && box.maxY >= nearY - maxDrop - 0.01
                            && x >= box.minX - 0.005 && x <= box.maxX + 0.005
                            && z >= box.minZ - 0.005 && z <= box.maxZ + 0.005) top = Math.max(top, box.maxY);
                }
            }
            return top == -Double.MAX_VALUE ? Double.NaN : top;
        }

        @Override public boolean clearAt(Vec3 point) {
            Vec3 offset = point.subtract(origin);
            AABB mountBox = body.move(offset), riderBox = rider.move(offset);
            if (!mod.level().noCollision(horse, mountBox) || !mod.level().noCollision(mod.getPlayer(), riderBox)) return false;
            AABB occupied = mountBox.minmax(riderBox);
            for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(occupied.minX, point.y - 0.05, occupied.minZ),
                    BlockPos.containing(occupied.maxX, occupied.maxY, occupied.maxZ))) {
                if (!mod.level().hasChunkAt(pos) || MovementHelper.avoidWalkingInto(mod.level().getBlockState(pos))) return false;
            }
            return true;
        }

        @Override public boolean traversable(Vec3 from, Vec3 to) {
            if (to.y - from.y > stepHeight + 0.01 || from.y - to.y > maxDrop + 0.01) return false;
            double height = Math.max(from.y, to.y);
            Vec3 start = new Vec3(from.x, height, from.z), end = new Vec3(to.x, height, to.z);
            return clear(from, start) && clear(start, end) && clear(end, to);
        }

        @Override public boolean clear(Vec3 from, Vec3 to) {
            Vec3 offset = from.subtract(origin), delta = to.subtract(from);
            AABB mount = body.move(offset).expandTowards(delta), passenger = rider.move(offset).expandTowards(delta);
            if (!mod.level().noCollision(horse, mount) || !mod.level().noCollision(mod.getPlayer(), passenger)) return false;
            AABB occupied = mount.minmax(passenger);
            for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(occupied.minX, occupied.minY - 0.03, occupied.minZ),
                    BlockPos.containing(occupied.maxX, occupied.maxY, occupied.maxZ))) {
                if (!mod.level().hasChunkAt(pos) || MovementHelper.avoidWalkingInto(mod.level().getBlockState(pos))) return false;
            }
            return true;
        }

        Optional<MountedPathPlanner.JumpEdge> jumpAlong(Vec3 from, Vec3 direction, int delay) {
            if (!horse.canJump() || horse.getJumpCooldown() != 0
                    || MountedJumpPhysics.environmentRejection(horse.isInWater() || horse.isInLava(),
                    horse.hasEffect(MobEffects.LEVITATION), horse.hasEffect(MobEffects.SLOW_FALLING), horse.isNoGravity()).isPresent()) return Optional.empty();
            BlockPos support = from.distanceToSqr(origin) < 1e-8 ? horse.getBlockPosBelowThatAffectsMyMovement()
                    : BlockPos.containing(from.x, from.y - (double)0.500001f, from.z);
            var below = mod.level().getBlockState(support).getBlock();
            var here = mod.level().getBlockState(BlockPos.containing(from)).getBlock();
            float jumpFactor = here.getJumpFactor() == 1f ? below.getJumpFactor() : here.getJumpFactor();
            float modifier = (float)horse.getAttributeValue(Attributes.AIR_DRAG_MODIFIER);
            var parameters = new MountedJumpPhysics.Parameters(MountedJumpPhysics.launchPower(
                    horse.getAttributeValue(Attributes.JUMP_STRENGTH), 1, jumpFactor, horse.getJumpBoostPower()),
                    horse.getAttributeValue(Attributes.MOVEMENT_SPEED), horse.getGravity(),
                    MountedJumpPhysics.modifiedFriction(below.getFriction(), (float)horse.getAttributeValue(Attributes.FRICTION_MODIFIER)),
                    MountedJumpPhysics.modifiedFriction(0.91f, modifier), MountedJumpPhysics.modifiedFriction(0.98f, modifier),
                    0, 0.98, 1, delay, 60);
            return MountedJumpPlanner.find(from, direction, parameters,
                    horse.getAttributeValue(Attributes.SAFE_FALL_DISTANCE) - 0.05, this);
        }

        @Override public List<MountedPathPlanner.JumpEdge> jumps(Vec3 from) {
            String key = from.toString();
            if (jumpCache.containsKey(key)) return jumpCache.get(key);
            if (jumpCache.size() >= jumpSourceLimit || from.subtract(origin).horizontalDistance() > 3) return List.of();
            List<MountedPathPlanner.JumpEdge> edges = new ArrayList<>();
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                MountedPathPlanner.JumpEdge best = null;
                for (int delay : new int[]{0, 2, 4, 6}) {
                    var edge = jumpAlong(from, new Vec3(dx, 0, dz).normalize(), delay);
                    if (edge.isPresent() && JUMP_FEEDBACK.allows(edge.get(), mod.getPlayer().tickCount)
                            && (best == null || _goal.heuristic(BlockPos.containing(edge.get().target()))
                            < _goal.heuristic(BlockPos.containing(best.target())))) best = edge.get();
                }
                if (best != null) edges.add(best);
            }
            List<MountedPathPlanner.JumpEdge> snapshot = List.copyOf(edges);
            jumpCache.put(key, snapshot);
            return snapshot;
        }

        boolean corridor(Vec3 from, Vec3 to) {
            double distance = from.subtract(to).horizontalDistance();
            int count = Math.max(1, (int)Math.ceil(distance / 0.25));
            Vec3 previous = from;
            for (int index = 1; index <= count; index++) {
                double fraction = (double)index / count;
                Vec3 point = from.lerp(to, fraction);
                Vec3 next = standAt(point.x, point.z, previous.y);
                if (next == null || !traversable(previous, next)) return false;
                previous = next;
            }
            return true;
        }
    }
}
