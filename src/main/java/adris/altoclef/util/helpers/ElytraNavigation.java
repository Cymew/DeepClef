package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.control.ElytraFlightInput;
import adris.altoclef.tasksystem.TaskChain;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Native jump, view and item-use control. The trajectory model only predicts movement. */
public final class ElytraNavigation {
    public enum Outcome { RUNNING, LANDED, FAILED }
    private enum Stage { TAKEOFF, DEPLOY, CLIMB, CRUISE, LAND, LANDED, FAILED }
    private static final Input[] KEYS = {Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT,
            Input.MOVE_RIGHT, Input.JUMP, Input.SNEAK, Input.SPRINT};
    private static Object owner;
    private static LocalPlayer player;
    private static ClientLevel world;
    private static ElytraFlightInput input;
    private static ElytraFlightTerrain terrain;
    private static ElytraRoutePlanner planner;
    private static ElytraCruisePolicy cruisePolicy;
    private static Stage stage;
    private static GoalXZ goal;
    private static Vec3 destination, landing, landingSearchTarget;
    private static double cruise;
    private static float yaw, pitch, previousYaw, previousPitch;
    private static int requestTick, lastTick, stageTick, releaseTick, retries, groundSamples, glideSamples;
    private static int unverifiedTicks, landingSearchTick;
    private static int lastGroundTick, landingSearchRadius;
    private static int boostUntil, lastRocketTick, rocketAckUntil, rocketsBefore, rocketDuration, originalSlot = -1, rocketSlot = -1;
    private static boolean jump, forward, mustLand, success, rocketPending;
    private static List<Vec3> path = List.of();
    private static String message = "鞘翅控制未启动。";

    private ElytraNavigation() { }

    public static boolean isActive() {
        return owner != null && player != null && player == Minecraft.getInstance().player
                && world == Minecraft.getInstance().level && player.isAlive() && !player.isPassenger()
                && requestTick >= player.tickCount - 1 && stage != Stage.FAILED && stage != Stage.LANDED;
    }

    public static boolean ownsFlight(AltoClef mod) {
        return isActive() && mod.getPlayer() == player && !player.isInWater() && !player.isInLava();
    }

    public static String status(AltoClef mod) {
        return (isActive() ? stage + " | " + message : message) + " | 无爆炸烟花="
                + (mod.getPlayer() == null ? 0 : ElytraEquipmentHelper.countSafeRockets(mod));
    }
    public static String result(Object requester) { return owner == requester ? message : "飞行已中断。"; }
    public static boolean succeeded(Object requester) { return owner == requester && success; }
    public static LocalPlayer getRenderPlayer() { return isActive() ? player : null; }
    public static List<Vec3> getRenderPath() { return isActive() ? path : List.of(); }
    public static Goal getRenderGoal() { return landing == null ? goal : new GoalBlock(net.minecraft.core.BlockPos.containing(landing)); }

