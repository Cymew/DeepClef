package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.BoatNavigation;
import adris.altoclef.util.helpers.BoatPlacementHelper;
import adris.altoclef.util.helpers.BoatSupplyHelper;
import adris.altoclef.util.helpers.MountedNavigation;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/** Acquire one carried boat, approach the nearby water, place it, then board its exact entity. */
public final class AutoBoatBoardTask extends Task {
    private enum Phase { SUPPLY, APPROACH, PLACE, BOARD }
    private final Vec3 _waterPoint;
    private Phase _phase = Phase.SUPPLY;
    private LocalPlayer _player;
    private Level _level;
    private Task _supply;
    private GetToBlockTask _approach;
    private BoatPlacementTask _placement;
    private BoatControlTask _boarding;
    private boolean _done, _success;
    private int _ticks, _phaseTicks, _lastTick = Integer.MIN_VALUE;
    private String _result;

    public AutoBoatBoardTask(Vec3 waterPoint) { _waterPoint = waterPoint; }
    public boolean succeeded() { return _done && _success; }
    public String getResult() { return _result == null ? "自动放船登船尚未完成或已中断。" : _result; }

    @Override protected void onStart(AltoClef mod) {
        if (_player == null) { _player = mod.getPlayer(); _level = mod.level(); }
        _lastTick = Integer.MIN_VALUE;
    }

    @Override protected Task onTick(AltoClef mod) {
        if (_done) return null;
        if (_player == null || mod.getPlayer() != _player || _level == null || mod.level() != _level
                || !_player.isAlive()) return finish(mod, false, "自动放船已停止：玩家或世界已改变。");
        if (MountedNavigation.isSafetyFrame() || mod.getFoodChain().isTryingToEat() || _player.isUsingItem()) {
            setDebugState("Waiting for food or survival before boarding a new boat.");
            return null;
        }
        if (_lastTick != _player.tickCount) { _lastTick = _player.tickCount; _ticks++; _phaseTicks++; }
        if (_ticks > 1500) return finish(mod, false, "自动放船登船超时，继续原路线。");
        if (_player.isPassenger() && _phase != Phase.BOARD)
            return finish(mod, false, "自动放船已停止：当前已在其他载具上。");

        switch (_phase) {
            case SUPPLY -> {
                if (BoatPlacementHelper.hasBoatInInventory(_player)) {
                    advance(mod, Phase.APPROACH, _supply);
                    return null;
                }
                if (_supply == null) _supply = BoatSupplyHelper.craftFromCarriedPlanks(mod);
                if (_supply == null) return finish(mod, false, "背包没有普通船/竹筏，也没有足够的现成木板制作，继续原路线。");
                if (_phaseTicks > 600 || _supply.isFinished(mod))
                    return finish(mod, false, "未能用现成材料完成造船，继续原路线。");
                setDebugState("Crafting one boat from carried planks.");
                return _supply;
            }
            case APPROACH -> {
                if (_waterPoint == null || _player.position().distanceToSqr(_waterPoint) <= 12.25
                        || _phaseTicks > 200) {
                    advance(mod, Phase.PLACE, _approach);
                    return null;
                }
                if (_approach == null) _approach = new GetToBlockTask(BlockPos.containing(_waterPoint.add(0, -.3, 0)));
                if (_approach.isFinished(mod)) {
                    advance(mod, Phase.PLACE, _approach);
                    return null;
                }
                setDebugState("Approaching the long water route before launching.");
                return _approach;
            }
            case PLACE -> {
                if (_placement == null) _placement = new BoatPlacementTask(_waterPoint);
                if (!_placement.isFinished(mod)) {
                    setDebugState("Launching one inventory boat.");
                    return _placement;
                }
                if (!_placement.succeeded()) return finish(mod, false, _placement.getResult());
                _boarding = BoatControlTask.board(_placement.getPlacedBoatId());
                advance(mod, Phase.BOARD, _placement);
                return null;
            }
            case BOARD -> {
                if (!_boarding.isFinished(mod)) {
                    setDebugState("Boarding the newly confirmed boat.");
                    return _boarding;
                }
                var boat = BoatNavigation.getControlledBoat(_player);
                boolean success = boat != null && boat.getUUID().equals(_placement.getPlacedBoatUUID());
                return finish(mod, success, success ? "已自动放船并登上驾驶位。" : _boarding.getResult());
            }
        }
        return null;
    }

    private void advance(AltoClef mod, Phase phase, Task previous) {
        if (previous != null && previous.isActive()) previous.stop(mod);
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        _phase = phase; _phaseTicks = 0;
    }

    private Task finish(AltoClef mod, boolean success, String result) {
        _done = true; _success = success; _result = result;
        for (Task child : new Task[]{_supply, _approach, _placement, _boarding})
            if (child != null && child.isActive()) child.stop(mod);
        setDebugState(result);
        return null;
    }

    @Override public boolean isFinished(AltoClef mod) { return _done; }
    @Override protected void onStop(AltoClef mod, Task interruptTask) { }
    @Override protected boolean isEqual(Task other) {
        return other instanceof AutoBoatBoardTask task && Objects.equals(_waterPoint, task._waterPoint);
    }
    @Override protected String toDebugString() { return "Automatically launching and boarding a boat"; }
}
