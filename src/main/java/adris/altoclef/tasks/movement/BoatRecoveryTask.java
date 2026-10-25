package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.BoatNavigation;
import adris.altoclef.util.helpers.BoatRecoveryHelper;
import adris.altoclef.util.helpers.BowCombat;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.MountedNavigation;
import adris.altoclef.util.helpers.SpearHelper;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Optional, bounded cleanup of exactly the boat that its parent has just dismounted. */
public final class BoatRecoveryTask extends Task {
    private final UUID _boatId;
    private final Set<UUID> _oldDrops = new HashSet<>();
    private ClientLevel _world;
    private AltoClef _mod;
    private LocalPlayer _player;
    private AbstractBoat _boat;
    private ItemStack _expected = ItemStack.EMPTY;
    private Vec3 _dropPosition;
    private GetToEntityTask _approach;
    private ItemEntity _drop;
    private boolean _initialized, _done, _succeeded, _removed;
    private int _beforeCount = -1, _attacks, _ticks, _pickupTicks, _inventoryAckTicks, _lastTick = Integer.MIN_VALUE;
    private int _originalSlot = -1, _attackSlot = -1, _lastAttackTick = Integer.MIN_VALUE;
    private String _result = "船回收尚未完成。";

    public BoatRecoveryTask(UUID boatId) { _boatId = boatId; }
    public String getResult() { return _result; }
    public boolean succeeded() { return _succeeded; }

    @Override
    protected void onStart(AltoClef mod) {
        _mod = mod;
        if (_world == null) {
            _world = mod.level();
            _player = mod.getPlayer();
        }
        // Pausing for food or survival preserves the target and confirmations, never a held key.
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_done) return null;
        if (_player == null || mod.getPlayer() != _player || _world == null || mod.level() != _world
                || !_player.isAlive()) return finish(mod, false, "跳过回收：玩家或世界已改变。");
        if (_player.isPassenger()) return finish(mod, false, "跳过回收：玩家尚未下船或已登上其他载具。");
        if (_player.getAbilities().instabuild || _player.isSpectator())
            return finish(mod, false, "跳过回收：当前模式破坏船不会正常掉落船物品。");
        if (!BoatRecoveryHelper.mayAct(false, _player.isUsingItem(), mod.getFoodChain().isTryingToEat(),
                MountedNavigation.isSafetyFrame(), SpearHelper.isCombatActive() || BowCombat.isActive())) {
            restoreSlot();
            setDebugState("Waiting for food or survival before optional boat recovery.");
            return null;
        }
        if (_player.containerMenu != _player.inventoryMenu || _player.isUnderWater())
            return finish(mod, false, "跳过回收：正在使用容器或尚未浮出水面。");
        boolean nextTick = _lastTick != _player.tickCount;
        _lastTick = _player.tickCount;
        if (nextTick && ++_ticks > 440) return finish(mod, false, "船回收超时，继续原任务。");

        if (!_initialized) {
            _boat = findBoat();
            if (!eligible(_boat)) return finish(mod, false, "跳过回收：原船不在附近、不是空普通船，或已系绳/沉水。");
            _expected = BoatRecoveryHelper.expectedDrop(_boat);
            if (!BoatRecoveryHelper.hasRoom(_player.getInventory(), _expected))
                return finish(mod, false, "背包已满，保留停泊的船。");
            _beforeCount = BoatRecoveryHelper.count(_player.getInventory(), _expected);
            _dropPosition = _boat.position();
            _initialized = true;
        }

