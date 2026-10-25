package adris.altoclef.mixins;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ChunkLoadEvent;
import adris.altoclef.eventbus.events.ChunkUnloadEvent;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientChunkCache.class)
public class LoadChunkMixin {

    @Inject(
            method = "replaceWithPacketData",
            at = @At("RETURN")
    )
    private void onLoadChunk(int x, int z, ClientboundLevelChunkPacketData data, CallbackInfoReturnable<LevelChunk> ci) {
        // Publish a ChunkLoadEvent with the return value of the method as the argument
        EventBus.publish(new ChunkLoadEvent(ci.getReturnValue()));
    }

    /**
     * Publishes a ChunkUnloadEvent when a chunk is unloaded.
     */
    @Inject(
            method = "drop",
            at = @At("TAIL")
    )
    private void onChunkUnload(ChunkPos pos, CallbackInfo ci) {
        EventBus.publish(new ChunkUnloadEvent(pos));
    }
}
