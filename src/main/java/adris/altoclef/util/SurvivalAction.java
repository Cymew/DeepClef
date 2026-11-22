package adris.altoclef.util;

import adris.altoclef.AltoClef;
import baritone.api.pathing.goals.GoalBlock;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.slots.PlayerSlot;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Short self-chosen actions. Resource targets come from lit first hits, visible entities or carried ingredients. */
abstract class SurvivalAction extends Task {
    private final String label;
    private final InventoryChanges inventory = new InventoryChanges();
    private String before;
    String result = "动作结束，尚未确认效果";
    boolean done;
    private boolean inventoryChanged;
    boolean retryLater() { return !inventoryChanged; }

    SurvivalAction(String label) { this.label = label; }
    String label() { return label; }
    boolean isMeal() { return false; }
    boolean singleBlock() { return false; }
    net.minecraft.world.item.Item product() { return Items.AIR; }

    static String productKey(net.minecraft.world.item.Item item) {
        if (item.builtInRegistryHolder().is(ItemTags.LOGS)) return PlayerProject.LOGS;
        if (item.builtInRegistryHolder().is(ItemTags.PLANKS)) return PlayerProject.PLANKS;
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    static Map<String,Integer> supplies(LocalPlayer p) {
        Map<String,Integer> result = new HashMap<>();
        for (var stack : p.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) continue;
            var exact = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            result.merge(exact, stack.getCount(), Integer::sum);
            var family = productKey(stack.getItem());
            if (!family.equals(exact)) result.merge(family, stack.getCount(), Integer::sum);
        }
        return result;
    }
    static boolean lowAir(boolean submerged, int air, int maximum) {
        return submerged && maximum > 0 && air <= maximum / 3;
    }
    static boolean night(boolean overworld, long clock) {
        int time = Math.floorMod(clock, 24000);
        return overworld && time >= 11500 && time < 23000;
    }
    static String vitals(LocalPlayer p) {
        return "生命 " + p.getHealth() + "/" + p.getMaxHealth() + "，饥饿 " + p.getFoodData().getFoodLevel()
                + "/20，氧气 " + p.getAirSupply() + "/" + p.getMaxAirSupply();
    }
    void begin(LocalPlayer p) {
        before = vitals(p);
        inventory.advance(InventoryChanges.snapshot(p.getInventory(), p.inventoryMenu, p.containerMenu));
    }
    String receipt(AltoClef mod) {
        var p = mod.getPlayer();
        var delta = inventory.advance(InventoryChanges.snapshot(p.getInventory(), p.inventoryMenu, p.containerMenu));
        inventoryChanged = !delta.isEmpty();
        return result + "。开始时 " + before + "；现在 " + vitals(p) + "。"
                + (delta.isEmpty() ? "持有物品无净变化" : delta);
    }
    Task finish(String message) { result = message; done = true; return null; }
    @Override public boolean isFinished(AltoClef mod) { return done; }
    @Override protected boolean isEqual(Task other) { return other == this; }
    @Override protected String toDebugString() { return label(); }
    @Override protected void onStart(AltoClef mod) {}
    @Override protected void onStop(AltoClef mod, Task interrupt) {}

    static List<SurvivalAction> candidates(AltoClef mod, boolean hurt, List<BlockHitResult> surroundings, String wanted, List<ItemEntity> visibleDrops) {
        var p = mod.getPlayer();
        var options = new ArrayList<SurvivalAction>();
        if (p.getFoodData().getFoodLevel() < 20 && mod.getModSettings().isAutoEat() && mod.getFoodChain().hasMeal(mod))
            options.add(new Recovery(true));
        boolean overworld = mod.level().dimension() == Level.OVERWORLD;
        if (hurt || p.getHealth() < p.getMaxHealth() || night(overworld, overworld ? mod.level().getOverworldClockTime() : 0))
            options.add(new Recovery(false));
        if (p.getAbilities().instabuild || p.containerMenu != p.inventoryMenu) return options;
        visibleDrops.stream().filter(item -> canCarry(p, item)).sorted(Comparator.comparing(item ->
                !matchesProduct(item.getItem().getItem(), wanted))).limit(4).forEach(item -> options.add(new Pickup(item)));
        surroundings.stream().filter(hit -> p.getEyePosition().distanceTo(hit.getLocation()) <= p.blockInteractionRange())
                .filter(hit -> harvestHit(hit, face -> SceneObservation.lit(mod, face), pos -> harvestable(mod, pos)) != null)
                .sorted(Comparator.comparingDouble(hit -> p.getEyePosition().distanceToSqr(hit.getLocation())))
                .limit(3).forEach(hit -> {
                    var pos = hit.getBlockPos();
                    options.add(new Harvest(pos, mod.level().getBlockState(pos).getBlock()));
                });
        addRecipes(mod, options, wanted);
        return options;
    }

