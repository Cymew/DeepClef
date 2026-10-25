package adris.altoclef.mixins;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.SlotClickChangedEvent;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.core.NonNullList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;

/**
 * In 1.21 AbstractContainerMenu had two methods: clicked() (dispatcher) and
 * slotClick() (handler); the mixin redirected the inner slotClick call.
 * In 26.3 they are merged into a single clicked(int,int,ContainerInput,Player),
 * and the click is dispatched from MultiPlayerGameMode.handleContainerInput
 * (verified via javap: offset 109 invokes menu.clicked). Redirect there instead.
 */
@Mixin(MultiPlayerGameMode.class)
public class SlotClickMixin {

    @Redirect(
            method = "handleContainerInput",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;clicked(IILnet/minecraft/world/inventory/ContainerInput;Lnet/minecraft/world/entity/player/Player;)V")
    )
    private void slotClick(AbstractContainerMenu self, int slotIndex, int button, ContainerInput actionType, Player player) {
        NonNullList<Slot> afterSlots = self.slots;
        List<ItemStack> beforeStacks = new ArrayList<>(afterSlots.size());
        for (Slot slot : afterSlots) {
            beforeStacks.add(slot.getItem().copy());
        }
        // Perform slot changes potentially
        self.clicked(slotIndex, button, actionType, player);
        // Check for changes and alert
        for (int i = 0; i < beforeStacks.size(); ++i) {
            ItemStack before = beforeStacks.get(i);
            ItemStack after = afterSlots.get(i).getItem();
            if (!ItemStack.isSameItemSameComponents(before, after)) {
                adris.altoclef.util.slots.Slot slot = adris.altoclef.util.slots.Slot.getFromCurrentScreen(i);
                EventBus.publish(new SlotClickChangedEvent(slot, before, after));
            }
        }
    }
}
