package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.entity.AbstractKillEntityTask;
import adris.altoclef.tasks.movement.GetToEntityTask;
import adris.altoclef.tasks.movement.RunAwayFromSpearTargetTask;
import adris.altoclef.tasksystem.Task;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.item.component.KineticWeapon;
import net.minecraft.world.item.component.PiercingWeapon;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Spear tactics for the local player. Vanilla components and collision queries decide
 * whether an attack can connect; the margins below are positioning heuristics only.
 * Baritone owns travel; local combat owns both movement and aim after travel ends.
 */
public final class SpearHelper {
    public static final double SPEAR_MIN_RANGE = 2.0;
    public static final double SPEAR_MAX_RANGE = 4.5;
    public static final double RETREAT_THRESHOLD = 3.1;
    public static final double DESIRED_HIT_DISTANCE = 4.1;
    private static final double RUN_UP_BUFFER = 1.4;
    private static final Input[] MOVEMENT = {
            Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT, Input.MOVE_RIGHT, Input.SPRINT
    };

    private static Entity _target;
    private static LocalPlayer _owner;
    private static Item _weapon;
    private static boolean _charging;
    private static boolean _localControl;
    private static float _chargeHealth;
    private static int _lastHurtTime;
    private static int _releaseTick = -1;
    private static int _retryChargeTick;
    private static int _lastChargeAttemptTick = -80;
    private static boolean _prepareCharge;
    private static int _prepareChargeSince;
    private static Task _travel;
    private static boolean _travellingAway;
    private static double _travelDistance;
    private static boolean _travelChargeSetup;
    private static int _travelSince;
    private static int _travelProgressTick;
    private static double _travelBestDistance;
    private static double _retreatDistance;
    private static int _retreatTick;
    private static double _retreatStartDistance;
    private static Vec3 _jabRetreatOrigin;
    private static Vec3 _jabRetreatAway;
    private static Entity _aimTarget;
    private static int _aimTick = -1;
    private static int _alignedSince = -1;
    private static Vec3 _lastAimDirection;
    private static Vec3 _previousAimDirection;
    private static int _previousAimTick = -1;
    private static float _renderFromYaw;
    private static float _renderFromPitch;
    private static float _renderToYaw;
    private static float _renderToPitch;
    private static boolean _renderAim;
    private static LivingEntity _solutionPlayer;
    private static Entity _solutionTarget;
    private static Item _solutionWeapon;
    private static int _solutionTick = -1;
    private static SpearAim.Solution _aimSolution;
    private static Vec3 _selectedAimAnchor;

    private SpearHelper() { }

    public static boolean isSpear(ItemStack stack) {
        return stack != null && !stack.isEmpty() && isSpear(stack.getItem());
    }

    public static boolean isSpear(Item item) {
        return item != null && item.builtInRegistryHolder().is(ItemTags.SPEARS);
    }

    /** Includes aiming, travel and recovery, so the aura cannot steal combat inputs. */
    public static boolean isCombatActive() {
        return _target != null && _owner == Minecraft.getInstance().player && _target.isAlive() && !_target.isRemoved();
    }

    public static boolean isCharging() {
        return _charging || MountedSpearCombat.isCharging();
    }

    public static Optional<Entity> getChargeTarget() {
        return isCharging() && isCombatActive() ? Optional.of(_target) : Optional.empty();
    }

    /** Retain a valid combat target through jab cooldowns and spacing recovery too. */
    public static Optional<Entity> getCombatTarget() {
        return isCombatActive() ? Optional.of(_target) : Optional.empty();
    }

    public static double getSpearAttackDamage(ItemStack stack) {
        return AbstractKillEntityTask.getWeaponAttackDamage(stack);
    }

    public static int getActivationDelayTicks(ItemStack spear) {
        KineticWeapon kinetic = spear == null ? null : spear.get(DataComponents.KINETIC_WEAPON);
        return kinetic == null ? 0 : kinetic.delayTicks();
    }

