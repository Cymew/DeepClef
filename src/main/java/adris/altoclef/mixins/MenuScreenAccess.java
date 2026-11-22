package adris.altoclef.mixins;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractContainerScreen.class)
public interface MenuScreenAccess {
    @Accessor("imageWidth") int deepclefPanelWidth();
    @Accessor("imageHeight") int deepclefPanelHeight();
    @Accessor("leftPos") int deepclefLeft();
    @Accessor("topPos") int deepclefTop();
    @Accessor("hoveredSlot") Slot deepclefHoveredSlot();
    @Accessor("hoveredSlot") void deepclefSetHoveredSlot(Slot slot);
    @Invoker("getHoveredSlot") Slot deepclefSlotAt(double x, double y);
    @Invoker("slotClicked") void deepclefClick(Slot slot, int index, int button, ContainerInput input);
}
