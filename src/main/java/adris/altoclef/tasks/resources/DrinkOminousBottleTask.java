package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import baritone.api.utils.input.Input;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Items;

/** Uses the native consumable and waits for a server-observed omen, rather than inventing an effect. */
final class DrinkOminousBottleTask extends Task {
    @Override protected void onStart(AltoClef mod) { }
    @Override protected Task onTick(AltoClef mod) {
        if (isFinished(mod)) return null;
        if (!mod.getItemStorage().hasItemInventoryOnly(Items.OMINOUS_BOTTLE)) return new OminousBottleAcquisitionTask(1);
        if (mod.getFoodChain().isTryingToEat()) { mod.getInputControls().release(Input.CLICK_RIGHT); return null; }
        if (mod.getPlayer().containerMenu != mod.getPlayer().inventoryMenu) return null;
        mod.getClientBaritone().getPathingBehavior().requestPause();
        if (!mod.getSlotHandler().forceEquipItem(Items.OMINOUS_BOTTLE) || !mod.getPlayer().getMainHandItem().is(Items.OMINOUS_BOTTLE)) return null;
        if (!mod.getPlayer().isUsingItem()) mod.getController().useItem(mod.getPlayer(), InteractionHand.MAIN_HAND);
        mod.getInputControls().hold(Input.CLICK_RIGHT);
        return null;
    }
    @Override protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getInputControls().release(Input.CLICK_RIGHT);
        if (mod.getPlayer() != null && mod.getPlayer().isUsingItem() && mod.getPlayer().getUseItem().is(Items.OMINOUS_BOTTLE)) mod.getController().releaseUsingItem(mod.getPlayer());
    }
    @Override public boolean isFinished(AltoClef mod) { return mod != null && (mod.getPlayer().hasEffect(MobEffects.BAD_OMEN) || mod.getPlayer().hasEffect(MobEffects.TRIAL_OMEN)); }
    @Override protected boolean isEqual(Task other) { return other instanceof DrinkOminousBottleTask; }
    @Override protected String toDebugString() { return "Drink ominous bottle for an ominous trial"; }
}
