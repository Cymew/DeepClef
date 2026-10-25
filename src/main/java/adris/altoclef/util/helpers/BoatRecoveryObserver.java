package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.BoatRecoveryTask;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;

import java.util.UUID;

/** Observes an idle manual dismount without replacing a user's current or queued task. */
public final class BoatRecoveryObserver {
    private final DismountMemory memory = new DismountMemory();
    private ClientLevel world;
    private LocalPlayer player;
    private AbstractBoat rememberedBoat;

    public void reset() {
        memory.reset();
        world = null;
        player = null;
        rememberedBoat = null;
    }

    /** Called before task arbitration, so scheduling never mutates the runner's chain iteration. */
    public void tick(AltoClef mod) {
        LocalPlayer current = mod.getPlayer();
        ClientLevel currentWorld = mod.level();
        if (current == null || currentWorld == null || !current.isAlive() || current.isRemoved()) {
            reset();
            return;
        }
        if (player != current || world != currentWorld) {
            reset();
            player = current;
            world = currentWorld;
        }
        AbstractBoat controlled = BoatNavigation.getControlledBoat(current);
        if (controlled != null) rememberedBoat = controlled;
        // getCurrentTask also sees a queued task before its first tick; isActive alone is not enough.
        boolean hasTask = mod.getUserTaskChain().getCurrentTask() != null;
        memory.observe(controlled == null ? null : controlled.getUUID(), current.isPassenger(), current.tickCount, hasTask);
        UUID candidate = memory.ready(current.tickCount);
        if (candidate == null) {
            if (!memory.hasBoat()) rememberedBoat = null;
            return;
        }
        if (rememberedBoat == null || !candidate.equals(rememberedBoat.getUUID())
                || !BoatRecoveryHelper.canRecover(mod, rememberedBoat)) {
            memory.reset();
            rememberedBoat = null;
            return;
        }
        if (survivalBusy(mod)) return;
        // Consume before scheduling: normal completion/cancellation cannot restart this cleanup.
        memory.reset();
        rememberedBoat = null;
        mod.runUserTask(new BoatRecoveryTask(candidate));
    }

    private static boolean survivalBusy(AltoClef mod) {
        var player = mod.getPlayer();
        if (player.isUsingItem() || player.isOnFire() || player.isInLava() || player.isUnderWater()
                || !player.onGround() && !player.isInWater()
                || player.getAirSupply() < player.getMaxAirSupply()
                || SpearHelper.isCombatActive() || BowCombat.isActive()) return true;
        // These priority queries only detect plans; they never equip, press keys or change tasks.
        if (mod.getFoodChain().isTryingToEat() || mod.getFoodChain().getPriority(mod) >= 50
                || mod.getMobDefenseChain().getPriority(mod) >= 50
                || mod.getMLGBucketChain().getPriority(mod) >= 50) return true;
        var foreground = mod.getTaskRunner().getCurrentTaskChain();
        return foreground != null && foreground != mod.getUserTaskChain()
                && foreground.isActive() && foreground.getPriority(mod) >= 50;
    }

    /** Pure transition memory: it never guesses a nearest boat after dismounting. */
    static final class DismountMemory {
        private UUID drivingBoat;
        private UUID pendingBoat;
        private int dismountTick;

        void observe(UUID controlledBoat, boolean passenger, int tick, boolean hasUserTask) {
            if (controlledBoat != null) {
                drivingBoat = controlledBoat;
                pendingBoat = null;
                return;
            }
            if (passenger) { reset(); return; }
            if (drivingBoat != null) {
                pendingBoat = drivingBoat;
                drivingBoat = null;
                dismountTick = tick;
            }
            if (hasUserTask || pendingBoat != null && (tick < dismountTick || tick - dismountTick > 100))
                pendingBoat = null;
        }

        UUID ready(int tick) {
            return pendingBoat != null && tick > dismountTick && tick - dismountTick <= 100 ? pendingBoat : null;
        }

        boolean hasBoat() { return drivingBoat != null || pendingBoat != null; }
        void reset() { drivingBoat = null; pendingBoat = null; dismountTick = 0; }
    }
}
