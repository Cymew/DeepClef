package adris.altoclef.mixins;

import adris.altoclef.tasks.movement.OpenDoorTask;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public class DoorServerStateMixin {
    @Inject(method="setServerVerifiedBlockState",at=@At("RETURN"))
    private void altoclef$observeDoor(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        OpenDoorTask.serverState((ClientLevel)(Object)this,pos,state);
    }
}
