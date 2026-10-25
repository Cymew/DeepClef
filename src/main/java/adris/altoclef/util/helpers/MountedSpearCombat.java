package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.item.component.KineticWeapon;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

import java.util.HashSet;
import java.util.Set;

/**
 * Ordinary ridden horse controls, restricted to clear, supported ground. Attack
 * geometry and velocity thresholds remain vanilla; no horse attributes or motion
 * are changed. A horse's forward input already uses its normal ridden speed.
 */
public final class MountedSpearCombat {
    private enum State { APPROACH, STARTUP, CHARGE, BACK_AWAY, ESCAPE, HOLD }

    private static final Object CONTROL_OWNER = new Object();
    private static final double RUN_UP_DISTANCE = 2.4;
    private static LocalPlayer _owner;
    private static AbstractHorse _horse;
    private static Entity _target;
    private static Item _weapon;
    private static State _state = State.APPROACH;
    private static boolean _usingSpear;
    private static boolean _chargeBlocked;
    private static Vec3 _blockedHorsePosition;
    private static Vec3 _blockedTargetPosition;
    private static double _recoveryDistance;
    private static double _bestRetreatDistance;
    private static int _retreatProgressTick;
    private static int _stateTick;
    private static int _lastReleaseTick = -1;
    private static int _nextChargeTick;
    private static PositionGoal _approachGoal;
    private static PositionGoal _recoveryGoal;
    private static int _navigationFailures;
    private static int _navigationRetryTick;
    private static boolean _navigationStalled;
    private static Vec3 _navigationStallHorse;
    private static Vec3 _navigationStallTarget;
    private static Vec3 _navigationProgress;
    private static boolean _navigating;
    private static boolean _settling;
    private static int _stoppedTicks;
    private static int _alignedTicks;
    private static float _chargeHealth;
    private static int _chargeHurtTime;

    private MountedSpearCombat() { }

    /** Saddled, locally controlled horse family only; camels have different controls. */
    public static AbstractHorse getControlledHorse(LocalPlayer player) {
        return MountedNavigation.getControlledHorse(player);
    }

    public static boolean isActive() {
        return _owner != null && _owner == Minecraft.getInstance().player && _target != null
                && _target.isAlive() && !_target.isRemoved();
    }

    public static boolean isCharging() {
        return _usingSpear && isActive();
    }

    public static void reset(AltoClef mod) {
        releaseUse(mod);
        MountedNavigation.release(mod, CONTROL_OWNER);
        _owner = null;
        _horse = null;
        _target = null;
        _weapon = null;
        _state = State.APPROACH;
        _chargeBlocked = false;
        _blockedHorsePosition = null;
        _blockedTargetPosition = null;
        _recoveryDistance = 0;
        _alignedTicks = 0;
        _lastReleaseTick = -1;
        _nextChargeTick = 0;
        _approachGoal = null;
        _recoveryGoal = null;
        _navigationFailures = 0;
        _navigationRetryTick = 0;
        _navigationStalled = false;
        _navigationStallHorse = null;
        _navigationStallTarget = null;
        _navigationProgress = null;
        _navigating = false;
        _settling = false;
        _stoppedTicks = 0;
    }

