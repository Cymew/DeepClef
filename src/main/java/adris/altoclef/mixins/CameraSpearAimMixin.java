package adris.altoclef.mixins;

import adris.altoclef.util.helpers.SpearHelper;
import adris.altoclef.util.helpers.MountedNavigation;
import adris.altoclef.util.helpers.ElytraNavigation;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Interpolates the camera without changing the player's attack or movement rotation. */
@Mixin(Camera.class)
public abstract class CameraSpearAimMixin {
    @Redirect(
            method = "alignWithEntity(F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F"),
            require = 2,
            allow = 2
    )
    private float altoclef$smoothSpearYaw(Entity entity, float partialTicks) {
        return ElytraNavigation.getRenderYaw(entity, partialTicks, MountedNavigation.getRenderYaw(entity, partialTicks,
                SpearHelper.getRenderYaw(entity, partialTicks, entity.getViewYRot(partialTicks))));
    }

    @Redirect(
            method = "alignWithEntity(F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F"),
            require = 2,
            allow = 2
    )
    private float altoclef$smoothSpearPitch(Entity entity, float partialTicks) {
        return ElytraNavigation.getRenderPitch(entity, partialTicks, MountedNavigation.getRenderPitch(entity, partialTicks,
                SpearHelper.getRenderPitch(entity, partialTicks, entity.getViewXRot(partialTicks))));
    }
}
