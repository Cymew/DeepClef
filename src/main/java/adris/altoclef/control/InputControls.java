package adris.altoclef.control;

import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.KeyMapping;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/**
 * Sometimes we want to trigger a "press" for one frame, or do other input forcing.
 * <p>
 * Dealing with keeping track of a press and timing each time you do this is annoying.
 * <p>
 * For some reason using baritone's "Forcestate" doesn't always work, perhaps that's my bad.
 * <p>
 * But this will alleviate all confusion.
 */
@SuppressWarnings("UnnecessaryDefault")
public class InputControls {

    private final Queue<Input> _toUnpress = new ArrayDeque<>();
    private final Set<Input> _held = new HashSet<>();
    private final Set<Input> _waitForRelease = new HashSet<>(); // a click requires a release.

    private static KeyMapping inputToKeyBinding(Input input) {
        Options o = Minecraft.getInstance().options;
        return switch (input) {
            case MOVE_FORWARD -> o.keyUp;
            case MOVE_BACK -> o.keyDown;
            case MOVE_LEFT -> o.keyLeft;
            case MOVE_RIGHT -> o.keyRight;
            case CLICK_LEFT -> o.keyAttack;
            case CLICK_RIGHT -> o.keyUse;
            case JUMP -> o.keyJump;
            case SNEAK -> o.keyShift;
            case SPRINT -> o.keySprint;
            default -> throw new IllegalArgumentException("Invalid key input/not accounted for: " + input);
        };
    }

    private static void clickBinding(KeyMapping mapping) {
        // A direct action belongs to this binding even after a player rebinds it.
        var access = (adris.altoclef.mixins.KeyClickAccessor) mapping;
        access.deepclefSetClicks(access.deepclefGetClicks() + 1);
    }

    public void tryPress(Input input) {
        // We just pressed, so let us release.
        if (_waitForRelease.contains(input)) {
            return;
        }
        inputToKeyBinding(input).setDown(true);
        // Also necessary to ensure the game registers the input as "pressed"
        clickBinding(inputToKeyBinding(input));
        _toUnpress.add(input);
        _waitForRelease.add(input);
    }

    public void hold(Input input) {
        if (!inputToKeyBinding(input).isDown()) {
            clickBinding(inputToKeyBinding(input));
        }
        holdWithoutClick(input);
    }

    /** Maintain an already-started native use without queuing a second use click. */
    public void holdWithoutClick(Input input) {
        _held.add(input);
        inputToKeyBinding(input).setDown(true);
    }

    public void release(Input input) {
        _held.remove(input);
        inputToKeyBinding(input).setDown(false);
    }

    public void releaseAll() {
        onTickPre();
        for (Input input : _held) inputToKeyBinding(input).setDown(false);
        _held.clear();
        _waitForRelease.clear();
    }

    public boolean isHeldDown(Input input) {
        return inputToKeyBinding(input).isDown();
    }

    public void forceLook(float yaw, float pitch) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.setYRot(yaw);
            Minecraft.getInstance().player.setXRot(pitch);
        }
    }

    // Before the user calls input commands for the frame
    public void onTickPre() {
        while (!_toUnpress.isEmpty()) {
            inputToKeyBinding(_toUnpress.remove()).setDown(false);
        }
    }

    // After the user calls input commands for the frame
    public void onTickPost() {
        _waitForRelease.clear();
    }
}
