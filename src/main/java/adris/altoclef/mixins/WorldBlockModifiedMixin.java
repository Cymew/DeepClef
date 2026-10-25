package adris.altoclef.mixins;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.BlockBrokenEvent;
import adris.altoclef.eventbus.events.BlockPlaceEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.BiConsumer;

/**
 * 26.3: server-confirmed block changes arrive via
 * ClientPacketListener#handleChunkBlocksUpdate(ClientboundSectionBlocksUpdatePacket).
 * (Block#playerDestroy is server-side only in 26.3, so this replaces the old
 * World#onBlockChanged + Block#onBreak hooks.)
 * <p>
 * Wrap the native update consumer after its client-thread check. Minecraft#execute
 * runs immediately on that thread, so scheduling from HEAD does not defer events
 * until after the update. Read the applied state after each native update instead.
 */
@Mixin(ClientPacketListener.class)
public class WorldBlockModifiedMixin {

    @ModifyArg(
            method = "handleChunkBlocksUpdate",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/game/ClientboundSectionBlocksUpdatePacket;runUpdates(Ljava/util/function/BiConsumer;)V"),
            index = 0,
            require = 1
    )
    private BiConsumer<BlockPos, BlockState> altoclef$afterBlockUpdate(BiConsumer<BlockPos, BlockState> update) {
        var level = ((ClientPacketListener) (Object) this).getLevel();
        if (level == null) return update;
        return (pos, state) -> {
            BlockState oldState = level.getBlockState(pos);
            update.accept(pos, state);
            BlockState newState = level.getBlockState(pos);
            // Packet iteration reuses a mutable position; only published events
            // need a retained, immutable copy.
            if (oldState.isAir() && !newState.isAir()) {
                EventBus.publish(new BlockPlaceEvent(pos.immutable(), newState));
            } else if (!oldState.isAir() && newState.isAir()) {
                BlockBrokenEvent evt = new BlockBrokenEvent();
                evt.blockPos = pos.immutable();
                evt.blockState = oldState;
                evt.player = Minecraft.getInstance().player;
                EventBus.publish(evt);
            }
        };
    }
}
