package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/** One bow owner for task shooting and mounted combat; navigation and aim never run together. */
public final class BowCombat {
    private static final Object NAVIGATION_OWNER = new Object();
    private static final double BRAKE_SPEED = 0.035;
    private static final int MAX_DRAW_WAIT_TICKS = 70;
    private static LocalPlayer _player;
    private static Entity _target;
    private static boolean _active;
    private static boolean _drawing;
    private static boolean _ownsRightClick;
    private static boolean _walkingPath;
    private static int _navigationMode;
    private static int _steadyTicks;
    private static int _lastSteadyTick = -1;
    private static int _drawStartedTick;
    private static int _nextDrawTick;
    private static int _aimIndex;
    private static int _aimTicks;
    private static Goal _approachGoal;
    private static Goal _retreatGoal;

    private BowCombat() { }

    public static boolean canUse(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        return player != null && player.isAlive() && mod.getItemStorage().hasItem(Items.BOW)
                && !player.getProjectile(Items.BOW.getDefaultInstance()).isEmpty();
    }

    public static boolean canTarget(Entity target) {
        if (target == null || !target.isAlive() || target.isRemoved()) return false;
        if (target instanceof Enderman && !target.isPassenger() || target instanceof Breeze) return false;
        return !(target instanceof WitherBoss wither) || !wither.isPowered();
    }

    public static boolean isActive() {
        return _active && _player == Minecraft.getInstance().player;
    }

    public static boolean isDrawing() {
        return isActive() && _drawing && ownsBowUse(_player);
    }

