package adris.altoclef.mixins;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.PlayerCollidedWithEntityEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public class PlayerCollidesWithEntityMixin {

    // Determines a collision between items/EXP orbs/other objects within "pickup" range.
    // 26.3: Player#touch no longer exists; Entity#playerTouch(Player) is the hook.
    @Inject(
            method = "playerTouch",
            at = @At("HEAD")
    )
    private void onPlayerTouch(Player player, CallbackInfo ci) {
        if (player instanceof LocalPlayer) {
            EventBus.publish(new PlayerCollidedWithEntityEvent(player, (Entity) (Object) this));
        }
    }
}