    /** Wind up outside reach, then accelerate. getMotion uses blocks/second. */
    public static double getChargeStartDistance(AltoClef mod, ItemStack spear) {
        AttackRange range = mod.getPlayer().getAttackRangeWith(spear);
        double incoming = _target == null ? 0 : Math.max(0,
                -KineticWeapon.getMotion(_target).dot(directionTo(mod.getPlayer(), _target)));
        return range.effectiveMaxRange(mod.getPlayer()) + RUN_UP_BUFFER
                + incoming * (getActivationDelayTicks(spear) + 2) / 20.0;
    }

    /** Actual vanilla ray: dead zone, movement extension, hitbox margin and solid blocks. */
    public static boolean isInSpearRange(Entity from, Entity to) {
        if (!(from instanceof LivingEntity attacker) || to == null || !to.isAlive()) return false;
        ItemStack stack = attacker.getMainHandItem();
        if (!isSpear(stack)) return false;
        return ProjectileUtil.getHitEntitiesAlong(attacker, attacker.getAttackRangeWith(stack),
                        entity -> entity == to && PiercingWeapon.canHitEntity(attacker, entity),
                        ClipContext.Block.COLLIDER)
                .right().map(hits -> hits.stream().anyMatch(hit -> hit.getEntity() == to)).orElse(false);
    }

    /** Fully cooled, aimed jab; also used by aura when no kill task owns the controls. */
    public static boolean tryJab(AltoClef mod, Entity target) {
        ensurePlayer(mod);
        LocalPlayer player = mod.getPlayer();
        ItemStack hand = player.getMainHandItem();
        if (!isSpear(hand) || target == null || !target.isAlive() || player.isUsingItem()) return false;
        if (distanceToTarget(player.getEyePosition(), target.getBoundingBox())
                < Math.max(RETREAT_THRESHOLD, player.getAttackRangeWith(hand).effectiveMinRange(player) + 1)) return false;
        faceTarget(mod, target);
        // STAB has no rotation. Let the preceding tick's aim reach the server first.
        AttackRange range = player.getAttackRangeWith(hand);
        if (_alignedSince < 0 || player.tickCount <= _alignedSince || player.tickCount <= _releaseTick
                || _previousAimTick != player.tickCount - 1 || _previousAimDirection == null
                || !canHitInDirection(player, target, range, _previousAimDirection)
                || player.cannotAttackWithItem(hand, 0) || !isInSpearRange(player, target)) return false;
        PiercingWeapon piercing = hand.get(DataComponents.PIERCING_WEAPON);
        if (piercing == null) return false;
        player.connection.send(new ServerboundMovePlayerPacket.Rot(player.getYRot(), player.getXRot(),
                player.onGround(), player.horizontalCollision));
        SwingAnimation animation = hand.getAttackAnimation();
        mod.getController().piercingAttack(animation == null ? SwingAnimation.DEFAULT : animation, piercing);
        return true;
    }

    /** Releases owned inputs and server-side item use on interruption/weapon/target changes. */
    public static void stopCharging(AltoClef mod) {
        ensurePlayer(mod);
        MountedSpearCombat.reset(mod);
        releaseCharge(mod);
        if (_localControl) releaseMovement(mod);
        _target = null;
        _weapon = null;
        _travel = null;
        _retreatDistance = 0;
        _jabRetreatOrigin = null;
        _jabRetreatAway = null;
        _localControl = false;
        _retryChargeTick = 0;
        _lastChargeAttemptTick = -80;
        _prepareCharge = false;
        _travelChargeSetup = false;
        _renderAim = false;
        _aimTarget = null;
        _aimTick = -1;
        _alignedSince = -1;
        _lastAimDirection = null;
        _previousAimDirection = null;
        _previousAimTick = -1;
        clearAimSolution();
    }