    public static void tick(AltoClef mod, Entity target, AbstractHorse horse) {
        LocalPlayer player = mod.getPlayer();
        ItemStack hand = player == null ? ItemStack.EMPTY : player.getMainHandItem();
        if (player == null || target == null || !target.isAlive() || target.isRemoved()
                || getControlledHorse(player) != horse || horse == null || !SpearHelper.isSpear(hand)) {
            reset(mod);
            return;
        }
        if (_owner != player || _horse != horse || _target != target || _weapon != hand.getItem()) {
            reset(mod);
            _owner = player;
            _horse = horse;
            _target = target;
            _weapon = hand.getItem();
            _stateTick = player.tickCount;
            _settling = true;
            _stoppedTicks = 0;
            MountedNavigation.hold(mod, CONTROL_OWNER);
            return;
        }

        if (_chargeBlocked && (_blockedHorsePosition.distanceToSqr(horse.position()) > 2.25
                || _blockedTargetPosition.distanceToSqr(target.position()) > 4.0)) {
            _chargeBlocked = false;
        }
        if (_navigationStalled && (_navigationStallHorse.distanceToSqr(horse.position()) > 2.25
                || _navigationStallTarget.distanceToSqr(target.position()) > 4)) {
            _navigationStalled = false;
            _navigationFailures = 0;
            _navigationRetryTick = 0;
            _approachGoal = null;
            _recoveryGoal = null;
        }
        if (!horse.onGround() || horse.isInWater() || horse.isInLava()) {
            releaseUse(mod);
            move(mod, null);
            _state = _recoveryDistance > 0 ? State.ESCAPE : State.APPROACH;
            _alignedTicks = 0;
            return;
        }
        if (player.isUsingItem() && !_usingSpear) {
            move(mod, null);
            return;
        }

        AttackRange range = player.getAttackRangeWith(hand);
        KineticWeapon kinetic = hand.get(DataComponents.KINETIC_WEAPON);
        double distance = SpearHelper.distanceToTarget(player.getEyePosition(), target.getBoundingBox());
        SpearAim.Solution aim = SpearHelper.getAimSolution(player, target);
        Vec3 aimDirection = aim.point().subtract(player.getEyePosition()).normalize();
        double closing = Math.max(0, KineticWeapon.getMotion(player).subtract(KineticWeapon.getMotion(target))
                .dot(aimDirection));
        double dangerDistance = Math.max(3.1, range.effectiveMinRange(player) + 0.7 + closing * 0.1);
        double stagingDistance = stagingDistance(player, target, range, kinetic);
        double corridor = Math.max(1.0, horse.getDeltaMovement().horizontalDistance() * 5 + 0.65);

        if (_state == State.BACK_AWAY || _state == State.ESCAPE || _recoveryDistance > 0) {
            recover(mod, distance, stagingDistance, corridor);
            return;
        }

        if (_navigationStalled) {
            MountedNavigation.hold(mod, CONTROL_OWNER);
            if (aim.visible()) {
                SpearHelper.faceTarget(mod, target);
                if (SpearHelper.tryJab(mod, target)) beginRecovery(mod, distance, range);
            }
            return;
        }
        if (_navigating && aim.visible() && (aim.hittable() || distance <= stagingDistance + 0.6)) {
            _navigating = false;
            _settling = true;
            _stoppedTicks = 0;
        }
        if (_settling) {
            MountedNavigation.hold(mod, CONTROL_OWNER);
            _stoppedTicks = horse.getDeltaMovement().horizontalDistance() <= 0.025
                    && KineticWeapon.getMotion(player).horizontalDistance() <= 0.7 ? _stoppedTicks + 1 : 0;
            if (_stoppedTicks < 2) return;
            _settling = false;
        }
        if (_state != State.STARTUP && _state != State.CHARGE
                && (!aim.visible() || !aim.hittable() && distance > stagingDistance + 0.6)) {
            navigateToStaging(mod, stagingDistance, aim.visible());
            return;
        }

        // Navigation and aiming share the horse's yaw. Acquire the stationary
        // phase before facing a target; navigation never receives a combat look.
        if (_state != State.STARTUP && _state != State.CHARGE) MountedNavigation.hold(mod, CONTROL_OWNER);

        SpearHelper.faceTarget(mod, target);
        boolean aimed = player.getLookAngle().dot(aimDirection) >= Math.cos(Math.toRadians(6));
        _alignedTicks = aimed ? _alignedTicks + 1 : 0;

        if (_state == State.STARTUP || _state == State.CHARGE) {
            tickCharge(mod, hand, kinetic, distance, dangerDistance, corridor, aimed);
            return;
        }

        if (!visible(player, target)) {
            move(mod, null);
            return;
        }
        if (distance <= dangerDistance) {
            beginRecovery(mod, distance, range);
            return;
        }

        boolean chargeReady = kinetic != null && !_chargeBlocked && player.tickCount >= _nextChargeTick
                && player.tickCount > _lastReleaseTick;
        if (!aim.hittable() && chargeReady && distance >= range.effectiveMaxRange(player) + 1.3
                && _alignedTicks >= 2 && safeCorridor(mod, horizontalLook(player), Math.max(RUN_UP_DISTANCE, corridor))) {
            move(mod, null);
            // A wind-up begins at rest: fast horses must not coast through its delay.
            if (KineticWeapon.getMotion(player).horizontalDistance() > 0.7) return;
            mod.getController().useItem(player, InteractionHand.MAIN_HAND);
            _usingSpear = player.isUsingItem() && player.getUsedItemHand() == InteractionHand.MAIN_HAND;
            if (_usingSpear) {
                _chargeHealth = target instanceof LivingEntity living ? living.getHealth() : -1;
                _chargeHurtTime = target instanceof LivingEntity living ? living.hurtTime : 0;
                mod.getInputControls().hold(Input.CLICK_RIGHT);
                enter(State.STARTUP, player);
            } else {
                blockCharge(player);
            }
            return;
        }

        if (SpearHelper.tryJab(mod, target)) {
            beginRecovery(mod, distance, range);
            return;
        }
        if (aim.hittable()) {
            // Keep a usable head/side opening while aim synchronizes or the jab cools.
            move(mod, null);
            return;
        }
        if (chargeReady && distance < range.effectiveMaxRange(player) + 1.3
                && safeCorridor(mod, horizontalLook(player).scale(-1),
                        Math.max(corridor, stagingDistance + 0.6 - distance))) {
            // Too close for a useful run-up: create space instead of repeatedly using at zero speed.
            beginRecovery(mod, distance, range);
            return;
        }
        if (distance > range.effectiveMaxRange(player) - 0.3 && aimed
                && safeCorridor(mod, horizontalLook(player), corridor)) {
            move(mod, Input.MOVE_FORWARD);
        } else {
            move(mod, null);
        }
    }