        _removed = _boat.isRemoved() || !_boat.isAlive();
        int inventoryCount = BoatRecoveryHelper.count(_player.getInventory(), _expected);
        // Freeze at the first hit: inventory and removal acknowledgements need not arrive together.
        if (_attacks == 0 && !_removed) _beforeCount = Math.max(_beforeCount, inventoryCount);
        if (BoatRecoveryHelper.confirmed(_attacks, _removed, _beforeCount, inventoryCount))
            return finish(mod, true, "已回收刚才驾驶的船。");
        if (BoatRecoveryHelper.inventoryAheadOfRemoval(_attacks, _removed, _beforeCount, inventoryCount)) {
            restoreSlot();
            if (nextTick && ++_inventoryAckTicks > 100)
                return finish(mod, false, "已看到船物品库存增量，但服务器尚未确认原船移除。");
            setDebugState("Boat item received; waiting for the original boat's server removal acknowledgement.");
            return null;
        }
        if (!BoatRecoveryHelper.hasRoom(_player.getInventory(), _expected))
            return finish(mod, false, "背包已满，停止可选船回收。");

        if (_removed) return pickupTick(mod, nextTick);
        if (!eligible(_boat)) return finish(mod, false, "跳过回收：原船已被占用、系绳、沉水或移到远处。");
        if (_ticks > 240 || _attacks >= 40) return finish(mod, false, "船未被服务器正常破坏，停止回收。");
        _dropPosition = _boat.position();
        Vec3 point = nearestPoint(_player.getEyePosition(), _boat.getBoundingBox());
        if (!_player.isWithinAttackRange(ItemStack.EMPTY, _boat.getBoundingBox(), -.15)
                || !clearAttack(point)) {
            setDebugState("Approaching the dismounted boat for recovery.");
            return approach(_boat, 1.8);
        }
        if (hasWalkingChild()) {
            setDebugState("Stopping the walking approach before recovering the boat.");
            return null;
        }
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        if (!selectSafeHand()) return finish(mod, false, "跳过回收：快捷栏没有空手或安全的普通物品。");
        LookHelper.lookAt(mod, point);
        // Java boats heal one damage-unit per tick; full-strength bare-hand hits must repeat.
        if (nextTick && _player.getAttackStrengthScale(0) >= 1
                && (_lastAttackTick == Integer.MIN_VALUE || _player.tickCount - _lastAttackTick >= 5)) {
            if (_attacks == 0) {
                _beforeCount = BoatRecoveryHelper.count(_player.getInventory(), _expected);
                // Items appearing during the walking approach also predate our boat damage.
                for (Entity entity : _world.entitiesForRendering()) {
                    if (entity instanceof ItemEntity item && matches(item)) _oldDrops.add(item.getUUID());
                }
            }
            mod.getController().attack(_player, _boat);
            _player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            _attacks++;
            _lastAttackTick = _player.tickCount;
        }
        setDebugState("Recovering the dismounted boat; waiting for server damage/drop confirmation.");
        return null;
    }

    private Task pickupTick(AltoClef mod, boolean nextTick) {
        restoreSlot();
        if (_attacks == 0) return finish(mod, false, "原船已消失，未确认可回收的船物品。");
        if (nextTick && ++_pickupTicks > 200) return finish(mod, false, "船物品未能拾取，继续原任务。");
        if (_drop == null || _drop.isRemoved() || !matches(_drop)) {
            _drop = null;
            for (Entity entity : _world.entitiesForRendering()) {
                if (entity instanceof ItemEntity item && !item.isRemoved() && !_oldDrops.contains(item.getUUID())
                        && matches(item) && item.position().distanceToSqr(_dropPosition) <= 9) {
                    if (_drop != null) return finish(mod, false, "附近出现多个同种船掉落，无法确认来源，停止可选回收。");
                    _drop = item;
                }
            }
        }
        if (_drop == null) {
            setDebugState("Waiting for this boat's server drop and inventory acknowledgement.");
            return null;
        }
        if (_drop.distanceToSqr(_player) > BoatRecoveryHelper.MAX_DISTANCE_SQUARED)
            return finish(mod, false, "船物品已漂远，继续原任务。");
        setDebugState("Picking up the recovered boat; confirming inventory increase.");
        return approach(_drop, .35);
    }

    private GetToEntityTask approach(Entity target, double range) {
        GetToEntityTask desired = new GetToEntityTask(target, range);
        if (_approach == null || !_approach.equals(desired)) _approach = desired;
        return _approach;
    }

    private boolean eligible(AbstractBoat boat) {
        return boat != null && BoatRecoveryHelper.eligible(_boatId, boat.getUUID(),
                boat.isRemoved() || !boat.isAlive(), !boat.getPassengers().isEmpty(),
                boat instanceof AbstractChestBoat, boat.isLeashed(), boat.isUnderWater(), boat.distanceToSqr(_player));
    }

    private AbstractBoat findBoat() {
        if (_boatId == null) return null;
        for (Entity entity : _world.entitiesForRendering()) {
            if (entity instanceof AbstractBoat boat && _boatId.equals(boat.getUUID())) return boat;
        }
        return null;
    }

    private boolean matches(ItemEntity item) {
        return ItemStack.isSameItemSameComponents(item.getItem(), _expected);
    }

    private boolean selectSafeHand() {
        int selected = _player.getInventory().getSelectedSlot();
        if (BoatRecoveryHelper.safeAttackStack(_player.getMainHandItem())) return true;
        int safeSlot = -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = _player.getInventory().getItem(slot);
            if (stack.isEmpty()) { safeSlot = slot; break; }
            if (safeSlot < 0 && BoatRecoveryHelper.safeAttackStack(stack)) safeSlot = slot;
        }
        if (safeSlot < 0) return false;
        if (_attackSlot < 0) _originalSlot = selected;
        _attackSlot = safeSlot;
        _player.getInventory().setSelectedSlot(safeSlot);
        return true;
    }

    private boolean clearAttack(Vec3 point) {
        Vec3 eye = _player.getEyePosition();
        var hit = _world.clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, _player));
        if (hit.getType() != HitResult.Type.MISS && eye.distanceToSqr(hit.getLocation()) < eye.distanceToSqr(point) - .0001)
            return false;
        // Do not swing through another mob, boat or player to reach the requested boat.
        for (Entity entity : _world.getEntities(_player, new AABB(eye, point).inflate(.05))) {
            if (entity == _boat || !entity.isPickable() || entity.isSpectator()) continue;
            if (entity.getBoundingBox().inflate(.03).clip(eye, point).isPresent()) return false;
        }
        return true;
    }

    private static Vec3 nearestPoint(Vec3 eye, AABB box) {
        return new Vec3(Mth.clamp(eye.x, box.minX + .02, box.maxX - .02),
                Mth.clamp(eye.y, box.minY + .02, box.maxY - .02),
                Mth.clamp(eye.z, box.minZ + .02, box.maxZ - .02));
    }

    private boolean hasWalkingChild() {
        return thisOrChildSatisfies(task -> task != this && task.isActive() && !task.stopped());
    }

    private Task finish(AltoClef mod, boolean succeeded, String result) {
        _done = true;
        _succeeded = succeeded;
        _result = result;
        setDebugState(result);
        if (_approach != null && _approach.isActive()) _approach.stop(mod);
        BoatNavigation.release(mod, this);
        restoreSlot();
        return null;
    }

    private void restoreSlot() {
        if (_player != null && _mod != null && _mod.getPlayer() == _player && _mod.level() == _world
                && _attackSlot >= 0 && _player.getInventory().getSelectedSlot() == _attackSlot)
            _player.getInventory().setSelectedSlot(_originalSlot);
        _attackSlot = -1;
        _originalSlot = -1;
    }

    @Override public boolean isFinished(AltoClef mod) { return _done; }
    @Override protected void onStop(AltoClef mod, Task interruptTask) { restoreSlot(); }
    @Override protected boolean isEqual(Task other) {
        return other instanceof BoatRecoveryTask task && Objects.equals(_boatId, task._boatId);
    }
    @Override protected String toDebugString() { return "Recover dismounted boat " + _boatId; }
}