    public static Task tickSpearCombat(AltoClef mod, Entity target) {
        ensurePlayer(mod);
        LocalPlayer player = mod.getPlayer();
        ItemStack hand = player.getMainHandItem();
        if (!isSpear(hand) || target == null || !target.isAlive()) {
            stopCharging(mod);
            return null;
        }
        if (_target != target || _weapon != hand.getItem()) {
            stopCharging(mod);
            _target = target;
            _weapon = hand.getItem();
        }

        var horse = MountedSpearCombat.getControlledHorse(player);
        if (horse != null) {
            releaseCharge(mod);
            if (_travel != null) {
                _travel = null;
                _renderAim = false;
                return null;
            }
            _retreatDistance = 0;
            _prepareCharge = false;
            _localControl = false;
            MountedSpearCombat.tick(mod, target, horse);
            return null;
        }
        if (MountedSpearCombat.isActive()) {
            MountedSpearCombat.reset(mod);
            _localControl = false;
            _renderAim = false;
            return null;
        }
        if (player.isPassenger()) {
            // Unsupported/non-controlled vehicles must not enter walking Baritone paths.
            releaseCharge(mod);
            if (_localControl) releaseMovement(mod);
            _localControl = false;
            _renderAim = false;
            return null;
        }

        double distance = distanceToTarget(player.getEyePosition(), target.getBoundingBox());
        SpearAim.Solution aim = getAimSolution(player, target);
        boolean visible = aim.visible();
        boolean stable = player.onGround() || player.isInWater() || player.onClimbable();
        if (_prepareCharge && player.tickCount - _prepareChargeSince > 100) {
            _prepareCharge = false;
            _retryChargeTick = player.tickCount + 80;
        }
        // Keep the SAME travel task until it reaches its exit condition. Previously the
        // next combat decision interrupted retreat after just one tick.
        if (_travel != null) {
            if (_travelChargeSetup) {
                if (distance > _travelBestDistance + 0.15) {
                    _travelBestDistance = distance;
                    _travelProgressTick = player.tickCount;
                }
                if ((!_prepareCharge || player.tickCount - _travelSince > 60
                        || player.tickCount - _travelProgressTick > 16) && stable
                        && mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) {
                    _travel = null;
                    _travelChargeSetup = false;
                    _prepareCharge = false;
                    _retryChargeTick = player.tickCount + 80;
                    return null;
                }
            }
            boolean arrived = _travellingAway ? distance >= _travelDistance
                    : aim.hittable() || visible && distance <= _travelDistance;
            if (!arrived || !stable
                    || !mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) return _travel;
            _travel = null;
            _travelChargeSetup = false;
            // Task.tick stops the old child AFTER returning. Acquire local input next tick
            // so that child's onStop cannot cancel our newly acquired controls.
            return null;
        }
        if (!stable || !mod.getClientBaritone().getPathingBehavior().isSafeToCancel()) {
            releaseCharge(mod);
            if (_localControl) releaseMovement(mod);
            return null;
        }

        AttackRange range = player.getAttackRangeWith(hand);
        double min = range.effectiveMinRange(player);
        double max = range.effectiveMaxRange(player);
        double closing = Math.max(0, KineticWeapon.getMotion(player).subtract(KineticWeapon.getMotion(target))
                .dot(directionTo(player, target)));
        double retreatTrigger = Math.max(RETREAT_THRESHOLD, min + 1.0 + closing * 2 / 20.0);
        double recover = Math.max(min + 1.9, Math.min(DESIRED_HIT_DISTANCE, max - 0.3));

        // Swimming/climbing still permits aimed jabs and pathing. The direct sprint
        // controller is deliberately limited to supported, flat ground.
        if (!player.onGround()) {
            releaseCharge(mod);
            if (_retreatDistance > 0) {
                double retreatGoal = _retreatDistance;
                _retreatDistance = 0;
                _jabRetreatOrigin = null;
                _jabRetreatAway = null;
                return travel(mod, target, retreatGoal, true, visible);
            }
            if (distance <= retreatTrigger) return travel(mod, target, recover + 0.7, true, visible);
            if (!visible || !aim.hittable() && distance > max - 0.3) {
                return travel(mod, target, recover, false, visible);
            }
            acquireLocalControl(mod);
            releaseMovement(mod);
            if (tryJab(mod, target)) {
                return travel(mod, target, jabRecoveryDistance(distance, min), true, visible);
            }
            return null;
        }

        if (distance <= retreatTrigger && _retreatDistance == 0) {
            releaseCharge(mod);
            beginRetreat(player, distance, recover);
        }
        if (_retreatDistance > 0) {
            boolean steppedBack = _jabRetreatOrigin == null || player.position().subtract(_jabRetreatOrigin)
                    .dot(_jabRetreatAway) >= 0.5;
            if (distance >= _retreatDistance && steppedBack) {
                _retreatDistance = 0;
                _jabRetreatOrigin = null;
                _jabRetreatAway = null;
                releaseMovement(mod);
            } else {
                acquireLocalControl(mod);
                faceTarget(mod, target);
                Vec3 back = horizontalLook(player).scale(-1);
                // Face the target while backing away on clear ground. When this fails to
                // create space, let Baritone sustain a longer retreat around obstacles.
                if (distance > min && player.tickCount - _retreatTick < 16
                        && (player.tickCount - _retreatTick < 8 || distance > _retreatStartDistance + 0.15)
                        && canStep(mod, back, 1.0)) {
                    move(mod, Input.MOVE_BACK, false);
                    // Commit to creating space before another jab.
                    return null;
                }
                double retreatGoal = Math.max(recover + 0.7, _retreatDistance);
                if (!steppedBack) retreatGoal = Math.max(retreatGoal, distance + 0.5);
                _retreatDistance = 0;
                _jabRetreatOrigin = null;
                _jabRetreatAway = null;
                return travel(mod, target, retreatGoal, true, visible);
            }
        }

        if (!visible) return travel(mod, target, recover, false, false);
        double startDistance = getChargeStartDistance(mod, hand);
        if (_prepareCharge) {
            if (player.tickCount < _retryChargeTick) {
                _prepareCharge = false;
            } else if (distance < startDistance + 0.2) {
                Vec3 back = directionTo(player, target).multiply(-1, 0, -1).normalize();
                if (aim.hittable() && !canStep(mod, back, Math.max(1.9, startDistance + 0.2 - distance))) {
                    // A visible opening is already enough for a jab. Do not abandon it
                    // to chase a run-up that the surrounding terrain cannot support.
                    _prepareCharge = false;
                    _retryChargeTick = player.tickCount + 80;
                } else {
                    double incoming = Math.max(0, -KineticWeapon.getMotion(target).dot(directionTo(player, target)));
                    // A retreating path faces away. Reserve time to turn before winding up.
                    Task setup = travel(mod, target, startDistance + Math.max(1.5, incoming * 1.3), true, true);
                    _travelChargeSetup = true;
                    return setup;
                }
            }
        }
        if (!aim.hittable() && distance > startDistance + 1.5 && !_prepareCharge) {
            return travel(mod, target, startDistance + 1.5, false, true);
        }

        acquireLocalControl(mod);
        faceTarget(mod, target);
        if (_charging) {
            KineticWeapon kinetic = hand.get(DataComponents.KINETIC_WEAPON);
            if (!player.isUsingItem() || player.getUsedItemHand() != InteractionHand.MAIN_HAND || kinetic == null) {
                releaseCharge(mod);
                _retryChargeTick = player.tickCount + 20;
                return null;
            }
            int used = player.getTicksUsingItem();
            if (used >= kinetic.delayTicks() && target instanceof LivingEntity living
                    && (living.getHealth() < _chargeHealth || living.hurtTime > _lastHurtTime)) {
                releaseCharge(mod);
                _retryChargeTick = player.tickCount + 20;
                beginRetreat(player, distance, recover);
                return null;
            }
            if (target instanceof LivingEntity living) _lastHurtTime = living.hurtTime;
            if (used < kinetic.delayTicks()) {
                releaseMovement(mod);
                // An approaching target can invalidate the stationary wind-up.
                if (distance < max + 0.6) abortCharge(mod);
                return null;
            }
            boolean expired = used > Math.min(kinetic.computeDamageUseDuration(), kinetic.delayTicks() + 40);
            boolean lowSpeedContact = isInSpearRange(player, target) && kinetic.damageConditions().isPresent()
                    && !kinetic.damageConditions().get().test(used - kinetic.delayTicks(),
                    KineticWeapon.getMotion(player).dot(player.getLookAngle()),
                    Math.max(0, KineticWeapon.getMotion(player).subtract(KineticWeapon.getMotion(target))
                            .dot(player.getLookAngle())), 1);
            if (expired || lowSpeedContact || !aligned(player, target, 12)
                    || !canStep(mod, horizontalLook(player), 1.5)) {
                abortCharge(mod);
                return null;
            }
            mod.getInputControls().hold(Input.CLICK_RIGHT);
            move(mod, Input.MOVE_FORWARD, true);
            return null;
        }

        if (player.isUsingItem()) {
            releaseMovement(mod);
            return null;
        }
        KineticWeapon kinetic = hand.get(DataComponents.KINETIC_WEAPON);
        if (!aim.hittable() && kinetic != null && player.tickCount >= _retryChargeTick && player.tickCount > _releaseTick
                && distance >= startDistance && aligned(player, target, 3)
                && _alignedSince >= 0 && player.tickCount > _alignedSince
                && canStep(mod, horizontalLook(player), RUN_UP_BUFFER + 0.5)
                && player.getFoodData().getFoodLevel() > 6 && !player.isInWater()) {
            releaseMovement(mod);
            _lastChargeAttemptTick = player.tickCount;
            _prepareCharge = false;
            mod.getController().useItem(player, InteractionHand.MAIN_HAND);
            _charging = player.isUsingItem() && player.getUsedItemHand() == InteractionHand.MAIN_HAND;
            if (_charging) {
                _chargeHealth = target instanceof LivingEntity living ? living.getHealth() : -1;
                _lastHurtTime = target instanceof LivingEntity living ? living.hurtTime : 0;
                mod.getInputControls().hold(Input.CLICK_RIGHT);
            } else {
                _retryChargeTick = player.tickCount + 10;
            }
            return null;
        }

        // Close/slow/retreating targets: jab instead of repeating zero-damage charges.
        if (tryJab(mod, target)) {
            releaseMovement(mod);
            beginRetreat(player, distance, jabRecoveryDistance(distance, min));
            _jabRetreatOrigin = player.position();
            _jabRetreatAway = player.position().subtract(target.position()).multiply(1, 0, 1).normalize();
            _prepareCharge = kinetic != null && player.tickCount >= _retryChargeTick
                    && player.tickCount - _lastChargeAttemptTick >= 80
                    && player.getFoodData().getFoodLevel() > 6 && !player.isInWater()
                    && canStep(mod, horizontalLook(player).scale(-1), RUN_UP_BUFFER + 2)
                    && canStep(mod, horizontalLook(player), RUN_UP_BUFFER + 0.5);
            if (_prepareCharge) _prepareChargeSince = player.tickCount;
            return null;
        }
        if (aim.hittable()) {
            // Not firing can mean only cooldown or server-side aim synchronization.
            // Stay in this opening instead of turning a valid long jab into a path.
            releaseMovement(mod);
        } else if (distance > max - 0.3 && aligned(player, target, 8)) {
            if (canStep(mod, horizontalLook(player), 0.9)) {
                move(mod, Input.MOVE_FORWARD, false);
            } else {
                return travel(mod, target, recover, false, true);
            }
        } else {
            releaseMovement(mod);
        }
        return null;
    }

