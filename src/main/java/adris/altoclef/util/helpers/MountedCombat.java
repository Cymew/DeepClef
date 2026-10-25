package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/** Distance hysteresis separates riding, bow aiming and a committed spear pass. */
public final class MountedCombat {
    public enum Mode { SPEAR, BOW, STOP }
    private static final Object HOLD_OWNER = new Object();
    private static LocalPlayer _owner;
    private static Entity _target;
    private static Mode _mode = Mode.STOP;

    private MountedCombat() { }

    public static boolean isActive() {
        return _owner != null && _owner == Minecraft.getInstance().player && _target != null
                && _target.isAlive() && !_target.isRemoved() && MountedNavigation.getControlledHorse(_owner) != null;
    }

    public static Optional<Entity> getTarget() { return isActive() ? Optional.of(_target) : Optional.empty(); }

    public static Mode chooseMode(boolean hasSpear, boolean canBow, boolean wasRanged,
                                  boolean drawing, boolean charging, double distance) {
        if (charging && hasSpear) return Mode.SPEAR;
        if (drawing && canBow && distance > 10) return Mode.BOW;
        if (!hasSpear) return canBow ? Mode.BOW : Mode.STOP;
        if (!canBow) return Mode.SPEAR;
        return distance > (wasRanged ? 10 : 14) ? Mode.BOW : Mode.SPEAR;
    }

    public static void tick(AltoClef mod, Entity target) {
        if (MountedNavigation.getControlledHorse(mod.getPlayer()) == null || target == null
                || !target.isAlive() || target.isRemoved()) { reset(mod); return; }
        if (MountedNavigation.isJumping()) { MountedNavigation.continueJump(mod); return; }
        if (_owner != mod.getPlayer() || _target != target) {
            reset(mod);
            _owner = mod.getPlayer();
            _target = target;
        }
        Item spear = bestSpear(mod);
        boolean canBow = BowCombat.canUse(mod) && BowCombat.canTarget(target);
        double distance = target.position().subtract(_owner.getControlledVehicle().position()).horizontalDistance();
        Mode next = chooseMode(spear != null, canBow, _mode == Mode.BOW,
                BowCombat.isDrawing(), SpearHelper.isCharging(), distance);
        if (next != _mode) {
            // Clear old use before switching equipment or acquiring a new rider input owner.
            if (_mode == Mode.BOW) BowCombat.reset(mod);
            if (_mode == Mode.SPEAR) SpearHelper.stopCharging(mod);
            MountedNavigation.release(mod, HOLD_OWNER);
            _mode = next;
        }
        if (_mode == Mode.BOW) {
            BowCombat.tick(mod, target);
        } else if (_mode == Mode.SPEAR) {
            if (_owner.getMainHandItem().getItem() != spear) {
                MountedNavigation.hold(mod, HOLD_OWNER);
                mod.getSlotHandler().forceEquipItem(spear);
            }
            if (SpearHelper.isSpear(_owner.getMainHandItem())) SpearHelper.tickSpearCombat(mod, target);
        } else {
            MountedNavigation.hold(mod, HOLD_OWNER);
        }
    }

    private static Item bestSpear(AltoClef mod) {
        ItemStack held = mod.getPlayer().getMainHandItem();
        Item best = SpearHelper.isSpear(held) ? held.getItem() : null;
        double damage = best == null ? Double.NEGATIVE_INFINITY : SpearHelper.getSpearAttackDamage(held);
        for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
            if (!SpearHelper.isSpear(stack)) continue;
            double candidate = SpearHelper.getSpearAttackDamage(stack);
            if (candidate > damage) { best = stack.getItem(); damage = candidate; }
        }
        return best;
    }

    public static void reset(AltoClef mod) {
        if (_owner == null) return;
        BowCombat.reset(mod);
        SpearHelper.stopCharging(mod);
        MountedNavigation.release(mod, HOLD_OWNER);
        _owner = null;
        _target = null;
        _mode = Mode.STOP;
    }
}