    public static Outcome tick(AltoClef mod, Object requester, int x, int z, boolean landOnly) {
        if (owner != requester || player != mod.getPlayer() || world != mod.level()) begin(mod, requester, x, z, landOnly);
        if (player == null || !player.isAlive() || player.isPassenger() || player.isInWater() || player.isInLava())
            return fail("飞行已停止：玩家、载具或液体状态不允许继续滑翔。");
        requestTick = player.tickCount;
        if (stage == Stage.FAILED) return Outcome.FAILED;
        if (stage == Stage.LANDED) return Outcome.LANDED;
        if (lastTick == player.tickCount) return Outcome.RUNNING;
        lastTick = player.tickCount;
        previousYaw = yaw; previousPitch = pitch;
        jump = forward = false;
        mod.getClientBaritone().getPathingBehavior().requestPause();
        int age = player.tickCount - stageTick;

        if (stage == Stage.TAKEOFF) {
            if (!player.onGround()) {
                stage = Stage.DEPLOY; stageTick = releaseTick = player.tickCount;
                message = "起跳后松开跳跃，准备展开鞘翅。";
            } else {
                if (age > 30) return fail("起飞失败：未能正常跳离地面。");
                jump = true;
                pitch = -45;
                message = "原版起跳，准备鞘翅滑翔。";
            }
            return Outcome.RUNNING;
        }
        if (stage == Stage.DEPLOY) {
            if (player.isFallFlying()) {
                if (++glideSamples >= 2) {
                    stage = mustLand ? Stage.LAND : Stage.CLIMB; stageTick = player.tickCount;
                    message = "鞘翅已展开，开始检查飞行轨迹。";
                }
            } else {
                glideSamples = 0;
                // A real release sample separates ground jumping from the native airborne jump edge.
                jump = player.tickCount > releaseTick && (player.tickCount - releaseTick) % 4 == 1;
                pitch = -45;
                if (player.onGround() && age > 5) {
                    if (++retries >= 3) return fail("起飞失败：服务器未保持滑翔，已停止重复起跳。");
                    stage = Stage.TAKEOFF; stageTick = player.tickCount;
                } else if (age > 80) return fail("鞘翅未能保持展开，已交还原生坠落保护。");
            }
            return Outcome.RUNNING;
        }

        if (player.onGround()) {
            lastGroundTick = player.tickCount;
            if (++groundSamples >= 3) {
                success = landOnly || horizontal(player.position(), destination) <= 20;
                stage = Stage.LANDED;
                message = success ? "已在目的地附近着陆。" : "已提前着陆，尚未到达原目的地。";
                return Outcome.LANDED;
            }
            message = "正在确认原版落地状态。";
            return Outcome.RUNNING;
        }
        if (!player.isFallFlying()) {
            if (landing != null && horizontal(player.position(), landing) <= 2
                    && ElytraFlightPlan.settlingAfterGround(player.tickCount, lastGroundTick,
                    player.getY() - landing.y, player.getDeltaMovement().y)) {
                message = "正在等待服务器确认落点。";
                return Outcome.RUNNING;
            }
            // Do not retain an airborne lease that would block the actual bucket/levitation recovery.
            return fail("滑翔已意外中断，已交还坠落/生存保护。");
        }
        groundSamples = 0;

        int rockets = ElytraEquipmentHelper.countSafeRockets(mod);
        if (rocketPending && rockets < rocketsBefore) {
            rocketPending = false;
            // A delayed server acknowledgement must not allow a second attached rocket to overlap.
            boostUntil = Math.max(boostUntil, player.tickCount + rocketDuration + 3);
        }
        if (rocketPending && player.tickCount > rocketAckUntil) {
            rocketPending = false;
            mustLand = true;
            message = "烟花未收到库存确认，转入降落，不重复连发。";
        }
        if (ElytraEquipmentHelper.durabilityRemaining(mod) <= 24 || rockets == 0
                || player.isOnFire() || player.getHealth() <= 6 || player.getFoodData().getFoodLevel() <= 3) mustLand = true;
        double distance = horizontal(player.position(), destination);
        double heightAboveGoal = player.getY() - (landing == null ? destination.y : landing.y);
        int remainingBoost = Math.max(0, boostUntil - player.tickCount);
        double horizontalSpeed = player.getDeltaMovement().horizontalDistance();
        if (stage == Stage.CLIMB && player.getY() >= cruise - 6) stage = Stage.CRUISE;
        if (mustLand || ElytraFlightPlan.shouldApproach(heightAboveGoal, distance, horizontalSpeed, remainingBoost))
            stage = Stage.LAND;
        if (stage == Stage.LAND) {
            if (landingSearchTarget == null) {
                landingSearchTarget = mustLand ? player.position() : destination;
                landingSearchTick = player.tickCount;
            }
            if (landing == null || player.tickCount % 20 == 0) {
                var found = terrain.findLanding(landingSearchTarget, player.tickCount, landingSearchRadius);
                landing = found.orElse(null);
            }
            if (landing == null && terrain.landingSearchState() == ElytraFlightTerrain.LandingSearchState.EXHAUSTED
                    && player.tickCount - landingSearchTick > 40 && landingSearchRadius < 96) {
                // Only a completed search of loaded terrain justifies widening the goal area.
                landingSearchRadius = Math.min(96, landingSearchRadius + 18);
                landingSearchTick = player.tickCount;
            }
        }
        int nextRocketTicks = ElytraEquipmentHelper.nextRocketMaxTicks(mod);
        boolean mayRocket = !rocketPending && player.tickCount > boostUntil && player.tickCount - lastRocketTick >= 12
                && rockets > 0 && !mustLand && stage != Stage.LAND
                && ElytraFlightPlan.canStartBoost(heightAboveGoal, distance, horizontalSpeed, remainingBoost, nextRocketTicks);
        var economy = cruisePolicy.update(player.tickCount, player.getY(), horizontalSpeed, player.getDeltaMovement().y,
                cruise, distance, remainingBoost, stage == Stage.CLIMB, mayRocket);
        planner.setCruisePitch(economy.targetPitch());
        mayRocket = mayRocket && economy.allowRocket();
        var phase = stage == Stage.CLIMB ? ElytraRoutePlanner.Phase.CLIMB
                : stage == Stage.LAND ? ElytraRoutePlanner.Phase.LAND : ElytraRoutePlanner.Phase.CRUISE;
        var state = new ElytraRoutePlanner.State(player.position(), player.getDeltaMovement(), yaw, pitch,
                player.getGravity(), remainingBoost);
        Vec3 flightTarget = stage == Stage.LAND && landingSearchTarget != null ? landingSearchTarget : destination;
        var request = new ElytraRoutePlanner.Request(flightTarget, cruise, phase, landing, mayRocket, nextRocketTicks);
        var guidance = planner.update(state, request, terrain, player.tickCount, 2_000_000);
        yaw = guidance.yaw(); pitch = guidance.pitch(); path = guidance.trajectory();
        if (guidance.landingPoint() != null) landing = guidance.landingPoint();
        unverifiedTicks = guidance.trajectory().isEmpty() ? unverifiedTicks + 1 : 0;
        if (unverifiedTicks >= 10) { mustLand = true; stage = Stage.LAND; }
        if (guidance.useRocket() && mayRocket && !guidance.blocked()) useRocket(mod);
        message = stage + " | " + (stage == Stage.LAND ? planner.landingPhase() : economy.mode())
                + " | 高度=" + Math.round(player.getY()) + "/" + Math.round(cruise)
                + " | 余程=" + Math.round(distance)
                + (stage == Stage.LAND ? landing == null ? " | 落点=" + terrain.landingSearchState()
                    : " | 落点=" + Math.round(landing.x) + "," + Math.round(landing.y) + "," + Math.round(landing.z) : "")
                + (guidance.blocked() ? " | 正在沿可验证避障轨迹飞行" : "");
        return Outcome.RUNNING;
    }

