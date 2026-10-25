package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.entity.AbstractKillEntityTask;
import adris.altoclef.tasks.movement.GetToEntityTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.baritone.CachedProjectile;
import adris.altoclef.util.slots.PlayerSlot;
import baritone.api.utils.input.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Ground PvP combat controller.
 *
 * <p>Reuses the PvE controllers instead of re-implementing them:
 * <ul>
 *   <li>{@link SpearHelper} — spear poke rhythm (approach → aim → charge → stab → retreat)</li>
 *   <li>{@link BowCombat} — bow positioning, aiming and releasing</li>
 *   <li>{@link AbstractKillEntityTask} — sword equip/attack logic</li>
 * </ul>
 *
 * <p>On top of that it adds the habits a player needs against another player:
 * <ul>
 *   <li><b>Distance-based weapon choice</b> — spear/sword up close, bow once the
 *       target opens the gap; hysteresis keeps it from flipping every tick.</li>
 *   <li><b>Shield</b> — when the opponent is drawing a bow or a projectile is
 *       incoming, raise the shield and keep closing the distance behind it.</li>
 *   <li><b>Strafing</b> — circle around the target while in melee so incoming
 *       attacks miss more often.</li>
 * </ul>
 */
public final class PvpCombat {
    public enum Mode { NONE, SHIELD, BOW, SPEAR, SWORD }

    /** Above this distance, shooting is preferred over poking. */
    private static final double BOW_PREFER_RANGE = 9.0;
    /** Below this distance we stop placing shots and go melee. */
    private static final double BOW_DROP_RANGE = 6.0;
    /** Sword reach used for the attack/approach gate. */
    private static final double MELEE_RANGE = 3.4;
    /** While under fire we only stop blocking once this close. */
    private static final double SHIELD_DROP_RANGE = 2.6;
    private static final int STRAFE_SWITCH_TICKS = 16;
    private static final double PROJECTILE_SCAN_RANGE = 12.0;

    private static LocalPlayer _player;
    private static Entity _target;
    private static Mode _mode = Mode.NONE;
    private static boolean _ownsRightClick;
    private static int _strafeDir = 1;
    private static int _lastStrafeSwitch;

    private PvpCombat() { }

    public static boolean isActive() {
        return _player != null && _target != null && _target.isAlive();
    }

    public static Mode getMode() {
        return _mode;
    }

    /**
     * Runs one tick of PvP combat. Returns null when the task should keep
     * running, or a movement task (approach) that the task system must execute.
     */
    public static Task tick(AltoClef mod, Entity target) {
        LocalPlayer player = mod.getPlayer();
        if (player == null || target == null || !target.isAlive() || target.isRemoved() || !AltoClef.inGame()) {
            reset(mod);
            return null;
        }
        if (_player != player || _target != target) {
            reset(mod);
            _player = player;
            _target = target;
        }
        if (mod.getFoodChain().needsToEat()) {
            // FoodChain takes over; never fight while eating.
            reset(mod);
            return null;
        }

        double distance = player.distanceTo(target);
        CombatPreferences.Style style = CombatPreferences.getStyle();
        // Weapon families are restricted by the requested style; AUTO keeps the
        // balanced default (spear if available, sword otherwise, bow at range).
        boolean bowReady = BowCombat.canUse(mod) && BowCombat.canTarget(target)
                && (style == CombatPreferences.Style.AUTO || style == CombatPreferences.Style.BOW);
        Item spear = (style == CombatPreferences.Style.SWORD || style == CombatPreferences.Style.BOW)
                ? null : CombatPreferences.bestSpear(mod);
        boolean underFire = opponentIsDrawingBow(target) || incomingProjectile(mod, player);

        // 1) Under fire: block first, keep closing the gap behind the shield.
        if (underFire && distance > SHIELD_DROP_RANGE
                && !SpearHelper.isCharging() && !BowCombat.isDrawing()) {
            setMode(Mode.SHIELD);
            raiseShield(mod);
            LookHelper.lookAt(mod, target.getEyePosition());
            return distance > MELEE_RANGE ? new GetToEntityTask(target, MELEE_RANGE) : null;
        }
        if (_mode == Mode.SHIELD) dropShield(mod);

        // 2) Ranged: bow once the target opens the distance (or always, if forced).
        boolean forcedBow = style == CombatPreferences.Style.BOW;
        if (bowReady && (forcedBow || distance > (_mode == Mode.BOW ? BOW_DROP_RANGE : BOW_PREFER_RANGE))) {
            setMode(Mode.BOW);
            dropShield(mod);
            BowCombat.tick(mod, target);
            return null;
        }

        // 3) Spear: the same poke rhythm PvE uses (beats a sword in reach).
        if (spear != null) {
            setMode(Mode.SPEAR);
            dropShield(mod);
            if (!SpearHelper.isSpear(player.getMainHandItem())) {
                mod.getSlotHandler().forceEquipItem(spear);
                return null;
            }
            return SpearHelper.tickSpearCombat(mod, target);
        }

        // 4) Sword fallback: equip, close in, strafe and swing on full cooldown.
        setMode(Mode.SWORD);
        dropShield(mod);
        return swordMelee(mod, target, distance);
    }

