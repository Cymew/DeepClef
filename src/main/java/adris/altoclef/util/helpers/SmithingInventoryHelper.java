package adris.altoclef.util.helpers;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** A dry inventory-capacity calculation; never edits or discards a real stack. */
public final class SmithingInventoryHelper {
    private SmithingInventoryHelper() { }

    /** The final unconsumed template remains available for later duplication. */
    public static int templateStockFor(int consumptions) {
        if (consumptions < 0) throw new IllegalArgumentException("Negative smithing consumption");
        return Math.addExact(consumptions, 1);
    }

    public static boolean canFit(List<ItemStack> inventory, List<ItemStack> returning) {
        List<ItemStack> simulated = new ArrayList<>();
        for (ItemStack stack : inventory) simulated.add(stack.copy());
        for (ItemStack stack : returning) {
            if (stack.isEmpty()) continue;
            int remaining = stack.getCount();
            for (ItemStack existing : simulated) {
                if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, stack)) continue;
                int moved = Math.min(remaining, Math.max(0, existing.getMaxStackSize() - existing.getCount()));
                existing.grow(moved); remaining -= moved;
            }
            for (int index = 0; remaining > 0 && index < simulated.size(); index++) {
                if (!simulated.get(index).isEmpty()) continue;
                int moved = Math.min(remaining, stack.getMaxStackSize());
                simulated.set(index, stack.copyWithCount(moved)); remaining -= moved;
            }
            if (remaining > 0) return false;
        }
        return true;
    }

    public static boolean consumedOnce(int[] before, int[] after) {
        if (before == null || after == null || before.length != 3 || after.length != 3) return false;
        for (int index = 0; index < 3; index++) if (before[index] < 1 || after[index] != before[index] - 1) return false;
        return true;
    }
}
