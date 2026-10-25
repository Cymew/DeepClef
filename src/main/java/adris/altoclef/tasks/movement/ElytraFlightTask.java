package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ElytraEquipmentHelper;
import adris.altoclef.util.helpers.ElytraFlightTerrain;
import adris.altoclef.util.helpers.ElytraFlightPlan;
import adris.altoclef.util.helpers.ElytraNavigation;
import adris.altoclef.util.helpers.ElytraPhysics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;

/** A trip finishes only after observed native ground contact, never at an airborne XZ match. */
public final class ElytraFlightTask extends Task {
    private final int x, z;
    private final boolean landOnly;
    private LocalPlayer player;
    private ClientLevel world;
    private Task equip;
    private ElytraFlightTerrain launchTerrain;
    private boolean started, done, success, equipmentStarted;
    private int ticks, launchAttempts, lastTick = Integer.MIN_VALUE;
    private String result;

    private ElytraFlightTask(int x, int z, boolean landOnly) { this.x = x; this.z = z; this.landOnly = landOnly; }
    public static ElytraFlightTask travel(int x, int z) { return new ElytraFlightTask(x, z, false); }
    public static ElytraFlightTask land() { return new ElytraFlightTask(0, 0, true); }
    public static ElytraFlightTask forExecution(Task current, ElytraFlightTask requested) {
        return current instanceof ElytraFlightTask existing && existing.equals(requested) ? existing : requested;
    }
    public String getResult() { return result == null ? "鞘翅飞行已中断，尚未确认着陆。" : result; }
    public boolean succeeded() { return done && success; }
    public static String status(AltoClef mod) {
        if (mod.getPlayer() == null || mod.level() == null) return "未进入世界。";
        return "鞘翅已装备=" + ElytraEquipmentHelper.isEquipped(mod) + "，剩余耐久="
                + ElytraEquipmentHelper.durabilityRemaining(mod) + "，滑翔=" + mod.getPlayer().isFallFlying()
                + " | " + ElytraNavigation.status(mod);
    }

    @Override protected void onStart(AltoClef mod) {
        if (player == null) { player = mod.getPlayer(); world = mod.level(); }
        lastTick = Integer.MIN_VALUE;
    }

    @Override protected Task onTick(AltoClef mod) {
        if (done) return null;
        if (player == null || mod.getPlayer() != player || world == null || mod.level() != world || !player.isAlive())
            return finish(mod, false, "飞行已停止：玩家或世界已变化。");
        if (player.isPassenger() || player.getAbilities().flying || player.isSpectator())
            return finish(mod, false, "请先离开载具或创造飞行，再使用鞘翅导航。");
        if (player.isInWater() || player.isInLava() || player.hasEffect(MobEffects.LEVITATION))
            return finish(mod, false, "当前液体或飘浮状态不能启动鞘翅飞行。");
        if (lastTick != player.tickCount) { lastTick = player.tickCount; ticks++; }
        if (!started) {
            if (landOnly && player.onGround()) return finish(mod, true, "当前已在地面。无需降落。");
            if (!landOnly && ElytraEquipmentHelper.hasLeashedFollowers(mod))
                return finish(mod, false, "仍有生物拴在你身上；原版烟花会断开牵绳，请先安置它们。");
            if (equip != null) {
                if (!equip.isFinished(mod)) return equip;
                if (equip.isActive()) equip.stop(mod);
                equip = null;
                if (!ElytraEquipmentHelper.isEquipped(mod))
                    return finish(mod, false, "未能确认鞘翅装备完成，请检查装备槽和耐久。");
                return null;
            }
            if (!ElytraEquipmentHelper.isEquipped(mod)) {
                if (!player.onGround()) return finish(mod, false, "请在地面先装备可用鞘翅；空中不会更换装备。");
                if (equip == null && !equipmentStarted) {
                    equipmentStarted = true;
                    equip = ElytraEquipmentHelper.equipTask(mod);
                }
                if (equip == null || equip.isFinished(mod))
                    return finish(mod, false, "未能装备背包中的可用鞘翅，请检查装备槽和耐久。");
                setDebugState("Equipping the carried elytra through native inventory slots.");
                return equip;
            }
            if (!player.isFallFlying()) {
                if (ElytraEquipmentHelper.durabilityRemaining(mod) <= 32)
                    return finish(mod, false, "鞘翅耐久不足以预留安全降落，请先修复。");
                if (ElytraEquipmentHelper.countSafeRockets(mod) == 0)
                    return finish(mod, false, "背包没有可用于推进的无爆炸烟花火箭。");
                if (player.onGround()) {
                    double distance = Math.hypot(x + .5 - player.getX(), z + .5 - player.getZ());
                    if (!landOnly && !ElytraFlightPlan.canStartBoost(0, distance, 0, 0,
                            ElytraEquipmentHelper.nextRocketMaxTicks(mod)))
                        return finish(mod, false, "距离过近，不足以完成本次烟花起飞和降落；请用普通 @goto。");
                    float direction = landOnly ? player.getYRot()
                            : (float)Math.toDegrees(Math.atan2(-(x + .5 - player.getX()), z + .5 - player.getZ()));
                    if (launchTerrain == null) launchTerrain = new ElytraFlightTerrain(mod);
                    if (!launchTerrain.clearTakeoff(player.position(), ElytraPhysics.look(direction, 0))) {
                        if (++launchAttempts >= 20)
                            return finish(mod, false, "附近没有已加载且净空足够的起飞通道，请移动到开阔地面。");
                        setDebugState("Checking loaded takeoff clearance in bounded frames.");
                        return null;
                    }
                }
            }
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            started = true;
            return null;
        }
        int targetX = landOnly ? player.blockPosition().getX() : x;
        int targetZ = landOnly ? player.blockPosition().getZ() : z;
        var outcome = ElytraNavigation.tick(mod, this, targetX, targetZ, landOnly);
        setDebugState(ElytraNavigation.status(mod));
        if (outcome != ElytraNavigation.Outcome.RUNNING) {
            boolean completed = outcome == ElytraNavigation.Outcome.LANDED && ElytraNavigation.succeeded(this);
            return finish(mod, completed, ElytraNavigation.result(this));
        }
        return null;
    }

    private Task finish(AltoClef mod, boolean succeeded, String message) {
        done = true; success = succeeded; result = message;
        if (equip != null && equip.isActive()) equip.stop(mod);
        ElytraNavigation.release(mod, this);
        setDebugState(message);
        return null;
    }
    @Override public boolean isFinished(AltoClef mod) { return done; }
    @Override protected void onStop(AltoClef mod, Task interruptTask) { ElytraNavigation.release(mod, this); }
    @Override protected boolean isEqual(Task other) {
        return other instanceof ElytraFlightTask task && task.x == x && task.z == z && task.landOnly == landOnly;
    }
    @Override protected String toDebugString() { return landOnly ? "Landing with elytra" : "Flying to " + x + " " + z; }
}