    private static Task travel(AltoClef mod, Entity target, double distance, boolean away, boolean visible) {
        releaseCharge(mod);
        if (_localControl) releaseMovement(mod);
        _localControl = false;
        _renderAim = false;
        _travellingAway = away;
        _travelDistance = distance;
        _travelChargeSetup = false;
        _travelSince = mod.getPlayer().tickCount;
        _travelProgressTick = _travelSince;
        _travelBestDistance = distanceToTarget(mod.getPlayer().getEyePosition(), target.getBoundingBox());
        _travel = away ? new RunAwayFromSpearTargetTask(target, distance)
                : new GetToEntityTask(target, visible ? Math.max(1, distance - 1) : 1);
        return _travel;
    }

    private static void beginRetreat(LocalPlayer player, double distance, double recover) {
        _retreatDistance = recover;
        _retreatTick = player.tickCount;
        _retreatStartDistance = distance;
        _jabRetreatOrigin = null;
        _jabRetreatAway = null;
    }

    private static void acquireLocalControl(AltoClef mod) {
        if (_localControl) return;
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        _localControl = true;
        releaseMovement(mod);
    }

    private static void abortCharge(AltoClef mod) {
        releaseCharge(mod);
        releaseMovement(mod);
        _retryChargeTick = mod.getPlayer().tickCount + 40;
    }