    /** Returns true only on the tick that an aimed, fully drawn arrow was released. */
    public static boolean tick(AltoClef mod, Entity target) {
        if (MountedNavigation.isJumping()) { MountedNavigation.continueJump(mod); return false; }
        LocalPlayer player = mod.getPlayer();
        if (!canUse(mod) || !canTarget(target) || mod.getFoodChain().isTryingToEat()
                || !mod.getMLGBucketChain().doneMLG() || mod.getMLGBucketChain().isFallingOhNo(mod)
                || mod.getMLGBucketChain().isChorusFruiting() || player.isInLava()) {
            reset(mod);
            return false;
        }
        if (_player != player || _target != target) {
            reset(mod);
            _player = player;
            _target = target;
            _approachGoal = new ShootingPositionGoal(mod, target, false);
            _retreatGoal = new ShootingPositionGoal(mod, target, true);
        }
        AbstractHorse horse = MountedNavigation.getControlledHorse(player);
        if (player.isPassenger() && horse == null) {
            reset(mod);
            return false;
        }
        if (player.isUsingItem() && !ownsBowUse(player)) {
            reset(mod);
            return false;
        }
        if (_drawing && !ownsBowUse(player)) {
            cancelOwnUse(mod);
            _nextDrawTick = player.tickCount + 5;
        }
        _active = true;
        Vec3 origin = arrowOrigin(player);
        Vec3 targetMotion = target.getKnownSpeed();
        // Teleport discontinuities cannot be extrapolated into an arrow intercept.
        if (targetMotion.lengthSqr() > 4) targetMotion = Vec3.ZERO;
        Vec3 inherited = inheritedMotion(player);
        Optional<BowAim.Solution> shot = chooseShot(mod, target, origin, targetMotion, inherited);
        double horizontalDistance = player.position().subtract(target.position()).horizontalDistance();

        if (_navigationMode == 1 && horizontalDistance >= 18) _navigationMode = 0;
        if (_navigationMode == 2 && horizontalDistance <= 18 && shot.isPresent()) _navigationMode = 0;
        if (_navigationMode == 0) {
            if (horizontalDistance < 8) _navigationMode = 1;
            else if (horizontalDistance > 24 || shot.isEmpty()) _navigationMode = 2;
        }
        if (_navigationMode != 0) {
            cancelOwnUse(mod);
            _steadyTicks = 0;
            _aimTicks = 0;
            Goal goal = _navigationMode == 1 ? _retreatGoal : _approachGoal;
            if (horse != null) {
                stopWalkingPath(mod);
                MountedNavigation.tick(mod, NAVIGATION_OWNER, goal);
            } else if (!_walkingPath || mod.getClientBaritone().getCustomGoalProcess().getGoal() != goal) {
                mod.getClientBaritone().getCustomGoalProcess().setGoalAndPath(goal);
                _walkingPath = true;
            }
            return false;
        }

        if (horse != null) {
            stopWalkingPath(mod);
            MountedNavigation.hold(mod, NAVIGATION_OWNER);
            if (!horse.onGround() || horse.isInWater() || horse.isInLava()
                    || horse.getDeltaMovement().horizontalDistance() > BRAKE_SPEED) {
                cancelOwnUse(mod);
                _steadyTicks = 0;
                _aimTicks = 0;
                return false;
            }
        } else {
            stopWalkingPath(mod);
            mod.getClientBaritone().getPathingBehavior().requestPause();
            if (!player.onGround() || player.isInWater()) {
                cancelOwnUse(mod);
                _steadyTicks = 0;
                return false;
            }
        }
        if (_lastSteadyTick != player.tickCount) {
            _steadyTicks = _lastSteadyTick == player.tickCount - 1 ? _steadyTicks + 1 : 1;
            _lastSteadyTick = player.tickCount;
        }
        if (_steadyTicks < 2 || shot.isEmpty()) return false;
        if (!player.getMainHandItem().is(Items.BOW)) {
            cancelOwnUse(mod);
            if (!mod.getSlotHandler().forceEquipItem(Items.BOW) || !player.getMainHandItem().is(Items.BOW)) return false;
        }
        BowAim.Solution solution = shot.get();
        float yaw = player.getYRot() + Mth.wrapDegrees(solution.yaw() - player.getYRot());
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(solution.pitch());
        _aimTicks++;
        if (player.tickCount < _nextDrawTick) return false;
        if (!_drawing) {
            mod.getInputControls().release(Input.CLICK_RIGHT);
            mod.getController().useItem(player, InteractionHand.MAIN_HAND);
            _drawing = player.isUsingItem() && player.getUsedItemHand() == InteractionHand.MAIN_HAND
                    && player.getUseItem().is(Items.BOW);
            if (!_drawing) {
                _nextDrawTick = player.tickCount + 10;
                return false;
            }
            _drawStartedTick = player.tickCount;
        }
        mod.getInputControls().hold(Input.CLICK_RIGHT);
        _ownsRightClick = true;
        if (player.tickCount - _drawStartedTick > MAX_DRAW_WAIT_TICKS) {
            cancelOwnUse(mod);
            _nextDrawTick = player.tickCount + 20;
            return false;
        }
        if (player.getTicksUsingItem() < BowAim.FULL_DRAW_TICKS || _aimTicks < 2) return false;
        // Check the actual launch vector one last time before the RELEASE_USE_ITEM packet.
        Vec3 actualInitial = player.getLookAngle().scale(3).add(inheritedMotion(player));
        if (!trajectoryClear(mod, target, arrowOrigin(player), actualInitial, targetMotion, solution.flightTicks())) {
            cancelOwnUse(mod);
            _navigationMode = 2;
            return false;
        }
        player.connection.send(new ServerboundMovePlayerPacket.Rot(player.getYRot(), player.getXRot(),
                player.onGround(), player.horizontalCollision));
        mod.getController().releaseUsingItem(player);
        mod.getInputControls().release(Input.CLICK_RIGHT);
        _ownsRightClick = false;
        _drawing = false;
        _nextDrawTick = player.tickCount + Math.max(4, Math.min(12, (int) Math.ceil(solution.flightTicks())));
        return true;
    }

