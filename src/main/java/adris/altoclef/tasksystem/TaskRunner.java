package adris.altoclef.tasksystem;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.util.helpers.MountedNavigation;
import adris.altoclef.util.helpers.BoatNavigation;
import adris.altoclef.util.helpers.ElytraNavigation;

import java.util.ArrayList;

public class TaskRunner {

    private final ArrayList<TaskChain> _chains = new ArrayList<>();
    private final AltoClef _mod;
    private boolean _active;

    private TaskChain _cachedCurrentTaskChain = null;

    public TaskRunner(AltoClef mod) {
        _mod = mod;
        _active = false;
    }

    public void tick() {
        if (!_active || !AltoClef.inGame()) return;
        tickChains();
    }

    // Keep arbitration separate from game availability so its lifecycle can be tested
    // without constructing a client world. Priority queries must only plan actions.
    void tickChains() {
        // Get highest priority chain and run
        TaskChain maxChain = null;
        float maxPriority = Float.NEGATIVE_INFINITY;
        for (TaskChain chain : _chains) {
            if (!chain.isActive()) continue;
            float priority = chain.getPriority(_mod);
            if (priority > maxPriority) {
                maxPriority = priority;
                maxChain = chain;
            }
        }
        if (_cachedCurrentTaskChain != null && maxChain != _cachedCurrentTaskChain) {
            _cachedCurrentTaskChain.onInterrupt(_mod, maxChain);
        }
        _cachedCurrentTaskChain = maxChain;
        MountedNavigation.prepareFrame(_mod, maxChain, maxPriority);
        BoatNavigation.prepareFrame(_mod, maxChain, maxPriority);
        ElytraNavigation.prepareFrame(_mod, maxChain);
        adris.altoclef.util.helpers.SwimNavigation.prepareFrame(_mod, maxChain);
        if (maxChain != null) {
            maxChain.tick(_mod);
        }
        // A task may disable the runner while finishing. Do not restart background
        // actions after all chains have just released their controls.
        if (_active) {
            for (TaskChain chain : _chains) {
                chain.onTickFinished(_mod, maxChain);
            }
        }
    }

    public void addTaskChain(TaskChain chain) {
        _chains.add(chain);
    }

    public void enable() {
        if (!_active) {
            _mod.getBehaviour().push();
            _mod.getBehaviour().setPauseOnLostFocus(false);
        }
        _active = true;
    }

    public void disable() {
        if (_active) {
            _mod.getBehaviour().pop();
        }
        for (TaskChain chain : _chains) {
            chain.stop(_mod);
        }
        _active = false;
        _cachedCurrentTaskChain = null;
        MountedNavigation.releaseAll(_mod);
        BoatNavigation.releaseAll(_mod);
        ElytraNavigation.releaseAll(_mod);
        adris.altoclef.util.helpers.SwimNavigation.releaseAll(_mod);

        Debug.logMessage("Stopped");
    }

    public TaskChain getCurrentTaskChain() {
        return _cachedCurrentTaskChain;
    }

    public boolean isActive() { return _active; }

    // Kinda jank ngl
    public AltoClef getMod() {
        return _mod;
    }
}
