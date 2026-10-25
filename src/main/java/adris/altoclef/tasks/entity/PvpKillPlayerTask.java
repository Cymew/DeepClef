package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.PvpCombat;
import net.minecraft.world.entity.Entity;

import java.util.Optional;

/**
 * PvP: hunt a named player.
 *
 * <p>Unlike {@link KillPlayerTask} (which walks up and swings like a mob fight),
 * this task delegates to {@link PvpCombat}: spear/sword up close, bow at range,
 * shield while closing under fire and strafing in melee.
 *
 * <p>The target has to be loaded client-side (entity tracker / render distance);
 * if it never shows up the task gives up instead of pathing across the world.
 */
public class PvpKillPlayerTask extends Task {

    /** Give up if the player never becomes visible for this long. */
    private static final int GIVE_UP_TICKS = 20 * 30;

    private final String _playerName;
    private int _lostTicks;
    private Entity _target;
    private boolean _giveUp;

    public PvpKillPlayerTask(String playerName) {
        _playerName = playerName;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _lostTicks = 0;
        _target = null;
        _giveUp = false;
        PvpCombat.reset(mod);
        // A pause/inventory screen disables vanilla key handling (bow release,
        // shield use); close it before the fight starts.
        adris.altoclef.util.helpers.StorageHelper.closeInterferingScreens();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (!AltoClef.inGame()) return null;

        Optional<Entity> found = lookup(mod);
        if (found.isEmpty()) {
            _lostTicks++;
            setDebugState("Waiting for " + _playerName + " to come into view");
            if (_lostTicks >= GIVE_UP_TICKS) {
                mod.logWarning("PvP: 附近找不到玩家 " + _playerName + "（需在视野/渲染距离内），任务结束。");
                _giveUp = true;
                return null;
            }
            // Search around instead of standing still.
            return new TimeoutWanderTask(2);
        }

        _lostTicks = 0;
        _target = found.get();
        setDebugState("PvP vs " + _playerName + " (" + PvpCombat.getMode() + ")");
        return PvpCombat.tick(mod, _target);
    }

    private Optional<Entity> lookup(AltoClef mod) {
        if (!mod.getEntityTracker().isPlayerLoaded(_playerName)) return Optional.empty();
        return mod.getEntityTracker().getPlayerEntity(_playerName).map(Entity.class::cast);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _giveUp || (_target != null && (!_target.isAlive() || _target.isRemoved()));
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        PvpCombat.reset(mod);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof PvpKillPlayerTask task && task._playerName.equals(_playerName);
    }

    @Override
    protected String toDebugString() {
        return "PvP hunting " + _playerName;
    }
}
