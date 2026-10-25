package adris.altoclef.control;

import baritone.api.utils.input.Input;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.phys.Vec2;

/** Keeps the previous sampled keys across Baritone's input-controller swaps. */
public final class MountedRiderInput extends ClientInput {
    private Input direction;
    private boolean jump;
    private boolean dismount;

    /** Intent must not change keyPresses until LocalPlayer samples this tick. */
    public void setIntent(Input direction, boolean jump) {
        this.direction = direction == Input.MOVE_FORWARD || direction == Input.MOVE_BACK ? direction : null;
        this.jump = jump;
    }

    /** Set pending Shift input; cancel an uncommitted jump charge before requesting dismount. */
    public void setDismount(boolean dismount) {
        this.dismount = dismount;
    }

    /** Suppress the release edge only when cancelling an uncommitted charge. */
    public void cancelCharge() {
        jump = false;
        var keys = keyPresses;
        keyPresses = new net.minecraft.world.entity.player.Input(keys.forward(), keys.backward(), keys.left(),
                keys.right(), false, keys.shift(), keys.sprint());
    }

    @Override public void tick() {
        if (dismount) {
            keyPresses = new net.minecraft.world.entity.player.Input(false, false, false, false, false, true, false);
            moveVector = Vec2.ZERO;
            return;
        }
        boolean forward = direction == Input.MOVE_FORWARD;
        boolean backward = direction == Input.MOVE_BACK;
        keyPresses = new net.minecraft.world.entity.player.Input(forward, backward, false, false, jump, false, false);
        moveVector = new Vec2(0, forward ? 1 : backward ? -1 : 0);
    }
}