    // Only hand the actual first hit to the resource classifier, after checking the exposed face.
    static BlockPos harvestHit(BlockHitResult hit, java.util.function.Predicate<BlockPos> lit,
                               java.util.function.Predicate<BlockPos> harvestable) {
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        var pos = hit.getBlockPos();
        return lit.test(pos.relative(hit.getDirection())) && harvestable.test(pos) ? pos : null;
    }

    static boolean needsStarterWood(LocalPlayer p) {
        return !p.getAbilities().instabuild && p.getInventory().getFreeSlot() >= 0
                && p.getInventory().getNonEquipmentItems().stream().noneMatch(stack -> stack.is(ItemTags.LOGS)
                || stack.is(ItemTags.PLANKS) || stack.is(ItemTags.AXES) || stack.is(ItemTags.PICKAXES)
                || stack.is(Items.CRAFTING_TABLE));
    }

    static boolean harvestable(AltoClef mod, BlockPos pos) {
        var p = mod.getPlayer();
        if (pos.equals(p.blockPosition().below()) || mod.getExtraBaritoneSettings().shouldAvoidBreaking(pos)) return false;
        BlockState state = mod.level().getBlockState(pos);
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return (state.is(BlockTags.LOGS) || path.endsWith("_ore") || state.is(Blocks.STONE))
                && state.getDestroySpeed(mod.level(), pos) >= 0 && (p.hasCorrectToolForDrops(state) || p.getInventory().getNonEquipmentItems().stream()
                    .anyMatch(stack -> !stack.isEmpty() && stack.isCorrectToolForDrops(state)));
    }

