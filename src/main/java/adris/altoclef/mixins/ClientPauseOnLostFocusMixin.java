package adris.altoclef.mixins;

import adris.altoclef.AltoClef;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The bot has to keep simulating while the Minecraft window is unfocused.
 *
 * <p>Vanilla's pause-on-lost-focus ({@code Options.pauseOnLostFocus}, on by
 * default) calls {@code pauseGame(false)} half a second after focus is lost.
 * In single player that freezes the world, and in multiplayer it opens the
 * pause screen; either way most input-driven actions stop working. The symptom
 * that exposed this was drawing a bow but never releasing the arrow, because
 * the bow release path needs live ticks / an unfiltered input pass.
 *
 * <p>Cancelling here only applies while a world is loaded, so the menu and the
 * loading screens keep their normal behaviour. The human can still press Esc.
 */
@Mixin(Minecraft.class)
public abstract class ClientPauseOnLostFocusMixin {

    @Inject(method = "pauseIfInactive", at = @At("HEAD"), cancellable = true)
    private void altoclef$keepSimulatingWhenUnfocused(CallbackInfo ci) {
        if (AltoClef.inGame()) {
            ci.cancel();
        }
    }
}
