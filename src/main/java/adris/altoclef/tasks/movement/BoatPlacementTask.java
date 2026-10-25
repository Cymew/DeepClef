package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.BoatPlacementHelper;
import adris.altoclef.util.helpers.LookHelper;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Places one inventory boat using native item use, then awaits its server entity. */
public final class BoatPlacementTask extends Task {
    private final Vec3 _preferredPoint;
    private final Set<UUID> _existing = new HashSet<>();
    private LocalPlayer _player;
    private Level _level;
    private AltoClef _mod;
    private Item _item;
    private Vec3 _expected;
    private boolean _requested, _done, _success;
    private int _ticks, _lastTick = Integer.MIN_VALUE, _originalSlot = -1, _equippedSlot = -1;
    private int _placedId = -1;
    private UUID _placedUUID;
    private String _result;

    public BoatPlacementTask() { this(null); }
    public BoatPlacementTask(Vec3 preferredPoint) { _preferredPoint = preferredPoint; }

    public int getPlacedBoatId() { return _placedId; }
    public UUID getPlacedBoatUUID() { return _placedUUID; }
    public boolean succeeded() { return _done && _success; }
    public String getResult() { return _result == null ? "放船尚未完成或已被中断。" : _result; }

    @Override protected void onStart(AltoClef mod) {
        _mod = mod;
        if (_player == null) { _player = mod.getPlayer(); _level = mod.level(); }
        _lastTick = Integer.MIN_VALUE;
    }

    @Override protected Task onTick(AltoClef mod) {
        if (_done) return null;
        LocalPlayer player = mod.getPlayer();
        if (player == null || player != _player || _level == null || mod.level() != _level || !player.isAlive()) {
            finish(false, "放船失败：玩家或世界已变化。");
            return null;
        }
        boolean nextTick = _lastTick != player.tickCount;
        _lastTick = player.tickCount;
        if (nextTick) _ticks++;
        if (_requested) {
            // Never resend after useItem: an unacknowledged packet may still place
            // a boat on the server, even when the client-side use returned PASS.
            AbstractBoat found = findNewBoat(mod);
            if (found != null) {
                _placedId = found.getId();
                _placedUUID = found.getUUID();
                finish(true, "已放置船，实体 ID=" + _placedId + "。");
            } else if (_ticks >= 120) {
                finish(false, "放船失败：未收到服务器的新船确认；为避免重复消耗，不再次放置。");
            }
            return null;
        }
        if (player.isPassenger()) {
            finish(false, "放船失败：当前仍在载具上。");
            return null;
        }
        if (_ticks > 120) {
            finish(false, "放船失败：附近没有可交互且船身和乘员净空足够的安全水面。");
            return null;
        }
        if (player.isUsingItem() || mod.getFoodChain().isTryingToEat()) {
            setDebugState("Waiting for the hand before placing the boat.");
            return null;
        }
        // Do not manipulate slots while another container or a cursor stack owns them.
        if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) {
            setDebugState("Waiting for the player inventory before placing the boat.");
            return null;
        }
        if (!BoatPlacementHelper.hasBoatInInventory(player)) {
            finish(false, "放船失败：背包没有普通船或竹筏。");
            return null;
        }
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        if (!nextTick || _ticks % 5 != 1) return null;
        var site = BoatPlacementHelper.findSite(mod, _preferredPoint);
        if (site.isEmpty()) {
            setDebugState("Looking for reachable, clear water for one boat.");
            return null;
        }
        InteractionHand hand = equip(mod);
        if (hand == null || !BoatPlacementHelper.isOrdinaryBoatItem(player.getItemInHand(hand))) {
            setDebugState("Moving an inventory boat to the hotbar.");
            return null;
        }
        LookHelper.lookAt(mod, site.get().aim());
        var actual = BoatPlacementHelper.currentViewSite(mod);
        if (actual.isEmpty() || actual.get().placement().distanceToSqr(site.get().placement()) > .04) {
            setDebugState("Rechecking the native boat item ray.");
            return null;
        }
        _existing.clear();
        for (Entity entity : mod.level().entitiesForRendering()) {
            if (entity instanceof AbstractBoat boat) _existing.add(boat.getUUID());
        }
        _item = player.getItemInHand(hand).getItem();
        _expected = actual.get().placement();
        _requested = true;
        _ticks = 0;
        mod.getController().useItem(player, hand);
        setDebugState("Placed one boat item; waiting for the server entity.");
        return null;
    }

    private InteractionHand equip(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        if (BoatPlacementHelper.isOrdinaryBoatItem(player.getMainHandItem())) return InteractionHand.MAIN_HAND;
        if (BoatPlacementHelper.isOrdinaryBoatItem(player.getOffhandItem())) return InteractionHand.OFF_HAND;
        if (_originalSlot < 0) _originalSlot = player.getInventory().getSelectedSlot();
        for (int slot = 0; slot < 9; slot++) {
            if (!BoatPlacementHelper.isOrdinaryBoatItem(player.getInventory().getItem(slot))) continue;
            player.getInventory().setSelectedSlot(slot);
            _equippedSlot = slot;
            return InteractionHand.MAIN_HAND;
        }
        mod.getSlotHandler().forceEquipItem(BoatPlacementHelper.ordinaryBoats(), false);
        _equippedSlot = player.getInventory().getSelectedSlot();
        return BoatPlacementHelper.isOrdinaryBoatItem(player.getMainHandItem()) ? InteractionHand.MAIN_HAND : null;
    }

    private AbstractBoat findNewBoat(AltoClef mod) {
        AbstractBoat match = null;
        for (AbstractBoat boat : _level.getEntitiesOfClass(AbstractBoat.class,
                _player.getBoundingBox().inflate(16))) {
            if (!boat.isAlive() || boat.isRemoved() || _existing.contains(boat.getUUID())
                    || !BoatPlacementHelper.matchesItem(boat, _item)
                    || boat.position().distanceToSqr(_expected) > 9) continue;
            // Ambiguous concurrent placements cannot safely be claimed for boarding/recovery.
            if (match != null) return null;
            match = boat;
        }
        return match;
    }

    private void restoreSlot() {
        if (_player != null && _mod != null && _mod.getPlayer() == _player
                && _mod.level() == _level && _originalSlot >= 0 && _equippedSlot >= 0
                && _player.getInventory().getSelectedSlot() == _equippedSlot) {
            _player.getInventory().setSelectedSlot(_originalSlot);
        }
        _equippedSlot = -1;
    }

    private void finish(boolean success, String result) {
        _done = true; _success = success; _result = result;
        setDebugState(result);
        restoreSlot();
    }

    @Override public boolean isFinished(AltoClef mod) { return _done; }
    @Override protected void onStop(AltoClef mod, Task interruptTask) { restoreSlot(); }
    @Override protected boolean isEqual(Task other) {
        return other instanceof BoatPlacementTask task && Objects.equals(_preferredPoint, task._preferredPoint);
    }
    @Override protected String toDebugString() { return "Placing an inventory boat"; }
}