    private static void releaseCharge(AltoClef mod) {
        if (!_charging) return;
        mod.getInputControls().release(Input.CLICK_RIGHT);
        if (mod.getPlayer() != null && mod.getPlayer().isUsingItem()
                && isSpear(mod.getPlayer().getUseItem())) {
            mod.getController().releaseUsingItem(mod.getPlayer());
        }
        _releaseTick = mod.getPlayer() == null ? -1 : mod.getPlayer().tickCount;
        _charging = false;
    }

    private static void move(AltoClef mod, Input direction, boolean sprint) {
        for (Input input : MOVEMENT) {
            boolean down = input == direction || input == Input.SPRINT && sprint;
            if (down) mod.getInputControls().hold(input);
            else mod.getInputControls().release(input);
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(input, down);
        }
        mod.getPlayer().setSprinting(sprint);
    }

    private static void releaseMovement(AltoClef mod) {
        for (Input input : MOVEMENT) {
            mod.getInputControls().release(input);
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(input, false);
        }
        if (mod.getPlayer() != null) mod.getPlayer().setSprinting(false);
    }

    /** Use the same exposed point for turning, alignment and velocity projections. */
    private static Vec3 aimPoint(LivingEntity player, Entity target) {
        return getAimSolution(player, target).point();
    }

