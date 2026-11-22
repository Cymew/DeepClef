package adris.altoclef.mixins;

import adris.altoclef.util.InventoryChanges;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class InventorySyncMixin implements InventoryChanges.Source {
    @Unique private final InventoryChanges deepclef$changes = new InventoryChanges();

    @Override public InventoryChanges deepclefInventoryChanges() { return deepclef$changes; }

    @Inject(method = "handleContainerContent", at = @At("RETURN"))
    private void deepclef$inventoryReady(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        var player = Minecraft.getInstance().player;
        if (packet.containerId() == 0 && player != null && player.connection == (Object)this)
            deepclef$changes.afterInventorySync(player);
    }
}
