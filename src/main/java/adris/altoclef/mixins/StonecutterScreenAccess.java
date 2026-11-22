package adris.altoclef.mixins;

import net.minecraft.client.gui.screens.inventory.StonecutterScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(StonecutterScreen.class)
public interface StonecutterScreenAccess {
    @Accessor("startIndex") int deepclefStartIndex();
}