    private static Optional<BowAim.Solution> chooseShot(AltoClef mod, Entity target, Vec3 origin,
                                                       Vec3 targetMotion, Vec3 inherited) {
        AABB box = target.getBoundingBox();
        double x = (box.minX + box.maxX) * 0.5;
        double z = (box.minZ + box.maxZ) * 0.5;
        Vec3[] points = {box.getCenter(), new Vec3(x, box.minY + box.getYsize() * 0.8, z),
                new Vec3(x, box.minY + box.getYsize() * 0.25, z),
                new Vec3(box.minX + box.getXsize() * 0.2, box.getCenter().y, z),
                new Vec3(box.minX + box.getXsize() * 0.8, box.getCenter().y, z)};
        for (int attempt = 0; attempt < points.length; attempt++) {
            int index = attempt == 0 ? _aimIndex : attempt <= _aimIndex ? attempt - 1 : attempt;
            Optional<BowAim.Solution> solution = BowAim.solve(origin, points[index], targetMotion,
                    inherited, BowAim.FULL_DRAW_TICKS);
            if (solution.isPresent() && trajectoryClear(mod, target, origin, solution.get().initialVelocity(),
                    targetMotion, solution.get().flightTicks())) {
                _aimIndex = index;
                return solution;
            }
        }
        return Optional.empty();
    }

    private static boolean trajectoryClear(AltoClef mod, Entity target, Vec3 origin, Vec3 initial,
                                            Vec3 targetMotion, double flightTicks) {
        Vec3 previous = origin;
        double time = 0;
        double horizon = Math.min(BowAim.MAX_FLIGHT_TICKS, flightTicks + 0.5);
        while (time < horizon) {
            double nextTime = Math.min(time + 1, horizon);
            Vec3 next = BowAim.positionAt(origin, initial, nextTime);
            Vec3 relativeFrom = previous.subtract(targetMotion.scale(time));
            Vec3 relativeTo = next.subtract(targetMotion.scale(nextTime));
            AABB targetBox = target.getBoundingBox();
            Optional<Vec3> targetHit = targetBox.contains(relativeFrom) ? Optional.of(relativeFrom)
                    : targetBox.clip(relativeFrom, relativeTo);
            double hitFraction = Double.POSITIVE_INFINITY;
            if (targetHit.isPresent()) {
                double segment = relativeFrom.distanceTo(relativeTo);
                hitFraction = segment < 1.0e-8 ? 0 : relativeFrom.distanceTo(targetHit.get()) / segment;
            }
            double endFraction = Math.min(1, hitFraction);
            Vec3 checkEnd = previous.lerp(next, endFraction);
            // A block or fluid behind the target's first hit must not invalidate that earlier hit.
            for (int sample = 0; sample <= 4; sample++) {
                BlockPos position = BlockPos.containing(previous.lerp(checkEnd, sample / 4.0));
                if (!mod.level().hasChunkAt(position) || !mod.level().getFluidState(position).isEmpty()) return false;
            }
            HitResult block = mod.level().clipIncludingBorder(new ClipContext(previous, checkEnd,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mod.getPlayer()));
            if (block.getType() != HitResult.Type.MISS && (targetHit.isEmpty()
                    || previous.distanceToSqr(block.getLocation()) + 1.0e-8 < previous.distanceToSqr(checkEnd))) return false;
            AABB search = new AABB(previous, checkEnd).inflate(0.5);
            for (Entity other : mod.level().getEntities(mod.getPlayer(), search,
                    entity -> entity != target && entity.canBeHitByProjectile()
                            && entity.getRootVehicle() != mod.getPlayer().getRootVehicle())) {
                AABB otherBox = other.getBoundingBox().inflate(0.3);
                if (otherBox.contains(previous) || otherBox.clip(previous, checkEnd).isPresent()) return false;
            }
            if (targetHit.isPresent()) return true;
            previous = next;
            time = nextTime;
        }
        return false;
    }

    private static Vec3 arrowOrigin(LocalPlayer player) {
        return new Vec3(player.getX(), player.getEyeY() - (double) 0.1f, player.getZ());
    }