    private static void begin(AltoClef mod, Object requester, int x, int z, boolean landOnly) {
        releaseAll(mod);
        owner = requester; player = mod.getPlayer(); world = mod.level();
        if (player == null || world == null) { stage = Stage.FAILED; message = "未进入世界。"; return; }
        input = new ElytraFlightInput(); terrain = new ElytraFlightTerrain(mod); planner = new ElytraRoutePlanner();
        cruisePolicy = new ElytraCruisePolicy();
        goal = new GoalXZ(x, z);
        destination = new Vec3(x + .5, player.getY(), z + .5);
        landingSearchTarget = null;
        landingSearchRadius = 18; lastGroundTick = Integer.MIN_VALUE;
        cruise = world.dimensionType().hasCeiling() ? Math.min(112, world.getMaxY() - 12) : world.getMaxY() + 16;
        yaw = previousYaw = player.getYRot(); pitch = previousPitch = player.getXRot();
        if (!landOnly) yaw = previousYaw = (float)Math.toDegrees(Math.atan2(-(destination.x - player.getX()), destination.z - player.getZ()));
        mustLand = landOnly; success = false; rocketPending = false;
        stage = player.isFallFlying() ? (landOnly ? Stage.LAND : Stage.CLIMB)
                : player.onGround() ? Stage.TAKEOFF : Stage.DEPLOY;
        stageTick = releaseTick = requestTick = player.tickCount;
        lastTick = Integer.MIN_VALUE; lastRocketTick = player.tickCount - 100;
        boostUntil = player.tickCount - 1; retries = groundSamples = glideSamples = unverifiedTicks = 0;
        for (Entity entity : world.entitiesForRendering()) {
            if (entity instanceof FireworkRocketEntity rocket && rocket.getOwner() == player && rocket.distanceToSqr(player) < 16)
                boostUntil = player.tickCount + 52;
        }
        BoatNavigation.releaseAll(mod); MountedNavigation.releaseAll(mod);
        mod.getClientBaritone().getElytraProcess().onLostControl();
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        if (landOnly && player.onGround()) { stage = Stage.LANDED; success = true; message = "当前已在地面。"; }
    }