    private static void tickCharge(AltoClef mod, ItemStack hand, KineticWeapon kinetic, double distance,
                                   double dangerDistance, double corridor, boolean aimed) {
        LocalPlayer player = mod.getPlayer();
        if (!_usingSpear || !player.isUsingItem() || player.getUsedItemHand() != InteractionHand.MAIN_HAND
                || kinetic == null || !SpearHelper.isSpear(player.getUseItem())) {
            releaseUse(mod);
            blockCharge(player);
            move(mod, null);
            enter(State.HOLD, player);
            return;
        }
        int used = player.getTicksUsingItem();
        if (used >= kinetic.delayTicks() && _target instanceof LivingEntity living
                && (living.getHealth() < _chargeHealth || living.hurtTime > _chargeHurtTime)) {
            beginRecovery(mod, distance, player.getAttackRangeWith(hand));
            return;
        }
        if (_target instanceof LivingEntity living) _chargeHurtTime = living.hurtTime;
        if (distance <= dangerDistance) {
            beginRecovery(mod, distance, player.getAttackRangeWith(hand));
            return;
        }
        if (!aimed || !visible(player, _target)
                || !safeCorridor(mod, horizontalLook(player), Math.max(1.5, corridor))) {
            releaseUse(mod);
            blockCharge(player);
            move(mod, null);
            enter(State.HOLD, player);
            return;
        }
        if (used < kinetic.delayTicks()) {
            move(mod, null);
            mod.getInputControls().hold(Input.CLICK_RIGHT);
            return;
        }
        enterIfDifferent(State.CHARGE, player);
        Vec3 look = player.getLookAngle();
        double attackerSpeed = KineticWeapon.getMotion(player).dot(look);
        double relativeSpeed = Math.max(0, KineticWeapon.getMotion(player)
                .subtract(KineticWeapon.getMotion(_target)).dot(look));
        boolean badContact = SpearHelper.isInSpearRange(player, _target) && kinetic.damageConditions().isPresent()
                && !kinetic.damageConditions().get().test(used - kinetic.delayTicks(), attackerSpeed, relativeSpeed, 1);
        if (badContact || used > Math.min(kinetic.computeDamageUseDuration(), kinetic.delayTicks() + 50)) {
            releaseUse(mod);
            blockCharge(player);
            move(mod, null);
            enter(State.HOLD, player);
            return;
        }
        mod.getInputControls().hold(Input.CLICK_RIGHT);
        move(mod, Input.MOVE_FORWARD);
    }