    public static Vec3 aimPoint(Vec3 eye, AABB box) {
        double inset = Math.min(0.15, box.getYsize() / 4);
        return new Vec3((box.minX + box.maxX) / 2,
                Mth.clamp(eye.y, box.minY + inset, box.maxY - inset),
                (box.minZ + box.maxZ) / 2);
    }

    public static double distanceToTarget(Vec3 eye, AABB box) {
        // Spacing must not increase just because aiming moves from the chest to a head.
        return eye.distanceTo(aimPoint(eye, box));
    }

    static SpearAim.Solution getAimSolution(LivingEntity player, Entity target) {
        ItemStack stack = player.getMainHandItem();
        if (_solutionPlayer == player && _solutionTarget == target && _solutionWeapon == stack.getItem()
                && _solutionTick == player.tickCount && _aimSolution != null) return _aimSolution;
        AABB box = target.getBoundingBox();
        Vec3 eye = player.getEyePosition();
        Vec3 preferred = _solutionPlayer == player && _solutionTarget == target && _selectedAimAnchor != null
                ? SpearAim.pointAt(box, _selectedAimAnchor) : null;
        AttackRange range = player.getAttackRangeWith(stack);
        _aimSolution = SpearAim.select(eye, player.getLookAngle(), box, preferred,
                point -> canHitInDirection(player, target, range, point.subtract(eye).normalize()),
                point -> traceTarget(player, target, point.subtract(eye).normalize(), 0,
                        eye.distanceTo(point) + 0.01, range.hitboxMargin()));
        _selectedAimAnchor = _aimSolution.visible() ? SpearAim.anchor(_aimSolution.point(), box) : null;
        _solutionPlayer = player;
        _solutionTarget = target;
        _solutionWeapon = stack.getItem();
        _solutionTick = player.tickCount;
        return _aimSolution;
    }

