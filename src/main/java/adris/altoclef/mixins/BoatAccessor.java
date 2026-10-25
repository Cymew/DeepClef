package adris.altoclef.mixins;

import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only native state for prediction; no boat movement fields are written. */
@Mixin(AbstractBoat.class)
public interface BoatAccessor {
    @Accessor("deltaRotation") float altoclef$getAngularVelocity();
    @Accessor("status") AbstractBoat.Status altoclef$getStatus();
    @Accessor("lastYd") double altoclef$getLastVerticalMovement();
}
