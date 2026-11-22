package adris.altoclef.util;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.container.SmeltInFurnaceTask;
import adris.altoclef.tasks.resources.CollectCobblestoneTask;
import adris.altoclef.tasks.resources.CollectCobbledDeepslateTask;
import adris.altoclef.tasks.resources.CollectIronIngotTask;
import adris.altoclef.tasks.resources.CollectPlanksTask;
import adris.altoclef.tasks.resources.CollectSticksTask;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasksystem.Task;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.item.ItemEntity;
import java.util.List;

/** One player decision owns the pickup and the native get until the inventory target is met. */
final class PlayerGather extends SurvivalAction {
    private final PlayerProject.Milestone order;
    private final ResourceTask resource;
    private final Pickup pickup;
    private net.minecraft.world.inventory.AbstractFurnaceMenu smeltingMenu;
    private boolean quotaMet;
    private int highWater = -1;
    private boolean progressed;

    boolean takeProgress() {
        boolean changed = progressed;
        progressed = false;
        return changed;
    }

    private PlayerGather(PlayerProject.Milestone order, ResourceTask resource, ItemEntity firstDrop) {
        super("批量获取 " + order.item() + " 至背包共 " + order.count() + " 个");
        this.order = order;
        this.resource = resource;
        resource.leaveStoredOutput();
        pickup = firstDrop == null ? null : new Pickup(firstDrop);
    }

    static PlayerGather candidate(AltoClef mod, PlayerProject.Milestone order, List<ItemEntity> drops) {
        var nativeGet = nativeTask(order);
        if (nativeGet == null) return null;
        var firstDrop = drops.stream().filter(drop -> matchesProduct(drop.getItem().getItem(), order.item())
                && canCarry(mod.getPlayer(), drop)).findFirst().orElse(null);
        return new PlayerGather(order, nativeGet, firstDrop);
    }

    static ResourceTask nativeTask(PlayerProject.Milestone order) {
        if (order == null) return null;
        String key = order.item();
        if (key.equals(PlayerProject.LOGS) || key.equals(PlayerProject.PLANKS)) {
            String family = key.equals(PlayerProject.LOGS) ? "log" : "planks";
            return TaskCatalogue.getItemTask(family, order.count());
        }
        var id = Identifier.tryParse(key);
        var item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null || !TaskCatalogue.taskExists(item)) return null;
        var task = TaskCatalogue.getItemTask(item, order.count());
        // Offer ordinary mining and production. Animal interactions retain their own method.
        if (key.equals("minecraft:blaze_rod") || key.equals("minecraft:ender_pearl") || task instanceof MineAndCollectTask || task instanceof CraftInInventoryTask
                || task instanceof CraftInTableTask || task instanceof SmeltInFurnaceTask
                || task instanceof CollectCobblestoneTask || task instanceof CollectCobbledDeepslateTask
                || task instanceof CollectIronIngotTask || task instanceof CollectPlanksTask
                || task instanceof CollectSticksTask) return task;
        return null;
    }

    @Override protected Task onTick(AltoClef mod) {
        int count = held(mod);
        if (count > highWater) {
            highWater = count;
            progressed = true;
        }
        if (resource.isActive() && mod.getPlayer().containerMenu instanceof net.minecraft.world.inventory.AbstractFurnaceMenu furnace)
            smeltingMenu = furnace;
        if (count >= order.count()) {
            if (smeltingMenu != null && mod.getPlayer().containerMenu == smeltingMenu)
                mod.getPlayer().closeContainer();
            return finish("已达到目标库存");
        }
        if (pickup != null && !pickup.done) return pickup;
        if (resource.isFinished(mod)) return finish("内置获取已结束，按随身库存重新核对");
        return resource;
    }

    private int held(AltoClef mod) {
        return mod.getPlayer().getInventory().getNonEquipmentItems().stream()
                .filter(s -> !s.isEmpty() && matchesProduct(s.getItem(), order.item()))
                .mapToInt(s -> s.getCount()).sum();
    }

    @Override String receipt(AltoClef mod) {
        int count = held(mod);
        quotaMet = count >= order.count();
        result = (quotaMet ? "批量获取达标 " : "批量获取未达标 ") + order.item() + " " + count + "/" + order.count()
                + "，尚缺 " + Math.max(0, order.count() - count);
        return super.receipt(mod);
    }

    @Override boolean retryLater() { return !quotaMet; }
}