    static List<ItemEntity> visibleDrops(AltoClef mod) {
        var p = mod.getPlayer();
        return mod.level().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(SceneObservation.NEAR),
                item -> visibleDrop(mod, item)).stream().sorted(Comparator.comparingDouble(p::distanceToSqr)).toList();
    }

    private static boolean visibleDrop(AltoClef mod, ItemEntity item) {
        var p = mod.getPlayer();
        if (!item.isAlive() || item.isRemoved() || item.getItem().isEmpty() || item.level() != mod.level()
                || p.distanceTo(item) > Math.min(SceneObservation.NEAR, SceneObservation.range(Minecraft.getInstance()))
                || !SceneObservation.lit(mod, item.blockPosition())) return false;
        var box = item.getBoundingBox();
        var center = box.getCenter();
        var eye = p.getEyePosition();
        var body = net.minecraft.world.phys.shapes.CollisionContext.of(p);
        return SceneObservation.dropLineVisible(mod.level(), eye, center, mod.level().getChunkSource()::hasChunk, body)
                || SceneObservation.dropLineVisible(mod.level(), eye, new Vec3(center.x, box.maxY - .01, center.z),
                        mod.level().getChunkSource()::hasChunk, body);
    }

    static boolean canCarry(LocalPlayer p, ItemEntity item) {
        return p.getInventory().getFreeSlot() >= 0 || p.getInventory().getNonEquipmentItems().stream()
                .anyMatch(stack -> ItemStack.isSameItemSameComponents(stack, item.getItem()) && stack.getCount() < stack.getMaxStackSize());
    }

    static final class Pickup extends SurvivalAction {
        private final ItemEntity target;
        private GoalBlock leg;
        private boolean rules;
        private BlockPos lastSeen;
        private int seenAt, startedAt, initial;
        private final net.minecraft.world.item.Item expected;
        @Override net.minecraft.world.item.Item product() { return expected; }
        Pickup(ItemEntity target) {
            super("拾取目击的" + target.getItem().getHoverName().getString() + " " + target.blockPosition().toShortString());
            this.target = target;
            expected = target.getItem().getItem();
            lastSeen = target.blockPosition();
        }
        @Override protected Task onTick(AltoClef mod) {
            var p = mod.getPlayer();
            if (startedAt == 0) { startedAt = seenAt = p.tickCount; initial = p.getInventory().countItem(expected); }
            if (p.getInventory().countItem(expected) > initial) return finish("已确认掉落物入包");
            if (!target.isAlive() || target.isRemoved()) return finish("原掉落物已消失，以实际背包变化确认拾取");
            if (visibleDrop(mod, target)) { lastSeen = target.blockPosition(); seenAt = p.tickCount; }
            if (p.tickCount - seenAt > 30 || p.tickCount - startedAt > 160 || !canCarry(p, target))
                return finish("已目击掉落物，本次未确认入包");
            if (!rules) {
                mod.getBehaviour().push();
                mod.getBehaviour().avoidBlockBreaking(pos -> true);
                mod.getBehaviour().avoidBlockPlacing(pos -> true);
                rules = true;
            }
            var feet = lastSeen;
            if (mod.getPlayer().blockPosition().equals(feet)) return null;
            var next = new GoalBlock(feet);
            var process = mod.getClientBaritone().getCustomGoalProcess();
            if (!next.equals(leg)) {
                leg = next;
                process.setGoalAndPath(leg);
            } else if (!process.isActive()) {
                return finish("无法继续接近原掉落物，结束本次拾取");
            }
            return null;
        }
        @Override protected void onStop(AltoClef mod, Task interrupt) {
            var process = mod.getClientBaritone().getCustomGoalProcess();
            if (leg != null && leg.equals(process.getGoal())) process.onLostControl();
            leg = null;
            if (rules) mod.getBehaviour().pop();
            rules = false;
        }
    }

    static SurvivalAction harvest(BlockPos pos, Block block) { return new Harvest(pos, block); }

    private static final class Harvest extends SurvivalAction {
        private final BlockPos pos;
        private final Block block;
        private boolean started;
        private int brokenAt = -1, initial = -1;
        private Pickup pickup;
        @Override net.minecraft.world.item.Item product() {
            if (block == Blocks.STONE) return Items.COBBLESTONE;
            if (block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE) return Items.RAW_IRON;
            return block.asItem();
        }
        @Override boolean singleBlock() { return true; }
        Harvest(BlockPos pos, Block block) {
            super("采集眼前的" + block.getName().getString() + " " + pos.toShortString());
            this.pos = pos; this.block = block;
        }
        private void attack(AltoClef mod, boolean pressed) {
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, pressed);
        }
        @Override protected Task onTick(AltoClef mod) {
            var p = mod.getPlayer();
            if (initial < 0) initial = p.getInventory().countItem(product());
            if (brokenAt >= 0 || !mod.level().getBlockState(pos).is(block)) {
                attack(mod, false);
                if (p.getInventory().countItem(product()) > initial) return finish("已确认采集产物入包");
                if (!started) return finish("开始前原方块已变化");
                if (brokenAt < 0) brokenAt = p.tickCount;
                if ((pickup != null && pickup.done) || p.tickCount - brokenAt > 75) return finish("原方块已消失，收获取决于实际背包变化");
                if (pickup == null) {
                    var near = visibleDrops(mod);
                    near.stream().filter(item -> item.getItem().is(product()) && canCarry(p, item)).min(Comparator.comparingDouble(p::distanceToSqr)).ifPresent(item -> pickup = new Pickup(item));
                    if (pickup == null && p.tickCount - brokenAt > 25) return finish("方块已挖开，尚未确认产物入包");
                }
                return pickup;
            }
            var hit = SceneObservation.surfaceHit(mod, pos, false);
            if (hit == null || p.getEyePosition().distanceTo(hit.getLocation()) > p.blockInteractionRange()
                    || !harvestable(mod, pos)) {
                attack(mod, false);
                return finish("原目标不可见、超出触及范围或工具已变化");
            }
            var state = mod.level().getBlockState(pos);
            if (!p.hasCorrectToolForDrops(state)) {
                attack(mod, false);
                p.getInventory().getNonEquipmentItems().stream().filter(stack -> !stack.isEmpty() && stack.isCorrectToolForDrops(state))
                        .findFirst().ifPresent(stack -> mod.getSlotHandler().forceEquipItem(stack.getItem()));
                return null;
            }
            LookHelper.lookAt(mod, hit.getLocation());
            boolean aimed = LookHelper.isLookingAt(mod, pos);
            attack(mod, aimed);
            started |= aimed;
            return null;
        }
        @Override protected void onStop(AltoClef mod, Task interrupt) { attack(mod, false); }
    }

    private static final class Recovery extends SurvivalAction {
        private final boolean meal;
        private int until, hunger;
        private float health;
        Recovery(boolean meal) {
            super(meal ? "用随身可食用物进食" : "原地短暂休整并留意伤势和天色");
            this.meal = meal;
        }
        @Override boolean isMeal() { return meal; }
        @Override void begin(LocalPlayer p) {
            super.begin(p);
            until = p.tickCount + (meal ? 140 : 80);
            hunger = p.getFoodData().getFoodLevel();
            health = p.getHealth();
        }
        @Override protected Task onTick(AltoClef mod) {
            var p = mod.getPlayer();
            if (meal && p.getFoodData().getFoodLevel() > hunger) return finish("已观察到饥饿值回升");
            if (p.getHealth() < health) return finish("再次受伤，结束休整并重新观察");
            return p.tickCount >= until ? finish(meal ? "本次未观察到饥饿值回升" : "已在原地休整片刻") : null;
        }
    }

    private static boolean clearGrid(LocalPlayer p) {
        return p.containerMenu == p.inventoryMenu && p.inventoryMenu.getCarried().isEmpty()
                && p.inventoryMenu.getInputGridSlots().stream().noneMatch(slot -> slot.hasItem())
                && !p.inventoryMenu.getSlot(0).hasItem();
    }
    private static void addRecipes(AltoClef mod, List<SurvivalAction> options, String wanted) {
        var p = mod.getPlayer();
        if (!clearGrid(p) || p.getInventory().getFreeSlot() < 0) return;
        var contents = new StackedItemContents();
        p.getInventory().fillStackedContents(contents);
        var context = SlotDisplayContext.fromLevel(mod.level());
        var products = new HashSet<net.minecraft.world.item.Item>();
        for (var collection : p.getRecipeBook().getCollections()) {
            for (var entry : collection.getRecipes()) {
                if (!RuntimeRecipes.fits(entry.display(), 2, 2) || entry.craftingRequirements().isEmpty() || !entry.canCraft(contents)) continue;
                for (var stack : entry.display().result().resolveForStacks(context)) {
                    if (!(matchesProduct(stack.getItem(), wanted) || stack.is(ItemTags.PLANKS) || stack.is(Items.STICK) || stack.is(Items.CRAFTING_TABLE) || stack.is(Items.TORCH))) continue;
                    if (!matchesProduct(stack.getItem(), wanted) && p.getInventory().countItem(stack.getItem()) >= stack.getCount()) continue;
                    if (!products.add(stack.getItem())) continue;
                    options.add(new Craft(entry, stack.copy(), null));
                    break;
                }

            }
        }
        if (!wanted.isEmpty() && products.stream().noneMatch(item -> matchesProduct(item, wanted)))
            addCarriedRecipe(p, options, wanted);
    }

    static boolean matchesProduct(net.minecraft.world.item.Item item, String wanted) {
        return productKey(item).equals(wanted) || BuiltInRegistries.ITEM.getKey(item).toString().equals(wanted);
    }

    // Use only the carried ingredients for these known vanilla 2x2 layouts.
    private static void addCarriedRecipe(LocalPlayer p, List<SurvivalAction> options, String wanted) {
        var held = p.getInventory().getNonEquipmentItems();
        for (var stack : held) {
            var plank = adris.altoclef.util.helpers.ItemHelper.logToPlanks(stack.getItem());
            if (plank != null && matchesProduct(plank, wanted)) {
                var recipe = CraftingRecipe.newShapedRecipe(new ItemTarget[]{new ItemTarget(stack.getItem()), null, null, null}, 4);
                options.add(new Craft(null, new ItemStack(plank, 4), recipe));
                return;
            }
        }
        ItemStack output = switch (wanted) {
            case "minecraft:blaze_powder" -> new ItemStack(Items.BLAZE_POWDER, 2);
            case "minecraft:ender_eye" -> new ItemStack(Items.ENDER_EYE);
            case "minecraft:flint_and_steel" -> new ItemStack(Items.FLINT_AND_STEEL);
            default -> ItemStack.EMPTY;
        };
        if (!output.isEmpty()) {
            var first = output.is(Items.BLAZE_POWDER) ? Items.BLAZE_ROD : output.is(Items.ENDER_EYE) ? Items.ENDER_PEARL : Items.FLINT;
            var second = output.is(Items.ENDER_EYE) ? Items.BLAZE_POWDER : Items.IRON_INGOT;
            if (p.getInventory().countItem(first) > 0 && (output.is(Items.BLAZE_POWDER) || p.getInventory().countItem(second) > 0)) {
                var inputs = new ItemTarget[]{new ItemTarget(first), output.is(Items.BLAZE_POWDER) ? null : new ItemTarget(second), null, null};
                options.add(new Craft(null, output, CraftingRecipe.newShapedRecipe(inputs, output.getCount())));
            }
            return;
        }
        boolean table = PlayerProject.TABLE.equals(wanted);
        if (!table && !PlayerProject.STICKS.equals(wanted)) return;
        var boards = held.stream().filter(stack -> stack.is(ItemTags.PLANKS)).toList();
        if (boards.stream().mapToInt(ItemStack::getCount).sum() < (table ? 4 : 2)) return;
        var material = new ItemTarget(boards.stream().map(ItemStack::getItem).distinct().toArray(net.minecraft.world.item.Item[]::new));
        var recipe = CraftingRecipe.newShapedRecipe(new ItemTarget[]{material, table ? material : null, material, table ? material : null}, table ? 1 : 4);
        options.add(new Craft(null, table ? new ItemStack(Items.CRAFTING_TABLE) : new ItemStack(Items.STICK, 4), recipe));
    }

    private static final class Craft extends SurvivalAction {
        @Override net.minecraft.world.item.Item product() { return expected.getItem(); }
        private final RecipeDisplayEntry entry;
        private final ItemStack expected;
        private InventoryScreen screen;
        private boolean placed, clicked;
        private int initial, deadline;
        private final CraftingRecipe layout;
        private Task manual;
        Craft(RecipeDisplayEntry entry, ItemStack expected, CraftingRecipe layout) {
            super("用随身材料制作一次" + expected.getHoverName().getString() + " ×" + expected.getCount());
            this.entry = entry; this.expected = expected; this.layout = layout;
        }
        @Override protected Task onTick(AltoClef mod) {
            var p = mod.getPlayer();
            var mc = Minecraft.getInstance();
            if (!placed) {
                var contents = new StackedItemContents();
                p.getInventory().fillStackedContents(contents);
                if (mc.gui.screen() != null || !clearGrid(p) || (entry != null && !entry.canCraft(contents)) || p.getInventory().getFreeSlot() < 0)
                    return finish("配方、随身材料或操作界面已变化");
                initial = p.getInventory().countItem(expected.getItem());
                deadline = p.tickCount + 60;
                screen = new InventoryScreen(p);
                mc.gui.setScreen(screen);
                if (entry != null) mod.getController().handlePlaceRecipe(p.inventoryMenu.containerId, entry.id(), false);
                else manual = new adris.altoclef.tasks.CraftGenericManuallyTask(
                        new RecipeTarget(expected.getItem(), initial + expected.getCount(), layout));
                placed = true;
                return null;
            }
            if (mc.gui.screen() != screen || p.containerMenu != p.inventoryMenu) return finish("制作窗口已变化");
            if ((clicked || manual != null) && p.getInventory().countItem(expected.getItem()) > initial) return finish("已在背包观察到配方产物");
            if (p.tickCount >= deadline) return finish("等待配方或产物同步超时");
            if (manual != null) return manual;
            var output = p.inventoryMenu.getSlot(0).getItem();
            if (!clicked && ItemStack.isSameItemSameComponents(output, expected) && output.getCount() == expected.getCount()
                    && mod.getSlotHandler().canDoSlotAction()) {
                mod.getSlotHandler().handleInventoryMouseClick(PlayerSlot.CRAFT_OUTPUT_SLOT, 0, ContainerInput.QUICK_MOVE);
                clicked = true;
            }
            return null;
        }
        @Override protected void onStop(AltoClef mod, Task interrupt) {
            if (screen != null && Minecraft.getInstance().gui.screen() == screen) screen.onClose();
        }
    }
}
