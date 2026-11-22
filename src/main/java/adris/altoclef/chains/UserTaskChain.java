package adris.altoclef.chains;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.TaskFinishedEvent;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.util.time.Stopwatch;

// A task chain that runs a user defined task at the same priority.
// This basically replaces our old Task Runner.
@SuppressWarnings("ALL")
public class UserTaskChain extends SingleTaskChain {

    private final Stopwatch _taskStopwatch = new Stopwatch();
    private Runnable _currentOnFinish = null;

    private java.util.UUID walkEvent;
    private adris.altoclef.memory.MemoryScope walkScope;
    private Object walkLevel;

    private void endWalk(AltoClef mod,boolean completed) {
        if (walkEvent!=null && walkScope==mod.memory().scope() && walkLevel==mod.level() && mod.getPlayer()!=null
                && _mainTask instanceof adris.altoclef.tasks.movement.GetToXZTask walk) {
            boolean arrived=completed && !walk.stopped() && walk.verifiedAt(mod);
            mod.memory().record(walkEvent,arrived?"confirmed":"unfinished",arrived?"到达路标":"行走未完成",
                    walk.targetDescription(),arrived?adris.altoclef.memory.Experience.Signal.ARRIVED:adris.altoclef.memory.Experience.Signal.NONE);
        }
        walkEvent=null;
        walkScope=null;
        walkLevel=null;
    }

    private boolean _runningIdleTask;
    private boolean _nextTaskIdleFlag;

    public UserTaskChain(TaskRunner runner) {
        super(runner);
    }

    private static String prettyPrintTimeDuration(double seconds) {
        int minutes = (int) (seconds / 60);
        int hours = minutes / 60;
        int days = hours / 24;

        String result = "";
        if (days != 0) {
            result += days + " days ";
        }
        if (hours != 0) {
            result += (hours % 24) + " hours ";
        }
        if (minutes != 0) {
            result += (minutes % 60) + " minutes ";
        }
        if (!result.equals("")) {
            result += "and ";
        }
        result += String.format("%.3f", (seconds % 60));
        return result;
    }

    @Override
    protected void onTick(AltoClef mod) {

        // Pause if we're not loaded into a world.
        if (!mod.inGame()) return;

        super.onTick(mod);
    }

    public void finishPausedKey(AltoClef mod) {
        if (_mainTask instanceof adris.altoclef.util.AtomicActions.ActionTask key && key.finishAtBlockingScreen()) {
            finishTask(mod, false);
        }
    }

    public void cancel(AltoClef mod) {
        if (_mainTask != null) finishTask(mod, true);
    }

    @Override
    protected void onStop(AltoClef mod) {
        endWalk(mod,false);
        super.onStop(mod);
        _currentOnFinish = null;
        _nextTaskIdleFlag = false;
        _runningIdleTask = false;
    }

    @Override
    public float getPriority(AltoClef mod) {
        return 50;
    }

    @Override
    public String getName() {
        return "User Tasks";
    }

    public void runTask(AltoClef mod, Task task, Runnable onFinish) {
        _runningIdleTask = _nextTaskIdleFlag;
        _nextTaskIdleFlag = false;

        _currentOnFinish = onFinish;

        if (!_runningIdleTask) {
            Debug.logMessage("User Task Set: " + task.toString());
        }
        mod.getTaskRunner().enable();
        _taskStopwatch.begin();
        if (_mainTask==null || !_mainTask.equals(task)) endWalk(mod,false);
        setTask(task);
        if (walkEvent==null && _mainTask instanceof adris.altoclef.tasks.movement.GetToXZTask walk) {
            walkEvent=java.util.UUID.randomUUID();
            walkScope=mod.memory().scope();
            walkLevel=mod.level();
            mod.memory().record(walkEvent,"pending","行走目标",walk.targetDescription(),adris.altoclef.memory.Experience.Signal.NONE);
        }

        if (mod.getModSettings().failedToLoad()) {
            Debug.logWarning("Settings file failed to load at some point. Check logs for more info, or delete the" +
                    " file to re-load working settings.");
        }
    }

    @Override
    protected void onTaskFinish(AltoClef mod) {
        finishTask(mod, false);
    }

    private void finishTask(AltoClef mod, boolean cancelled) {
        endWalk(mod,!cancelled);
        Task oldTask = _mainTask;
        Runnable completion = _currentOnFinish;
        boolean wasIdle = _runningIdleTask;
        double seconds = _taskStopwatch.time();
        mod.getTaskRunner().disable();
        mod.getClientBaritone().getInputOverrideHandler().clearAllKeys();
        if (completion != null) completion.run();
        if (_mainTask == null && !wasIdle) {
            Debug.logMessage("User task %s. Took %s seconds.", cancelled ? "STOPPED" : "FINISHED", prettyPrintTimeDuration(seconds));
            EventBus.publish(new TaskFinishedEvent(seconds, oldTask));
        }
        if (!cancelled) mod.startConfiguredIdleTask();
    }

    public boolean isRunningIdleTask() {
        return isActive() && _runningIdleTask;
    }

    public void runConfiguredIdleCommand(AltoClef mod) {
        if (_nextTaskIdleFlag) return;
        _nextTaskIdleFlag = true;
        try {
            AltoClef.getCommandExecutor().executeWithPrefix(mod.getModSettings().getIdleCommand());
        } finally {
            _nextTaskIdleFlag = false;
        }
    }
}