    private static void beginRecovery(AltoClef mod, double distance, AttackRange range) {
        LocalPlayer player = mod.getPlayer();
        releaseUse(mod);
        move(mod, null);
        _recoveryDistance = SpearHelper.jabRecoveryDistance(distance, range.effectiveMinRange(player));
        _recoveryGoal = null;
        _navigationFailures = 0;
        _navigationRetryTick = 0;
        _navigating = false;
        _settling = false;
        _nextChargeTick = player.tickCount + 12;
        enterBackAway(player, distance);
    }

    private static void enterBackAway(LocalPlayer player, double distance) {
        _bestRetreatDistance = distance;
        _retreatProgressTick = player.tickCount;
        enter(State.BACK_AWAY, player);
    }

    private static void recover(AltoClef mod, double distance, double stagingDistance, double corridor) {
        LocalPlayer player = mod.getPlayer();
        double desiredDistance = Math.max(_recoveryDistance,
                _chargeBlocked ? _recoveryDistance : stagingDistance + 0.6);
        if (distance >= desiredDistance) {
            MountedNavigation.hold(mod, CONTROL_OWNER);
            _recoveryDistance = 0;
            _recoveryGoal = null;
            _alignedTicks = 0;
            _navigationFailures = 0;
            _navigating = false;
            _settling = true;
            _stoppedTicks = 0;
            enter(State.APPROACH, player);
            return;
        }
        if (_navigationStalled) {
            // Do not jab again while a promised half-block retreat is blocked.
            MountedNavigation.hold(mod, CONTROL_OWNER);
            return;
        }
        if (_state == State.BACK_AWAY) {
            SpearHelper.faceTarget(mod, _target);
            if (distance > _bestRetreatDistance + 0.12) {
                _bestRetreatDistance = distance;
                _retreatProgressTick = player.tickCount;
            }
            if (player.tickCount - _retreatProgressTick <= 10 && player.tickCount - _stateTick <= 24
                    && safeCorridor(mod, horizontalLook(player).scale(-1), corridor)) {
                move(mod, Input.MOVE_BACK);
            } else {
                move(mod, null);
                _recoveryGoal = new PositionGoal(mod, false, desiredDistance);
                enter(State.ESCAPE, player);
            }
            return;
        }
        if (_recoveryGoal == null || _recoveryGoal.needsRefresh(_target, desiredDistance)) {
            _recoveryGoal = new PositionGoal(mod, false, desiredDistance);
        }
        if (distance > _bestRetreatDistance + 0.15) {
            _bestRetreatDistance = distance;
            _retreatProgressTick = player.tickCount;
        } else if (player.tickCount - _retreatProgressTick > 60) {
            // Movement around an obstruction is not progress if the promised
            // separation never grows. Bound that failure without dropping it.
            stallNavigation(mod);
            MountedNavigation.hold(mod, CONTROL_OWNER);
            return;
        }
        // Do not faceTarget/faceAway here. The mounted path owns steering until
        // the actual recovery distance is reached, including routes around cover.
        navigate(mod, _recoveryGoal);
    }

