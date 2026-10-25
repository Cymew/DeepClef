package adris.altoclef.control;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/** Stable input history lets native LocalPlayer recognise both jump press edges. */
public final class ElytraFlightInput extends ClientInput {
    private boolean jump, forward;

    /** Only pending intent changes here; LocalPlayer.aiStep samples it in tick(). */
    public void setIntent(boolean jump, boolean forward) {
        this.jump = jump;
        this.forward = forward;
    }

    @Override public void tick() {
        keyPresses = new Input(forward, false, false, false, jump, false, false);
        moveVector = forward ? new Vec2(0, 1) : Vec2.ZERO;
    }
}
