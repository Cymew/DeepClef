package adris.altoclef.control;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/** Persistent native input; no position or velocity is written by swimming. */
public final class SwimInput extends ClientInput {
    private boolean forward, jump, sneak, sprint;
    public void intent(boolean forward, boolean jump, boolean sneak, boolean sprint) {
        this.forward=forward; this.jump=jump; this.sneak=sneak; this.sprint=sprint;
    }
    @Override public void tick() {
        keyPresses=new Input(forward,false,false,false,jump,sneak,sprint);
        moveVector=forward ? new Vec2(0,1) : Vec2.ZERO;
    }
}