    private static void navigateToStaging(AltoClef mod, double stagingDistance, boolean visible) {
        if (_approachGoal == null || _approachGoal.needsRefresh(_target, stagingDistance)) {
            _approachGoal = new PositionGoal(mod, true, stagingDistance);
        }
        if (MountedNavigation.isGoalReached(mod, _approachGoal)) {
            double actualDistance = SpearHelper.distanceToTarget(mod.getPlayer().getEyePosition(), _target.getBoundingBox());
            if (visible && actualDistance <= stagingDistance + 0.6) {
                MountedNavigation.hold(mod, CONTROL_OWNER);
                _settling = true;
                _stoppedTicks = 0;
                _navigationFailures = 0;
                _navigating = false;
                return;
            }
            // Candidate-node visibility can differ from the rider's precise
            // eye position at a corner. Try another node instead of stopping at
            // the same invisible opening forever.
            _approachGoal.reject(_horse.blockPosition());
            MountedNavigation.release(mod, CONTROL_OWNER);
        }
        navigate(mod, _approachGoal);
    }

    private static void navigate(AltoClef mod, PositionGoal goal) {
        int tick = mod.getPlayer().tickCount;
        _navigating = true;
        if (goal.rejectedCount() >= 8) stallNavigation(mod);
        if (_navigationStalled || tick < _navigationRetryTick) {
            MountedNavigation.hold(mod, CONTROL_OWNER);
            return;
        }
        if (_navigationProgress == null || _navigationProgress.distanceToSqr(_horse.position()) > 1) {
            _navigationProgress = _horse.position();
            _navigationFailures = 0;
        }
        MountedNavigation.tick(mod, CONTROL_OWNER, goal);
        if (MountedNavigation.isBlocked(CONTROL_OWNER)) {
            _navigationFailures++;
            _navigationRetryTick = tick + 20;
            MountedNavigation.hold(mod, CONTROL_OWNER);
            if (_navigationFailures >= 3 || goal.rejectedCount() >= 8) {
                stallNavigation(mod);
            }
        }
    }

    private static void stallNavigation(AltoClef mod) {
        _navigationStalled = true;
        _navigationStallHorse = _horse.position();
        _navigationStallTarget = _target.position();
        blockCharge(mod.getPlayer());
    }

    /** Candidate horse feet, plus the rider's measured eye offset, define each goal. */
    private static final class PositionGoal implements Goal {
        private final AltoClef mod;
        private final Entity target;
        private final Vec3 targetPosition;
        private final AABB targetBox;
        private final Vec3 eyeOffset;
        private final boolean approach;
        private final double desiredDistance;
        private final Set<BlockPos> rejected = new HashSet<>();

        private PositionGoal(AltoClef mod, boolean approach, double desiredDistance) {
            this.mod = mod;
            this.target = _target;
            this.targetPosition = target.position();
            this.targetBox = target.getBoundingBox();
            this.eyeOffset = mod.getPlayer().getEyePosition().subtract(_horse.position());
            this.approach = approach;
            this.desiredDistance = desiredDistance;
        }

        private boolean needsRefresh(Entity entity, double distance) {
            return entity != target || targetPosition.distanceToSqr(entity.position()) > 1
                    || Math.abs(distance - desiredDistance) > 0.6
                    || eyeOffset.distanceToSqr(mod.getPlayer().getEyePosition().subtract(_horse.position())) > 0.16;
        }

        private void reject(BlockPos position) { rejected.add(position.immutable()); }
        private int rejectedCount() { return rejected.size(); }

