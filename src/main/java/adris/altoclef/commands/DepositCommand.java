package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.*;
import adris.altoclef.tasks.container.StoreInAnyContainerTask;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import org.apache.commons.lang3.ArrayUtils;

public class DepositCommand extends Command {
    public DepositCommand() throws CommandException {
        super("deposit", "Deposit ALL of our items", new Arg(ItemList.class, "items (empty for ALL non gear items)", null, 0, false));
    }

    public static ItemTarget[] getAllNonEquippedOrToolItemsAsTarget(AltoClef mod) {
        return StorageHelper.getAllInventoryItemsAsTargets(slot -> {
            // Ignore armor
            if (ArrayUtils.contains(PlayerSlot.ARMOR_SLOTS, slot))
                return false;
            ItemStack stack = StorageHelper.getItemStackInSlot(slot);
            // Ignore tools
            if (!stack.isEmpty()) {
                Item item = stack.getItem();
                return !(item.components().has(DataComponents.TOOL));
            }
            return false;
        });
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String[] args = parser.getArgUnits();
        if ((args.length == 1 && !args[0].startsWith("[")) || (args.length == 2 && args[1].equalsIgnoreCase("all"))) {
            var selection = ItemList.parseRemainder(args[0]).items[0];
            var targets = StorageHelper.getAllInventoryItemsAsTargets(slot ->
                    selection.matches(StorageHelper.getItemStackInSlot(slot).getItem()));
            if (targets.length == 0) { mod.log("背包内没有该物品"); finish(); return; }
            mod.runUserTask(new StoreInAnyContainerTask(false, targets), completion());
            return;
        }
        ItemList itemList = parser.get(ItemList.class);
        ItemTarget[] items;
        if (itemList == null) {
            items = getAllNonEquippedOrToolItemsAsTarget(mod);
        } else {
            items = itemList.items;
        }

        mod.runUserTask(new StoreInAnyContainerTask(false, items), this::finish);
    }
}
