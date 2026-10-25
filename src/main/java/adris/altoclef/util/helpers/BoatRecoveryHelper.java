package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** Small, side-effect-free rules used before converting a parked boat into an item. */
public final class BoatRecoveryHelper {
    public static final double MAX_DISTANCE_SQUARED = 64;

    private BoatRecoveryHelper() { }

    /** Cheap optional-cleanup preflight for a parent or idle dismount observer. */
    public static boolean canRecover(AltoClef mod, AbstractBoat boat) {
        if (mod == null || mod.getPlayer() == null || mod.level() == null || boat == null) return false;
        var player = mod.getPlayer();
        return !player.isPassenger() && player.isAlive() && !player.isSpectator()
                && !player.getAbilities().instabuild && boat.level() == mod.level()
                && eligible(boat.getUUID(), boat.getUUID(), boat.isRemoved() || !boat.isAlive(),
                !boat.getPassengers().isEmpty(), boat instanceof AbstractChestBoat, boat.isLeashed(),
                boat.isUnderWater(), boat.distanceToSqr(player))
                && hasRoom(player.getInventory(), expectedDrop(boat));
    }

    public static ItemStack expectedDrop(AbstractBoat boat) {
        ItemStack result = boat.getPickResult();
        result.set(DataComponents.CUSTOM_NAME, boat.getCustomName());
        return result;
    }

    public static boolean eligible(UUID requested, UUID actual, boolean removed, boolean occupied,
                                   boolean storageBoat, boolean leashed, boolean submerged,
                                   double distanceSquared) {
        return requested != null && requested.equals(actual) && !removed && !occupied && !storageBoat
                && !leashed && !submerged && Double.isFinite(distanceSquared)
                && distanceSquared >= 0 && distanceSquared <= MAX_DISTANCE_SQUARED;
    }

    public static boolean mayAct(boolean passenger, boolean usingItem, boolean eating,
                                 boolean safetyFrame, boolean fighting) {
        return !passenger && !usingItem && !eating && !safetyFrame && !fighting;
    }

    /** Armor/offhand slots are deliberately not mistaken for empty backpack space. */
    public static boolean hasRoom(Inventory inventory, ItemStack expected) {
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (slotHasRoom(inventory.getItem(slot), expected)) return true;
        }
        return false;
    }

    public static boolean slotHasRoom(ItemStack held, ItemStack expected) {
        return expected != null && !expected.isEmpty() && (held.isEmpty()
                || ItemStack.isSameItemSameComponents(held, expected)
                && held.getCount() < held.getMaxStackSize());
    }

    public static int count(Inventory inventory, ItemStack expected) {
        int count = 0;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack held = inventory.getItem(slot);
            if (ItemStack.isSameItemSameComponents(held, expected)) count += held.getCount();
        }
        return count;
    }

    /** Bare hands are preferred; ordinary non-weapons also avoid sword sweeping and spear STAB. */
    public static boolean safeAttackStack(ItemStack stack) {
        return stack.isEmpty() || !stack.has(DataComponents.WEAPON)
                && !stack.has(DataComponents.PIERCING_WEAPON) && !stack.has(DataComponents.KINETIC_WEAPON)
                && !SpearHelper.isSpear(stack);
    }

    public static boolean confirmed(int attacks, boolean originalBoatRemoved, int before, int now) {
        return attacks > 0 && originalBoatRemoved && before >= 0 && now > before;
    }

    public static boolean inventoryAheadOfRemoval(int attacks, boolean originalBoatRemoved, int before, int now) {
        return attacks > 0 && !originalBoatRemoved && before >= 0 && now > before;
    }
}