    private static void useRocket(AltoClef mod) {
        if (!player.isFallFlying() || player.isUsingItem() || player.containerMenu != player.inventoryMenu
                || !player.containerMenu.getCarried().isEmpty()) return;
        int selected = player.getInventory().getSelectedSlot();
        InteractionHand hand = ElytraEquipmentHelper.equipRocket(mod);
        if (hand == null || !ElytraEquipmentHelper.safeRocket(player.getItemInHand(hand))) return;
        if (originalSlot < 0) originalSlot = selected;
        rocketSlot = player.getInventory().getSelectedSlot();
        rocketsBefore = ElytraEquipmentHelper.countSafeRockets(mod);
        if (ElytraEquipmentHelper.hasLeashedFollowers(mod)) { mustLand = true; return; }
        rocketDuration = ElytraEquipmentHelper.rocketMaxTicks(player.getItemInHand(hand));
        lastRocketTick = player.tickCount; boostUntil = player.tickCount + rocketDuration + 3;
        rocketPending = true; rocketAckUntil = player.tickCount + 60;
        applyLook(mod);
        mod.getController().useItem(player, hand);
    }

    private static Outcome fail(String reason) { stage = Stage.FAILED; message = reason; return Outcome.FAILED; }
    private static double horizontal(Vec3 a, Vec3 b) { return Math.hypot(a.x - b.x, a.z - b.z); }

    public static void prepareFrame(AltoClef mod, TaskChain selected) {
        if (owner != null && selected != mod.getUserTaskChain()) releaseAll(mod);
    }
    public static void endTick(AltoClef mod) {
        if (owner == null) return;
        if (!mod.getTaskRunner().isActive() || !isActive() || requestTick != player.tickCount) { releaseAll(mod); return; }
        applyInputs(mod); applyLook(mod);
    }
    public static void applyInputs(AltoClef mod) {
        if (!ownsFlight(mod)) return;
        for (Input key : KEYS) {
            boolean pressed = key == Input.JUMP && jump || key == Input.MOVE_FORWARD && forward;
            if (pressed) mod.getInputControls().hold(key); else mod.getInputControls().release(key);
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(key, pressed);
        }
        input.setIntent(jump, forward);
        player.input = input;
    }
    public static void applyLook(AltoClef mod) {
        if (!ownsFlight(mod)) return;
        player.setYRot(yaw); player.setYHeadRot(yaw); player.setXRot(pitch);
    }
    public static float getRenderYaw(Entity entity, float partialTick, float fallback) {
        return isActive() && entity == player ? SpearHelper.interpolateYaw(previousYaw, yaw, partialTick) : fallback;
    }
    public static float getRenderPitch(Entity entity, float partialTick, float fallback) {
        return isActive() && entity == player ? Mth.lerp(Mth.clamp(partialTick, 0, 1), previousPitch, pitch) : fallback;
    }
    public static void release(AltoClef mod, Object requester) { if (owner == requester) releaseAll(mod); }
    public static void releaseAll(AltoClef mod) {
        boolean heldLease = owner != null || input != null;
        if (player != null && mod.getPlayer() == player && mod.level() == world) {
            if (rocketSlot >= 0 && originalSlot >= 0 && player.getInventory().getSelectedSlot() == rocketSlot)
                player.getInventory().setSelectedSlot(originalSlot);
            if (player.input == input) player.input = new KeyboardInput(Minecraft.getInstance().options);
        }
        // Forced keys belong to the client controller, not the discarded player instance.
        if (heldLease) {
            for (Input key : KEYS) {
                mod.getInputControls().release(key);
                mod.getClientBaritone().getInputOverrideHandler().setInputForceState(key, false);
            }
        }
        owner = null; player = null; world = null; input = null; planner = null; terrain = null; cruisePolicy = null;
        landing = null; path = List.of(); originalSlot = rocketSlot = -1;
    }
}
