package adris.altoclef.util;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.entity.player.Inventory;
import java.util.function.BiConsumer;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/** A reporting cursor owned by one client connection. Accessed on the client thread. */
public final class InventoryChanges {
    public interface Source {
        InventoryChanges deepclefInventoryChanges();
    }

    private LocalPlayer player;
    private Object level;
    private boolean initialSync;
    private Map<String, Integer> previous;

    private void scope(LocalPlayer current) {
        if (current != player || current.level() != level) {
            player = current;
            level = current.level();
            previous = null;
            initialSync = false;
        }
    }

    /** The login inventory is the starting quantity. Later full syncs must preserve unreported changes. */
    public void afterInventorySync(LocalPlayer current) {
        scope(current);
        if (!initialSync) {
            previous = snapshot(current.getInventory(), current.inventoryMenu, current.containerMenu);
            initialSync = true;
        }
    }

    public static String take(LocalPlayer player) {
        if (player == null) return "";
        InventoryChanges changes = ((Source) player.connection).deepclefInventoryChanges();
        changes.scope(player);
        return changes.advance(snapshot(player.getInventory(), player.inventoryMenu, player.containerMenu));
    }

    String advance(Map<String, Integer> now) {
        var before = previous;
        previous = now;
        if (before == null || before.equals(now)) return "";
        var names = new TreeSet<>(before.keySet());
        names.addAll(now.keySet());
        var lines = new java.util.StringJoiner("，");
        for (String name : names) {
            int count = now.getOrDefault(name, 0);
            int delta = count - before.getOrDefault(name, 0);
            if (delta != 0) lines.add(name + " " + (delta > 0 ? "+" : "") + delta + "（现有 " + count + "）");
        }
        return lines.length() == 0 ? "" : "【自上次观察以来的持有物品净变化】" + lines;
    }

    static Map<String, Integer> snapshot(Inventory inventory, InventoryMenu personal, AbstractContainerMenu active) {
        var counts = new HashMap<String, Integer>();
        for (int i = 0; i < inventory.getContainerSize(); ++i) add(counts, inventory.getItem(i));
        visitTemporary(personal, active, (location, stack) -> add(counts, stack));
        return counts;
    }

    static void visitTemporary(InventoryMenu personal, AbstractContainerMenu active, BiConsumer<String, ItemStack> visit) {
        visitGrid("个人2×2合成格", personal, visit);
        if (!active.getCarried().isEmpty()) visit.accept("当前光标", active.getCarried());
        if (active instanceof AbstractCraftingMenu grid && grid != personal) {
            visitGrid("当前工作台合成格", grid, visit);
        } else if (active instanceof MerchantMenu) {
            for (int i = 0; i < 2; i++) {
                var slot = active.getSlot(i);
                if (slot.hasItem()) visit.accept("交易输入槽" + i, slot.getItem());
            }
        }
    }

    private static void visitGrid(String label, AbstractCraftingMenu grid, BiConsumer<String, ItemStack> visit) {
        for (var slot : grid.getInputGridSlots()) {
            if (slot.hasItem()) visit.accept(label + " 槽" + slot.index, slot.getItem());
        }
    }

    private static void add(Map<String, Integer> counts, ItemStack stack) {
        if (!stack.isEmpty()) counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                stack.getCount(), Integer::sum);
    }
}
