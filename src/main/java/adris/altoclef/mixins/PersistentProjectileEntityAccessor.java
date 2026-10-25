package adris.altoclef.mixins;

import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 26.3 removed the inGround boolean field; AbstractArrow now exposes
 * protected isInGround()/setInGround(boolean) accessors instead.
 */
@Mixin(AbstractArrow.class)
public interface PersistentProjectileEntityAccessor {
    // Note: must NOT be named isInGround() — an @Invoker method with the same
    // name as the target method overrides it and causes infinite recursion.
    @Invoker("isInGround")
    boolean callIsInGround();
}
