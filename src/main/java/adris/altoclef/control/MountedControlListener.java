package adris.altoclef.control;

import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.MountedNavigation;
import adris.altoclef.util.helpers.BoatNavigation;
import adris.altoclef.util.helpers.ElytraNavigation;
import adris.altoclef.util.helpers.SwimNavigation;
import baritone.api.event.events.PlayerUpdateEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.WorldEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;

/** Applies the chosen mounted action after Baritone's walking behaviors. */
public final class MountedControlListener implements AbstractGameEventListener {
    private final AltoClef _mod;

    public MountedControlListener(AltoClef mod) {
        _mod = mod;
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.IN) {
            // PathingBehavior can clear its forced keys on the first paused tick.
            MountedNavigation.applyInputs(_mod);
            // The vehicle ticks before its rider, so its direction is needed now.
            MountedNavigation.applyLook(_mod);
            BoatNavigation.applyInputs(_mod);
            ElytraNavigation.applyInputs(_mod);
            SwimNavigation.applyInputs(_mod);
            ElytraNavigation.applyLook(_mod);
            SwimNavigation.applyLook(_mod);
        } else {
            MountedNavigation.releaseAll(_mod);
            BoatNavigation.releaseAll(_mod);
            ElytraNavigation.releaseAll(_mod);
            SwimNavigation.releaseAll(_mod);
        }
    }

    @Override
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (event.getState() == EventState.PRE) {
            // LookBehavior (including its rideTick helper) may have set a later target.
            MountedNavigation.applyLook(_mod);
            ElytraNavigation.applyLook(_mod);
            SwimNavigation.applyLook(_mod);
        }
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        if (event.getState() == EventState.PRE) {
            MountedNavigation.releaseAll(_mod);
            BoatNavigation.releaseAll(_mod);
            ElytraNavigation.releaseAll(_mod);
            SwimNavigation.releaseAll(_mod);
        }
    }
}
