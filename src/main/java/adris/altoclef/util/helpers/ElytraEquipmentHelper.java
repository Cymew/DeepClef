package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;

/** Native inventory operations and exact-stack flight consumable checks; never gathers items. */
public final class ElytraEquipmentHelper {
    private ElytraEquipmentHelper() { }

    public static boolean usable(ItemStack stack) {
        return stack != null && stack.is(Items.ELYTRA) && LivingEntity.canGlideUsing(stack, EquipmentSlot.CHEST);
    }

    public static int remaining(ItemStack stack) {
        if (stack == null || !stack.is(Items.ELYTRA)) return 0;
        if (!stack.isDamageableItem()) return Integer.MAX_VALUE;
        return Math.max(0, stack.getMaxDamage() - stack.getDamageValue());
    }

    public static boolean isEquipped(AltoClef mod) {
        return mod != null && mod.getPlayer() != null && usable(mod.getPlayer().getItemBySlot(EquipmentSlot.CHEST));
    }

    public static boolean hasUsableElytra(AltoClef mod) {
        return isEquipped(mod) || mod != null && mod.getPlayer() != null && bestElytraSlot(mod.getPlayer()) >= 0;
    }

    /** Current wing reserve; before equipping, returns the best carried pair's reserve. */
    public static int durabilityRemaining(AltoClef mod) {
        if (mod == null || mod.getPlayer() == null) return 0;
        var player = mod.getPlayer();
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.is(Items.ELYTRA) && (usable(chest) || player.isFallFlying())) return remaining(chest);
        int slot = bestElytraSlot(player);
        return slot < 0 ? 0 : remaining(player.getInventory().getItem(slot));
    }

    private static int bestElytraSlot(LocalPlayer player) {
        int best = -1, reserve = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (usable(stack) && remaining(stack) > reserve) { best = slot; reserve = remaining(stack); }
        }
        return best;
    }

    /** Values outside ordinary 0..3 duration are not supported by the bounded flight predictor. */
    public static boolean safeRocket(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.is(Items.FIREWORK_ROCKET)) return false;
        Fireworks fireworks = stack.get(DataComponents.FIREWORKS);
        return fireworks == null || fireworks.explosions().isEmpty()
                && fireworks.flightDuration() >= 0 && fireworks.flightDuration() <= 3;
    }

    /** Native lifetime 10*(1+duration)+random(6)+random(7), including the final boosted tick. */
    public static int rocketMaxTicks(ItemStack stack) {
        if (!safeRocket(stack)) return 0;
        Fireworks fireworks = stack.get(DataComponents.FIREWORKS);
        return 10 * (1 + (fireworks == null ? 0 : fireworks.flightDuration())) + 12;
    }

    /** Same stack order as equipRocket, without changing the selected hand. */
    public static int nextRocketMaxTicks(AltoClef mod) {
        if (mod == null || mod.getPlayer() == null) return 0;
        var player = mod.getPlayer();
        if (safeRocket(player.getMainHandItem())) return rocketMaxTicks(player.getMainHandItem());
        if (safeRocket(player.getOffhandItem())) return rocketMaxTicks(player.getOffhandItem());
        for (int slot = 0; slot < 36; slot++) {
            var stack = player.getInventory().getItem(slot);
            if (safeRocket(stack)) return rocketMaxTicks(stack);
        }
        return 52;
    }

    public static int countSafeRockets(AltoClef mod) {
        if (mod == null || mod.getPlayer() == null) return 0;
        var player = mod.getPlayer();
        int count = safeRocket(player.getOffhandItem()) ? player.getOffhandItem().getCount() : 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (safeRocket(stack)) count += stack.getCount();
        }
        return count;
    }

    /** Native rocket use drops every leash connection held by this player. */
    public static boolean hasLeashedFollowers(AltoClef mod) {
        if (mod == null || mod.getPlayer() == null || mod.level() == null) return false;
        for (var entity : mod.level().entitiesForRendering()) {
            if (entity instanceof Leashable leashable && leashable.getLeashHolder() == mod.getPlayer()) return true;
        }
        return false;
    }

    /** Selects the exact safe stack; callers own/restores their original selected hotbar slot. */
    public static InteractionHand equipRocket(AltoClef mod) {
        if (mod == null || mod.getPlayer() == null) return null;
        var player = mod.getPlayer();
        if (player.isUsingItem() || mod.getFoodChain().isTryingToEat()) return null;
        if (safeRocket(player.getMainHandItem())) return InteractionHand.MAIN_HAND;
        if (safeRocket(player.getOffhandItem())) return InteractionHand.OFF_HAND;
        if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) return null;
        for (int slot = 0; slot < 9; slot++) {
            if (!safeRocket(player.getInventory().getItem(slot))) continue;
            player.getInventory().setSelectedSlot(slot);
            return InteractionHand.MAIN_HAND;
        }
        for (int slot = 9; slot < 36; slot++) {
            if (!safeRocket(player.getInventory().getItem(slot))) continue;
            // Native cursor-free swap can operate without discarding the current hand item.
            mod.getSlotHandler().handleInventoryMouseClick(Slot.getFromCurrentScreenInventory(slot),
                    player.getInventory().getSelectedSlot(), ContainerInput.SWAP);
            return safeRocket(player.getMainHandItem()) ? InteractionHand.MAIN_HAND : null;
        }
        return null;
    }

    public static Task equipTask(AltoClef mod) { return new EquipTask(); }

    private static final class EquipTask extends Task {
        private LocalPlayer player;
        private ClientLevel world;
        private AltoClef owner;
        private int source = -1, buffer = -1, phase, ticks, lastTick = Integer.MIN_VALUE;
        private ItemStack wing = ItemStack.EMPTY, originalBuffer = ItemStack.EMPTY, originalChest = ItemStack.EMPTY;
        private boolean done;

        @Override protected void onStart(AltoClef mod) {
            owner = mod;
            if (player == null) { player = mod.getPlayer(); world = mod.level(); }
        }

        @Override protected Task onTick(AltoClef mod) {
            if (done) return null;
            if (player == null || mod.getPlayer() != player || mod.level() != world || !player.isAlive()) return finish();
            if (player.isFallFlying() || !player.onGround() || player.isPassenger()) return finish();
            if (player.isUsingItem() || mod.getFoodChain().isTryingToEat()) return null;
            if (lastTick != player.tickCount) { lastTick = player.tickCount; ticks++; }
            if (ticks > 100 || player.containerMenu != player.inventoryMenu
                    || !player.containerMenu.getCarried().isEmpty()) return finish();
            if (phase == 0) {
                if (isEquipped(mod)) return finish();
                source = bestElytraSlot(player);
                if (source < 0) return finish();
                wing = player.getInventory().getItem(source).copy();
                var armor = player.inventoryMenu.getSlot(PlayerSlot.ARMOR_CHESTPLATE_SLOT.getWindowSlot());
                if (!armor.mayPickup(player) || !armor.mayPlace(wing)) return finish();
                buffer = source < 9 ? source : player.getInventory().getSelectedSlot();
                originalBuffer = player.getInventory().getItem(buffer).copy();
                originalChest = player.getItemBySlot(EquipmentSlot.CHEST).copy();
                phase = source < 9 ? 2 : 1;
            }
            if (phase == 1) {
                if (same(player.getInventory().getItem(buffer), wing)) { phase = 2; return null; }
                if (!same(player.getInventory().getItem(source), wing)
                        || !same(player.getInventory().getItem(buffer), originalBuffer)) return finish();
                swap(source, buffer);
                return null;
            }
            if (phase == 2) {
                if (same(player.getItemBySlot(EquipmentSlot.CHEST), wing)) { phase = 3; return null; }
                var armor = player.inventoryMenu.getSlot(PlayerSlot.ARMOR_CHESTPLATE_SLOT.getWindowSlot());
                if (!same(player.getInventory().getItem(buffer), wing) || !armor.mayPickup(player) || !armor.mayPlace(wing))
                    return finish();
                mod.getSlotHandler().handleInventoryMouseClick(PlayerSlot.ARMOR_CHESTPLATE_SLOT, buffer, ContainerInput.SWAP);
                return null;
            }
            if (phase == 3) {
                if (source < 9) return finish();
                if (same(player.getInventory().getItem(buffer), originalBuffer)
                        && same(player.getInventory().getItem(source), originalChest)) return finish();
                if (!same(player.getInventory().getItem(buffer), originalChest)
                        || !same(player.getInventory().getItem(source), originalBuffer)) return finish();
                swap(source, buffer);
            }
            return null;
        }

        private void swap(int inventorySlot, int hotbar) {
            owner.getSlotHandler().handleInventoryMouseClick(Slot.getFromCurrentScreenInventory(inventorySlot), hotbar, ContainerInput.SWAP);
        }

        private Task finish() { done = true; return null; }

        @Override public boolean isFinished(AltoClef mod) { return done; }
        @Override protected void onStop(AltoClef mod, Task interruptTask) {
            // An interruption must not leave an unrelated hand item in the temporary backpack buffer.
            if (player != null && mod.getPlayer() == player && mod.level() == world
                    && player.containerMenu == player.inventoryMenu && player.containerMenu.getCarried().isEmpty()
                    && source >= 9 && buffer >= 0 && same(player.getInventory().getItem(source), originalBuffer)) {
                ItemStack inBuffer = player.getInventory().getItem(buffer);
                if (same(inBuffer, originalChest) && same(player.getItemBySlot(EquipmentSlot.CHEST), wing)
                        || same(inBuffer, wing) && same(player.getItemBySlot(EquipmentSlot.CHEST), originalChest)) swap(source, buffer);
            }
        }
        @Override protected boolean isEqual(Task other) { return other instanceof EquipTask; }
        @Override protected String toDebugString() { return "Equipping carried elytra without discarding armor"; }
    }

    private static boolean same(ItemStack first, ItemStack second) {
        return first.isEmpty() && second.isEmpty() || ItemStack.isSameItemSameComponents(first, second)
                && first.getCount() == second.getCount();
    }
}