    private static Vec3 inheritedMotion(LocalPlayer player) {
        Vec3 motion = player.isPassenger() ? player.getKnownSpeed() : player.getKnownMovement();
        return new Vec3(motion.x, player.onGround() ? 0 : motion.y, motion.z);
    }

    private static boolean ownsBowUse(LocalPlayer player) {
        return _drawing && player != null && player == _player && player.isUsingItem()
                && player.getUsedItemHand() == InteractionHand.MAIN_HAND && player.getUseItem().is(Items.BOW);
    }

    private static void cancelOwnUse(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        if (ownsBowUse(player)) {
            // Changing the selected slot cancels BowItem without its releaseUsing() arrow spawn.
            // Restore it immediately; the inventory and the winner's equipment remain untouched.
            int selected = player.getInventory().getSelectedSlot();
            player.connection.send(new ServerboundSetCarriedItemPacket((selected + 1) % 9));
            player.connection.send(new ServerboundSetCarriedItemPacket(selected));
            player.stopUsingItem();
            mod.getController().releaseUsingItem(player);
        }
        if (_ownsRightClick) {
            mod.getInputControls().release(Input.CLICK_RIGHT);
            _ownsRightClick = false;
        }
        _drawing = false;
    }

    private static void stopWalkingPath(AltoClef mod) {
        if (_walkingPath) {
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            _walkingPath = false;
        }
    }

    public static void reset(AltoClef mod) {
        cancelOwnUse(mod);
        MountedNavigation.release(mod, NAVIGATION_OWNER);
        stopWalkingPath(mod);
        _player = null;
        _target = null;
        _active = false;
        _navigationMode = 0;
        _steadyTicks = 0;
        _lastSteadyTick = -1;
        _aimTicks = 0;
        _aimIndex = 0;
        _nextDrawTick = 0;
        _approachGoal = null;
        _retreatGoal = null;
    }

    /** Mounted goals use horse feet, while sight uses the rider's current height above those feet. */
    private static final class ShootingPositionGoal implements Goal {
        private final AltoClef mod;
        private final Entity target;
        private final boolean retreat;

        private ShootingPositionGoal(AltoClef mod, Entity target, boolean retreat) {
            this.mod = mod;
            this.target = target;
            this.retreat = retreat;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            if (!target.isAlive()) return true;
            double distance = Math.hypot(x + 0.5 - target.getX(), z + 0.5 - target.getZ());
            // Goal cells represent their centers; leave room for the horse's real sub-block position.
            if (retreat) return distance >= 19;
            if (distance < 9 || distance > 16) return false;
            Entity vehicle = mod.getPlayer().getVehicle();
            double height = mod.getPlayer().getEyeY() - (vehicle == null ? mod.getPlayer().getY() : vehicle.getY());
            Vec3 origin = new Vec3(x + 0.5, y + height, z + 0.5);
            if (mod.level().clipIncludingBorder(new ClipContext(origin, target.getBoundingBox().getCenter(),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mod.getPlayer())).getType() != HitResult.Type.MISS) {
                return false;
            }
            Vec3 motion = target.getKnownSpeed();
            if (motion.lengthSqr() > 4) motion = Vec3.ZERO;
            Optional<BowAim.Solution> solution = BowAim.solve(origin, target.getBoundingBox(), motion,
                    Vec3.ZERO, BowAim.FULL_DRAW_TICKS);
            return solution.isPresent() && trajectoryClear(mod, target, origin, solution.get().initialVelocity(),
                    motion, solution.get().flightTicks());
        }

        @Override
        public double heuristic(int x, int y, int z) {
            double distance = Math.hypot(x + 0.5 - target.getX(), z + 0.5 - target.getZ());
            return retreat ? Math.max(0, 19 - distance)
                    : Math.max(0, distance - 16) + Math.max(0, 9 - distance);
        }

        @Override
        public String toString() { return retreat ? "Bow retreat to 18 blocks" : "Bow visible firing position"; }
    }
}
