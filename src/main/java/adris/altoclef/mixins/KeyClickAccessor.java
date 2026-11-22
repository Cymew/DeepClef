package adris.altoclef.mixins;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(KeyMapping.class)
public interface KeyClickAccessor {
    @Accessor("clickCount") int deepclefGetClicks();
    @Accessor("clickCount") void deepclefSetClicks(int value);
}