    private static boolean canHitInDirection(LivingEntity player, Entity target, AttackRange range, Vec3 direction) {
        double min = range.effectiveMinRange(player);
        double max = range.effectiveMaxRange(player) + Math.max(0, player.getKnownMovement().dot(direction));
        // Reject distant candidates cheaply; this is only a broad phase. Vanilla's
        // query below still checks the real body, margin and block collisions.
        return rayIntersectsTarget(player.getEyePosition(), direction, target.getBoundingBox(), min, max, range.hitboxMargin())
                && traceTarget(player, target, direction, min, max, range.hitboxMargin());
    }

    /** Vanilla's directional spear query, without rotating the player to test a candidate. */
    private static boolean traceTarget(LivingEntity player, Entity target, Vec3 direction,
                                       double min, double max, float margin) {
        if (max <= min || direction.lengthSqr() < 0.99 || !PiercingWeapon.canHitEntity(player, target)) return false;
        Vec3 eye = player.getEyePosition();
        Vec3 from = eye.add(direction.scale(min));
        Vec3 to = eye.add(direction.scale(max));
        HitResult block = player.level().clipIncludingBorder(new ClipContext(eye, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (block.getType() != HitResult.Type.MISS) {
            to = block.getLocation();
            if (eye.distanceToSqr(to) < eye.distanceToSqr(from)) return false;
        }
        AABB search = AABB.ofSize(from, margin, margin, margin).expandTowards(to.subtract(from)).inflate(1);
        return ProjectileUtil.getManyEntityHitResult(player.level(), player, from, to, search,
                entity -> entity == target && PiercingWeapon.canHitEntity(player, entity),
                margin, ClipContext.Block.COLLIDER, true, true).stream().anyMatch(hit -> hit.getEntity() == target);
    }

    private static void clearAimSolution() {
        _solutionPlayer = null;
        _solutionTarget = null;
        _solutionWeapon = null;
        _solutionTick = -1;
        _aimSolution = null;
        _selectedAimAnchor = null;
    }

    /** Broad-phase ray geometry; this alone does not authorize an attack through blocks. */
    public static boolean rayIntersectsTarget(Vec3 eye, Vec3 direction, AABB target,
                                               double min, double max, double margin) {
        Vec3 start = eye.add(direction.scale(min));
        return target.inflate(margin).contains(start)
                || target.inflate(margin).clip(start, eye.add(direction.scale(max))).isPresent();
    }

    static void faceTarget(AltoClef mod, Entity target) {
        facePoint(mod, target, aimPoint(mod.getPlayer(), target));
    }

    static void faceAwayFromTarget(AltoClef mod, Entity target) {
        LocalPlayer player = mod.getPlayer();
        Vec3 away = player.position().subtract(target.position()).multiply(1, 0, 1);
        if (away.lengthSqr() < 1e-6) away = horizontalLook(player).scale(-1);
        facePoint(mod, target, player.getEyePosition().add(away));
    }

    private static void facePoint(AltoClef mod, Entity target, Vec3 point) {
        LocalPlayer player = mod.getPlayer();
        if (_aimTarget == target && _aimTick == player.tickCount) return;
        if (_aimTarget != target || _aimTick != player.tickCount - 1) _alignedSince = -1;
        _previousAimDirection = _aimTarget == target ? _lastAimDirection : null;
        _previousAimTick = _aimTick;
        boolean consecutive = _aimTick == player.tickCount - 1 && _renderAim;
        _renderFromYaw = consecutive ? _renderToYaw : player.getYRot();
        _renderFromPitch = consecutive ? _renderToPitch : player.getXRot();
        _aimTarget = target;
        _aimTick = player.tickCount;
        Rotation desired = LookHelper.getLookRotation(mod, point);
        // Match LookHelper's immediate logical aim. The Camera mixin interpolates
        // rendered frames independently, so a slow tick-by-tick chase is unnecessary.
        float yaw = player.getYRot() + Mth.wrapDegrees(desired.getYaw() - player.getYRot());
        float pitch = desired.getPitch();
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(pitch);
        _lastAimDirection = player.getLookAngle();
        _renderToYaw = yaw;
        _renderToPitch = pitch;
        _renderAim = true;
        // No Baritone updateTarget here: its equal-pitch nudge would modify the
        // already-computed spear aim again. Local combat has cancelled its path.
        if (aligned(player, target, 3)) {
            if (_alignedSince < 0) _alignedSince = player.tickCount;
        } else {
            _alignedSince = -1;
        }
    }

    public static double jabRecoveryDistance(double distanceBeforeJab, double minRange) {
        return Math.max(Math.max(DESIRED_HIT_DISTANCE, minRange + 1.9), distanceBeforeJab + 0.5);
    }

    public static float interpolateYaw(float from, float to, float partial) {
        return from + Mth.wrapDegrees(to - from) * Mth.clamp(partial, 0, 1);
    }

    public static float getRenderYaw(Entity entity, float partial, float vanilla) {
        return canInterpolate(entity) ? interpolateYaw(_renderFromYaw, _renderToYaw, partial) : vanilla;
    }

    public static float getRenderPitch(Entity entity, float partial, float vanilla) {
        return canInterpolate(entity) ? Mth.lerp(Mth.clamp(partial, 0, 1), _renderFromPitch, _renderToPitch) : vanilla;
    }

    private static boolean canInterpolate(Entity entity) {
        return _renderAim && entity == _owner && _owner == Minecraft.getInstance().player
                && !Minecraft.getInstance().isPaused() && _aimTick >= _owner.tickCount - 1;
    }

    private static boolean aligned(LivingEntity player, Entity target, double degrees) {
        return player.getLookAngle().dot(directionTo(player, target)) >= Math.cos(Math.toRadians(degrees));
    }

    private static void ensurePlayer(AltoClef mod) {
        if (_owner == mod.getPlayer()) return;
        // tickCount restarts on respawn/world changes. Never carry a release cooldown
        // or an entity/input owner into another LocalPlayer instance.
        if (_charging) mod.getInputControls().release(Input.CLICK_RIGHT);
        if (_localControl) releaseMovement(mod);
        MountedSpearCombat.reset(mod);
        _owner = mod.getPlayer();
        _charging = false;
        _target = null;
        _weapon = null;
        _travel = null;
        _localControl = false;
        _retreatDistance = 0;
        _jabRetreatOrigin = null;
        _jabRetreatAway = null;
        _releaseTick = -1;
        _retryChargeTick = 0;
        _lastChargeAttemptTick = -80;
        _prepareCharge = false;
        _travelChargeSetup = false;
        _renderAim = false;
        _aimTarget = null;
        _aimTick = -1;
        _alignedSince = -1;
        _lastAimDirection = null;
        _previousAimDirection = null;
        _previousAimTick = -1;
        clearAimSolution();
    }

    private static Vec3 directionTo(LivingEntity player, Entity target) {
        return aimPoint(player, target).subtract(player.getEyePosition()).normalize();
    }

    private static Vec3 horizontalLook(LivingEntity player) {
        return player.getLookAngle().multiply(1, 0, 1).normalize();
    }

    /** Conservative flat-ground corridor check. Slopes, steps and hazards go to Baritone. */
    private static boolean canStep(AltoClef mod, Vec3 direction, double distance) {
        LocalPlayer player = mod.getPlayer();
        AABB body = player.getBoundingBox().deflate(0.01);
        for (double step = 0.25; step <= distance + 0.001; step += 0.25) {
            AABB next = body.move(direction.scale(step));
            if (!mod.level().noCollision(player, next)) return false;
            for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(next.minX, next.minY - 0.05, next.minZ),
                    BlockPos.containing(next.maxX, next.maxY, next.maxZ))) {
                if (MovementHelper.avoidWalkingInto(mod.level().getBlockState(pos))) return false;
            }
            // All four corners need support: a center-only test misses pit edges.
            for (double x : new double[]{next.minX, next.maxX}) {
                for (double z : new double[]{next.minZ, next.maxZ}) {
                    AABB support = new AABB(x - 0.01, next.minY - 0.15, z - 0.01, x + 0.01, next.minY, z + 0.01);
                    if (!mod.level().getBlockCollisions(player, support).iterator().hasNext()) return false;
                }
            }
        }
        return true;
    }
}
