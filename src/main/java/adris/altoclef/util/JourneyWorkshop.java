package adris.altoclef.util;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.CraftGenericManuallyTask;
import adris.altoclef.tasks.construction.PlaceBlockTask;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasks.slot.MoveItemToSlotFromInventoryTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.slots.FurnaceSlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.BlockHitResult;
import java.util.List;
import java.util.Map;

/** One verified craft or smelt, at a witnessed station or a station from the player's own inventory. */
final class JourneyWorkshop extends SurvivalAction {
    private final Item output, fuel;
    private final Block station;
    private final BlockPos pos;
    private final CraftingRecipe recipe;
    private final int initial;
    private AbstractContainerMenu menu;
    private Task craft;
    private int nextOpen;
    private boolean fed;

    static JourneyWorkshop candidate(AltoClef mod, String wanted, List<BlockHitResult> surfaces, Map<String,Integer> stock) {
        var p = mod.getPlayer();
        if (p.getAbilities().instabuild || p.getInventory().getFreeSlot() < 0 || p.containerMenu != p.inventoryMenu
                || !p.inventoryMenu.getCarried().isEmpty() || p.inventoryMenu.getInputGridSlots().stream().anyMatch(slot -> slot.hasItem())) return null;
        boolean smelt = wanted.equals("minecraft:iron_ingot");
        Item output;
        CraftingRecipe recipe = null;
        Item fuel = Items.AIR;
        if (smelt) {
            if (!stock.containsKey("minecraft:raw_iron")) return null;
            fuel = p.getInventory().getNonEquipmentItems().stream()
                    .filter(s -> s.is(Items.COAL) || s.is(Items.CHARCOAL) || s.is(ItemTags.PLANKS) || s.is(ItemTags.LOGS))
                    .map(ItemStack::getItem).findFirst().orElse(Items.AIR);
            if (fuel == Items.AIR) return null;
            output = Items.IRON_INGOT;
        } else {
            var slots = new ItemTarget[9];
            if (wanted.equals("minecraft:wooden_pickaxe") || wanted.equals("minecraft:stone_pickaxe")) {
                boolean wood = wanted.equals("minecraft:wooden_pickaxe");
                if (stock.getOrDefault(wood ? PlayerProject.PLANKS : "minecraft:cobblestone", 0) < 3
                        || stock.getOrDefault(PlayerProject.STICKS, 0) < 2) return null;
                var material = wood ? new ItemTarget(p.getInventory().getNonEquipmentItems().stream()
                        .filter(s -> s.is(ItemTags.PLANKS)).map(ItemStack::getItem).distinct().toArray(Item[]::new))
                        : new ItemTarget(Items.COBBLESTONE);
                slots[0] = slots[1] = slots[2] = material;
                slots[4] = slots[7] = new ItemTarget(Items.STICK);
                output = wood ? Items.WOODEN_PICKAXE : Items.STONE_PICKAXE;
            } else if (wanted.equals("minecraft:furnace") && stock.getOrDefault("minecraft:cobblestone",0) >= 8) {
                java.util.Arrays.fill(slots, new ItemTarget(Items.COBBLESTONE));
                slots[4] = null;
                output = Items.FURNACE;
            } else if (wanted.equals("minecraft:beacon") && stock.getOrDefault("minecraft:glass",0) >= 5
                    && stock.getOrDefault("minecraft:obsidian",0) >= 3 && stock.containsKey("minecraft:nether_star")) {
                java.util.Arrays.fill(slots, new ItemTarget(Items.GLASS));
                slots[4] = new ItemTarget(Items.NETHER_STAR);
                slots[6] = slots[7] = slots[8] = new ItemTarget(Items.OBSIDIAN);
                output = Items.BEACON;
            } else return null;
            recipe = CraftingRecipe.newShapedRecipe(slots, 1);
        }
        Block station = smelt ? Blocks.FURNACE : Blocks.CRAFTING_TABLE;
        var place = surfaces.stream().filter(hit -> mod.level().getBlockState(hit.getBlockPos()).is(station))
                .min(java.util.Comparator.comparingDouble(hit -> hit.getLocation().distanceToSqr(p.position())))
                .map(BlockHitResult::getBlockPos);
        if (place.isEmpty() && p.getInventory().countItem(station.asItem()) > 0) {
            place = surfaces.stream().filter(hit -> hit.getDirection() == Direction.UP
                    && hit.getLocation().distanceTo(p.getEyePosition()) < p.blockInteractionRange()
                    && PlayerRoutine.validLanding(mod, hit.getBlockPos()) && mod.level().isEmptyBlock(hit.getBlockPos().above()))
                    .map(hit -> hit.getBlockPos().above()).findFirst();
        }
        return place.isEmpty() ? null : new JourneyWorkshop(output, fuel, station, place.get(), recipe, p.getInventory().countItem(output));
    }