        private Vec3 candidateEye(int x, int y, int z) {
            return new Vec3(x + 0.5, y, z + 0.5).add(eyeOffset);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            Vec3 eye = candidateEye(x, y, z);
            double distance = SpearHelper.distanceToTarget(eye, targetBox);
            if (!approach) {
                // A node is a whole block, whereas a jab promises a real measured
                // retreat. This buffer prevents rounding the rider's position
                // from accepting a node slightly short of the promised distance.
                return distance >= desiredDistance + 0.85;
            }
            return distance >= desiredDistance - 0.5 && distance <= desiredDistance + 0.25
                    && !rejected.contains(new BlockPos(x, y, z)) && hasOpening(eye);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            double distance = SpearHelper.distanceToTarget(candidateEye(x, y, z), targetBox);
            return (approach ? Math.abs(distance - desiredDistance)
                    : Math.max(0, desiredDistance + 0.85 - distance)) * 4;
        }

        private boolean hasOpening(Vec3 eye) {
            Vec3 canonical = SpearHelper.aimPoint(eye, targetBox);
            if (clear(eye, canonical)) return true;
            // Keep the same exposed-head/side principle as SpearAim, using a
            // small read-only set for path goal candidates. Actual attacks still
            // use the full vanilla ray and previous-tick aim checks.
            for (double y : new double[]{0.25, 0.75, 0.92}) {
                for (double x : new double[]{0.08, 0.5, 0.92}) {
                    for (double z : new double[]{0.08, 0.5, 0.92}) {
                        Vec3 point = new Vec3(targetBox.minX + targetBox.getXsize() * x,
                                targetBox.minY + targetBox.getYsize() * y,
                                targetBox.minZ + targetBox.getZsize() * z);
                        if (clear(eye, point)) return true;
                    }
                }
            }
            return false;
        }

        private boolean clear(Vec3 eye, Vec3 point) {
            return mod.level().clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, mod.getPlayer())).getType() == HitResult.Type.MISS;
        }
    }

    private static double stagingDistance(LocalPlayer player, Entity target, AttackRange range, KineticWeapon kinetic) {
        Vec3 direction = SpearHelper.getAimSolution(player, target).point().subtract(player.getEyePosition()).normalize();
        double incoming = Math.max(0, -KineticWeapon.getMotion(target).dot(direction));
        int delay = kinetic == null ? 0 : kinetic.delayTicks();
        return range.effectiveMaxRange(player) + RUN_UP_DISTANCE + incoming * (delay + 3) / 20.0;
    }

    private static void blockCharge(LocalPlayer player) {
        _chargeBlocked = true;
        _blockedHorsePosition = _horse.position();
        _blockedTargetPosition = _target.position();
        _nextChargeTick = player.tickCount + 30;
    }

    private static void releaseUse(AltoClef mod) {
        if (!_usingSpear) return;
        mod.getInputControls().release(Input.CLICK_RIGHT);
        LocalPlayer player = mod.getPlayer();
        if (player != null && player == _owner && player.isUsingItem()
                && player.getUsedItemHand() == InteractionHand.MAIN_HAND && SpearHelper.isSpear(player.getUseItem())) {
            mod.getController().releaseUsingItem(player);
        }
        _lastReleaseTick = player == null ? -1 : player.tickCount;
        _usingSpear = false;
    }

    private static void enter(State state, LocalPlayer player) {
        _state = state;
        _stateTick = player.tickCount;
    }

    private static void enterIfDifferent(State state, LocalPlayer player) {
        if (_state != state) enter(state, player);
    }

    private static void move(AltoClef mod, Input direction) {
        MountedNavigation.combatInput(mod, CONTROL_OWNER, direction);
    }

    private static Vec3 horizontalLook(LocalPlayer player) {
        return player.getLookAngle().multiply(1, 0, 1).normalize();
    }

    private static boolean visible(LocalPlayer player, Entity target) {
        return SpearHelper.getAimSolution(player, target).visible();
    }

    private static boolean safeCorridor(AltoClef mod, Vec3 direction, double distance) {
        return MountedNavigation.safeFlatCorridor(mod, direction, distance);
    }
}
