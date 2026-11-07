package adris.altoclef.mining;

import adris.altoclef.control.MiningWaterControl;
import adris.altoclef.control.SlotHandler;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import java.util.List;

/** Real stack scoring and native inventory, plus deterministic water-control replay. */
public final class MiningRegression {
    private static int checks;
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var registries = VanillaRegistries.createWorldLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(pending -> pending.apply());
        for (int start = 0; start < 9; start++) {
            for (int best = 0; best < 36; best++) {
                if (best == start) continue;
                Inventory inventory = new Inventory(null, null);
                inventory.setSelectedSlot(start);
                inventory.setItem(start, axe(Items.WOODEN_AXE, 2));
                inventory.setItem(best, axe(Items.IRON_AXE, 6));
                int[] swaps = {0};
                for (int tick = 0; tick < 100; tick++) {
                    int selected = StorageHelper.getBestMiningSlot(inventory.getNonEquipmentItems(),
                            inventory.getSelectedSlot(), Blocks.OAK_LOG.defaultBlockState());
                    check(SlotHandler.equipMiningTool(inventory, selected, hotbar -> {
                        swaps[0]++;
                        ItemStack old = inventory.getItem(hotbar);
                        inventory.setItem(hotbar, inventory.getItem(selected));
                        inventory.setItem(selected, old);
                    }), "equip succeeds");
                    check(inventory.getSelectedItem().is(Items.IRON_AXE), "fastest axe stays held on every tick");
                }
                check(swaps[0] == (best < 9 ? 0 : 1), "hotbar selection never swaps; backpack moves exactly once");
            }
        }
        Inventory inventory = new Inventory(null, null);
        inventory.setSelectedSlot(5);
        inventory.setItem(5, axe(Items.IRON_AXE, 6));
        inventory.setItem(0, axe(Items.IRON_AXE, 6));
        check(best(inventory) == 5, "equal speed keeps selected stack, regardless of scan order");
        ItemStack enchanted = axe(Items.IRON_AXE, 6);
        enchanted.enchant(registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY), 3);
        inventory.setItem(20, enchanted);
        check(best(inventory) == 20, "same item with efficiency is selected by exact stack");
        check(!SlotHandler.equipMiningTool(inventory, 20, hotbar -> {}), "unconfirmed inventory swap is not success");
        check(best(inventory) == 20, "failed swap retries the same stack");
        inventory.setItem(20, ItemStack.EMPTY);
        inventory.setItem(5, ItemStack.EMPTY);
        check(best(inventory) == 0, "broken held tool selects the remaining tool");
        check(!SlotHandler.equipMiningTool(inventory, 40, hotbar -> {throw new AssertionError();}), "offhand rejected");
        water();
        DirtPitRegression.run();
        System.out.println("Mining regression checks passed: " + checks);
    }

    private static int best(Inventory inventory) {
        return StorageHelper.getBestMiningSlot(inventory.getNonEquipmentItems(), inventory.getSelectedSlot(),
                Blocks.OAK_LOG.defaultBlockState());
    }

    private static ItemStack axe(Item item, float speed) {
        ItemStack stack = new ItemStack(item);
        // Explicit block rules avoid reliance on a live world's loaded tag bindings.
        stack.set(DataComponents.TOOL, new Tool(List.of(Tool.Rule.minesAndDrops(
                HolderSet.direct(Blocks.OAK_LOG.builtInRegistryHolder()), speed)), 1, 1, true));
        return stack;
    }

    private static void water() {
        for (double initialVelocity : new double[]{-.1, 0, .1}) {
            MiningWaterControl control = new MiningWaterControl();
            double y = 64, velocity = initialVelocity;
            int continuousMining = 0;
            for (int tick = 0; tick < 600; tick++) {
                boolean visible = Math.abs(y - 64) < .4;
                control.update(true, visible, y);
                check(control.isActive(), "water mining stance remains stable");
                if (control.shouldJump(y, velocity)) velocity += .04;
                y += velocity;
                velocity = velocity * .8 - .005; // native unsprinted vertical water integration
                check(Math.abs(y - 64) < .4, "no sink/ascent loop across the visibility boundary");
                continuousMining = visible ? continuousMining + 1 : 0;
            }
            check(continuousMining == 600, "long underwater break retains uninterrupted visibility");
            for (int tick = 0; tick < 20; tick++) control.update(true, false, y);
            check(control.isActive(), "temporary occlusion preserves anchor");
            control.update(true, false, y);
            check(!control.isActive(), "persistent obstruction releases stance for replanning");
            control.update(true, true, y);
            control.update(false, true, y);
            check(!control.shouldJump(y, -.1), "leaving water releases jump");
            control.update(true, true, y);
            control.reset();
            check(!control.isActive() && !control.shouldJump(y, -.1), "stop and interruption release float");
        }
        check(!MiningWaterControl.needsAir(true, false, 299, 300), "one lost air tick cannot interrupt mining");
        check(!MiningWaterControl.needsAir(true, false, 91, 300), "usable air permits mining");
        check(MiningWaterControl.needsAir(true, false, 90, 300), "low air still preempts mining");
        check(MiningWaterControl.needsAir(true, true, 299, 300), "escape continues until full air");
        check(!MiningWaterControl.needsAir(true, true, 300, 300), "full air resumes mining");
        check(MiningWaterControl.needsAir(false, false, 299, 300), "non-mining survival unchanged");
    }

    private static void check(boolean ok, String description) {
        checks++;
        if (!ok) throw new AssertionError(description);
    }
}