    private JourneyWorkshop(Item output, Item fuel, Block station, BlockPos pos, CraftingRecipe recipe, int initial) {
        super("使用" + station.getName().getString() + "制作" + new ItemStack(output).getHoverName().getString());
        this.output = output; this.fuel = fuel; this.station = station; this.pos = pos; this.recipe = recipe; this.initial = initial;
    }
    @Override Item product() { return output; }

    @Override protected Task onTick(AltoClef mod) {
        var p = mod.getPlayer();
        if (p.getInventory().countItem(output) > initial) return finish("已确认产物入包");
        if (menu == null && p.containerMenu != p.inventoryMenu) {
            if (nextOpen == 0 || !(recipe == null ? p.containerMenu instanceof FurnaceMenu : p.containerMenu instanceof CraftingMenu))
                return finish("窗口已变化");
            menu = p.containerMenu;
        }
        if (menu == null) {
            if (!mod.level().getBlockState(pos).is(station)) {
                if (!mod.level().isEmptyBlock(pos) || p.getInventory().countItem(station.asItem()) == 0
                        || SceneObservation.surfaceHit(mod, pos.below(), false) == null) return finish("工位已变化");
                return new PlaceBlockTask(pos, station);
            }
            var hit = SceneObservation.surfaceHit(mod, pos, false);
            if (hit == null) return finish("工位离开视野");
            if (hit.getLocation().distanceTo(p.getEyePosition()) > p.blockInteractionRange())
                return new GetWithinRangeOfBlockTask(pos, 2, false);
            if (p.tickCount >= nextOpen) {
                mod.getController().useItemOn(p, InteractionHand.MAIN_HAND, hit);
                nextOpen = p.tickCount + 10;
            }
            return null;
        }
        if (menu != p.containerMenu) return finish("原工位窗口已关闭");
        if (recipe != null) {
            if (craft == null) {
                if (!menu.getCarried().isEmpty() || ((CraftingMenu)menu).getInputGridSlots().stream().anyMatch(slot -> slot.hasItem()))
                    return finish("现有合成格已被占用");
                craft = new CraftGenericManuallyTask(new RecipeTarget(output, initial + 1, recipe));
            }
            return craft;
        }
        if (!fed) {
            if (!menu.getSlot(0).getItem().isEmpty() || !menu.getSlot(1).getItem().isEmpty() || !menu.getSlot(2).getItem().isEmpty())
                return finish("熔炉中已有其他物资");
            fed = true;
        }
        var cursor = menu.getCarried();
        if (!cursor.isEmpty() && ((cursor.is(fuel) && (menu.getSlot(1).hasItem() || ((FurnaceMenu)menu).isLit()))
                || (cursor.is(Items.RAW_IRON) && menu.getSlot(0).hasItem()) || menu.getSlot(2).hasItem())) {
            if (mod.getSlotHandler().canDoSlotAction())
                mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, true).ifPresent(slot ->
                        mod.getSlotHandler().handleInventoryMouseClick(slot, 0, ContainerInput.PICKUP));
            return null;
        }
        if (menu.getSlot(2).getItem().is(output)) {
            if (mod.getSlotHandler().canDoSlotAction() && menu.getCarried().isEmpty())
                mod.getSlotHandler().handleInventoryMouseClick(FurnaceSlot.OUTPUT_SLOT, 0, ContainerInput.QUICK_MOVE);
            return null;
        }
        if (!menu.getSlot(0).hasItem()) return new MoveItemToSlotFromInventoryTask(new ItemTarget(Items.RAW_IRON, 1), FurnaceSlot.INPUT_SLOT_MATERIALS);
        if (!((FurnaceMenu)menu).isLit() && !menu.getSlot(1).hasItem())
            return new MoveItemToSlotFromInventoryTask(new ItemTarget(fuel, 1), FurnaceSlot.INPUT_SLOT_FUEL);
        return null;
    }
    @Override protected void onStop(AltoClef mod, Task interrupt) {
        if (menu != null && mod.getPlayer() != null && mod.getPlayer().containerMenu == menu) mod.getPlayer().closeContainer();
    }
}
