package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.MountedNavigation;
import adris.altoclef.util.helpers.BoatNavigation;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.input.Input;

/**
 * Will move around randomly while holding shift
 * Used to escape weird situations where baritone doesn't work.
 */
public class SafeRandomShimmyTask extends Task {

    private final TimerGame _lookTimer;

    public SafeRandomShimmyTask(float randomLookInterval) {
        _lookTimer = new TimerGame(randomLookInterval);
    }

    public SafeRandomShimmyTask() {
        this(5);
    }

    @Override
    protected void onStart(AltoClef mod) {
        _lookTimer.reset();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (mod.getPlayer().getVehicle() instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat) {
            BoatNavigation.hold(mod, this);
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            setDebugState("Boat recovery stopped safely.");
            return null;
        }
        BoatNavigation.release(mod, this);
        if (MountedNavigation.getControlledHorse(mod.getPlayer()) != null) {
            MountedNavigation.hold(mod, this);
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            setDebugState("Mounted recovery stopped safely.");
            return null;
        }
        MountedNavigation.release(mod, this);

        if (_lookTimer.elapsed()) {
            Debug.logMessage("Random Orientation");
            _lookTimer.reset();
            LookHelper.randomOrientation(mod);
        }

        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        BoatNavigation.release(mod, this);
        MountedNavigation.release(mod, this);
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof SafeRandomShimmyTask;
    }

    @Override
    protected String toDebugString() {
        return "Shimmying";
    }
}
