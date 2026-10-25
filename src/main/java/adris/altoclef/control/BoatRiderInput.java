package adris.altoclef.control;

import adris.altoclef.util.helpers.BoatSteering;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/** Retains vanilla's sampled key history while boat navigation owns the rider. */
public final class BoatRiderInput extends ClientInput {
    private BoatSteering.Input intent = new BoatSteering.Input(false, false, false, false);
    private boolean dismount;

    public void setIntent(BoatSteering.Input intent, boolean dismount) {
        this.intent = intent;
        this.dismount = dismount;
    }

    @Override public void tick() {
        keyPresses = new Input(!dismount && intent.forward(), !dismount && intent.back(),
                !dismount && intent.left(), !dismount && intent.right(), false, dismount, false);
        float x = keyPresses.left() == keyPresses.right() ? 0 : keyPresses.left() ? 1 : -1;
        float z = keyPresses.forward() == keyPresses.backward() ? 0 : keyPresses.forward() ? 1 : -1;
        moveVector = new Vec2(x, z).normalized();
    }
}
