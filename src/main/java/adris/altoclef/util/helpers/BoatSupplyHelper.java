package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.CarriedBoatCraftTask;
import adris.altoclef.tasksystem.Task;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Arrays;

/** Opportunistic crafting from carried materials, without a wood-gathering detour. */
public final class BoatSupplyHelper {
    private BoatSupplyHelper() { }

    public static Task craftFromCarriedPlanks(AltoClef mod) {
        if (!mod.getItemStorage().hasEmptyInventorySlot()) return null;
        boolean table = mod.getItemStorage().hasItemInventoryOnly(Items.CRAFTING_TABLE);
        for (ItemHelper.WoodItems wood : ItemHelper.getWoodItems()) {
            if (wood.boat == null || wood.planks == null
                    || Arrays.stream(BoatPlacementHelper.ordinaryBoats()).noneMatch(item -> item == wood.boat)) continue;
            int count = mod.getItemStorage().getItemCountInventoryOnly(wood.planks);
            if (canCraft(count, table)) {
                return new CarriedBoatCraftTask(wood.boat, wood.planks);
            }
        }
        return null;
    }

    // Five matching planks for the native Java boat/raft recipe; reserve four more
    // when a crafting table also has to be made. Never count nearby containers/logs.
    static boolean canCraft(int matchingPlanks, boolean hasTable) {
        return matchingPlanks >= (hasTable ? 5 : 9);
    }
}
