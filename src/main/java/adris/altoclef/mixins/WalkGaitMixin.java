package adris.altoclef.mixins;

import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.GetToXZTask;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class WalkGaitMixin {
    @org.spongepowered.asm.mixin.Unique
    private final adris.altoclef.util.helpers.NativeWaterTravel deepclef$water = new adris.altoclef.util.helpers.NativeWaterTravel();

    @Inject(method = "aiStep", at = @At("HEAD"))
    private void deepclef$startFrame(CallbackInfo ci) {
        var p = (LocalPlayer)(Object)this;
        var owner = GetToXZTask.styledOwner(Debug.jankModInstance);
        if (owner == null) return;
        owner.beginFrame(p);
        if (owner.gait() == GetToXZTask.Gait.SNEAK) {
            // Vanilla computes the crouching pose before refreshing movement input.
            p.input.keyPresses = GetToXZTask.withGait(p.input.keyPresses, owner.gait());
            if (p.isSprinting()) p.setSprinting(false);
        }
    }

    @Inject(method = "aiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/ClientInput;tick()V", shift = At.Shift.AFTER))
    private void deepclef$applyGait(CallbackInfo ci) {
        if (deepclef$water.apply(Debug.jankModInstance, (LocalPlayer)(Object)this)) return;
        var owner = GetToXZTask.styledOwner(Debug.jankModInstance);
        if (owner == null) return;
        var input = ((LocalPlayer)(Object)this).input;
        input.keyPresses = GetToXZTask.withGait(input.keyPresses, owner.gait());
    }

    @Inject(method = "aiStep", at = @At("RETURN"))
    private void deepclef$measureMovement(CallbackInfo ci) {
        var owner = GetToXZTask.styledOwner(Debug.jankModInstance);
        if (owner != null) owner.endFrame((LocalPlayer)(Object)this);
    }
}