    private static Task swordMelee(AltoClef mod, Entity target, double distance) {
        LocalPlayer player = mod.getPlayer();
        if (CombatPreferences.getStyle() == CombatPreferences.Style.SWORD) {
            // Honour the explicit sword choice: never swap to an equally strong spear.
            Item weapon = CombatPreferences.bestMeleeWeapon(mod);
            if (weapon != null && player.getMainHandItem().getItem() != weapon) {
                mod.getSlotHandler().forceEquipItem(weapon);
                return null; // equipping this tick
            }
        } else if (AbstractKillEntityTask.equipWeapon(mod)) {
            return null; // equipping this tick
        }
        if (distance > MELEE_RANGE) {
            return new GetToEntityTask(target, MELEE_RANGE);
        }
        LookHelper.lookAt(mod, target.getEyePosition());
        strafe(mod, distance);
        if (player.getAttackStrengthScale(0) >= 1f
                && (player.onGround() || player.getDeltaMovement().y < 0 || player.isInWater())) {
            mod.getControllerExtras().attack(target);
        }
        return null;
    }

    // ---------------------------------------------------------------- weapon helpers

    private static Item bestSpear(AltoClef mod) {
        ItemStack held = mod.getPlayer().getMainHandItem();
        Item best = SpearHelper.isSpear(held) ? held.getItem() : null;
        double bestDamage = best == null ? Double.NEGATIVE_INFINITY : SpearHelper.getSpearAttackDamage(held);
        for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
            if (!SpearHelper.isSpear(stack)) continue;
            double damage = SpearHelper.getSpearAttackDamage(stack);
            if (damage > bestDamage) {
                bestDamage = damage;
                best = stack.getItem();
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- shield

    private static void raiseShield(AltoClef mod) {
        ItemStack offhand = StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT);
        if (offhand.getItem() != Items.SHIELD) {
            if (mod.getItemStorage().hasItem(Items.SHIELD) || mod.getItemStorage().hasItemInOffhand(Items.SHIELD)) {
                mod.getSlotHandler().forceEquipItemToOffhand(Items.SHIELD);
            }
            return;
        }
        mod.getInputControls().hold(Input.CLICK_RIGHT);
        _ownsRightClick = true;
    }

    private static void dropShield(AltoClef mod) {
        if (_ownsRightClick) {
            mod.getInputControls().release(Input.CLICK_RIGHT);
            _ownsRightClick = false;
        }
    }

    private static boolean opponentIsDrawingBow(Entity target) {
        return target instanceof LivingEntity living && living.isUsingItem()
                && living.getUseItem().is(Items.BOW);
    }

    private static boolean incomingProjectile(AltoClef mod, LocalPlayer player) {
        double rangeSq = PROJECTILE_SCAN_RANGE * PROJECTILE_SCAN_RANGE;
        for (CachedProjectile projectile : mod.getEntityTracker().getProjectiles()) {
            Vec3 toPlayer = player.position().subtract(projectile.position);
            if (toPlayer.lengthSqr() > rangeSq) continue;
            // Only worry about projectiles actually travelling towards us.
            if (projectile.velocity.dot(toPlayer) <= 0) continue;
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- movement

    private static void strafe(AltoClef mod, double distance) {
        LocalPlayer player = mod.getPlayer();
        if (player.tickCount - _lastStrafeSwitch > STRAFE_SWITCH_TICKS) {
            _strafeDir = -_strafeDir;
            _lastStrafeSwitch = player.tickCount;
        }
        if (_strafeDir > 0) {
            mod.getInputControls().hold(Input.MOVE_LEFT);
            mod.getInputControls().release(Input.MOVE_RIGHT);
        } else {
            mod.getInputControls().hold(Input.MOVE_RIGHT);
            mod.getInputControls().release(Input.MOVE_LEFT);
        }
        // Stay glued to the target while circling, but do not push into it.
        if (distance > 2.2) {
            mod.getInputControls().hold(Input.MOVE_FORWARD);
        } else {
            mod.getInputControls().release(Input.MOVE_FORWARD);
        }
    }

    private static void setMode(Mode mode) {
        _mode = mode;
    }

    public static void reset(AltoClef mod) {
        if (mod != null) {
            dropShield(mod);
            SpearHelper.stopCharging(mod);
            BowCombat.reset(mod);
            mod.getInputControls().release(Input.MOVE_LEFT);
            mod.getInputControls().release(Input.MOVE_RIGHT);
            mod.getInputControls().release(Input.MOVE_FORWARD);
        }
        _player = null;
        _target = null;
        _mode = Mode.NONE;
        _strafeDir = 1;
        _lastStrafeSwitch = 0;
    }
}
