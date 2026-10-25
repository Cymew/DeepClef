package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.BoatNavigation;
import adris.altoclef.util.helpers.LookHelper;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.input.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Natural boat boarding and dismounting, with navigation owned by the boat controller. */
public final class BoatControlTask extends Task {
    public enum Action { BOARD, GOTO, DISMOUNT }

    private static final double SEARCH_RADIUS = 64;
    private final Action _action;
    private final Integer _requestedId;
    private final GoalXZ _goal;
    private AltoClef _mod;
    private LocalPlayer _player;
    private Level _level;
    private AbstractBoat _boat;
    private GetToEntityTask _approach;
    private AutoBoatBoardTask _autoBoard;
    private BoatRecoveryTask _recovery;
    private boolean _mountedOnce;
    private boolean _done;
    private String _result;
    private int _lastTick;
    private boolean _hasTick;
    private int _boardingTicks;
    private int _interactCooldown;
    private int _interactions;
    private int _blockedTicks;
    private int _noProgressTicks;
    private double _bestRemaining = Double.POSITIVE_INFINITY;
    private boolean _dismountSeen;
    private int _dismountTick;
    private int _dismountPrepareTicks, _dismountAckTicks;
    private boolean _dismountSent;
    private UUID _dismountBoatUUID;
    private int _originalSlot = -1;
    private int _boardingSlot = -1;

    private BoatControlTask(Action action, Integer entityId, GoalXZ goal) {
        _action = action;
        _requestedId = entityId;
        _goal = goal;
    }

    public static BoatControlTask board(Integer entityId) {
        return new BoatControlTask(Action.BOARD, entityId, null);
    }

    public static BoatControlTask travel(int x, int z, Integer entityId) {
        return new BoatControlTask(Action.GOTO, entityId, new GoalXZ(x, z));
    }

    public static BoatControlTask dismount() {
        return new BoatControlTask(Action.DISMOUNT, null, null);
    }

    /** Match SingleTaskChain's equality reuse so callbacks observe the task actually retained. */
    public static BoatControlTask forExecution(Task current, BoatControlTask requested) {
        return current instanceof BoatControlTask existing && existing.equals(requested) ? existing : requested;
    }

    /** Also distinguishes cancellation from a completed operation for model feedback. */
    public String getResult() {
        return _result == null ? "船操作已中断，尚未完成。" : _result;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _mod = mod;
        if (_player == null) {
            _player = mod.getPlayer();
            _level = mod.level();
        }
        // Time spent in a higher priority survival chain must not consume this task's budget.
        _hasTick = false;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        if (_done) return null;
        if (player == null || mod.level() == null || player != _player
                || mod.level() != _level || !player.isAlive()) {
            finish("船操作失败：玩家或世界状态已变化。");
            return null;
        }
        boolean nextTick = !_hasTick || _lastTick != player.tickCount;
        _lastTick = player.tickCount;
        _hasTick = true;
        if (_action == Action.DISMOUNT) return dismountTick(mod, nextTick);
        // The auto-board child must finish its own server acknowledgement and
        // release its lease before this parent starts navigation on the next tick.
        if (_autoBoard != null) return autoBoardTick(mod);

        AbstractBoat controlled = BoatNavigation.getControlledBoat(player);
        if (controlled != null) {
            if (_requestedId != null && controlled.getId() != _requestedId) {
                finish("船操作失败：当前驾驶的船不是指定实体，请先下船。");
                return null;
            }
            _boat = controlled;
            _mountedOnce = true;
            // Stop the walking child before acquiring a moving boat lease.
            if (hasWalkingChild()) {
                if (_approach != null) _approach.stop(mod);
                BoatNavigation.hold(mod, this);
                setDebugState("Stopping the approach before controlling the boat.");
                return null;
            }
            if (_action == Action.BOARD) {
                BoatNavigation.hold(mod, this);
                finish("已登船并取得驾驶权，船实体 ID=" + controlled.getId() + "。");
                return null;
            }
            if (BoatNavigation.isGoalReached(mod, _goal)) {
                BoatNavigation.hold(mod, this);
                finish("已驾船到达 X=" + _goal.getX() + " Z=" + _goal.getZ() + "；仍留在船上。");
                return null;
            }
            BoatNavigation.tick(mod, this, _goal);
            if (nextTick) {
                _blockedTicks = BoatNavigation.isBlocked(this) ? _blockedTicks + 1 : 0;
                double remaining = Math.hypot(controlled.getX() - (_goal.getX() + .5),
                        controlled.getZ() - (_goal.getZ() + .5));
                if (remaining <= _bestRemaining - .5) {
                    _bestRemaining = remaining;
                    _noProgressTicks = 0;
                } else if (remaining <= 1 && controlled.getDeltaMovement().horizontalDistance() < .04) {
                    // Blue-ice angular inertia can take longer than the travel stall budget
                    // to settle. The navigation goal still checks the full unpowered glide.
                    _noProgressTicks = 0;
                } else {
                    _noProgressTicks++;
                }
                if (_blockedTicks >= 160 || _noProgressTicks >= 600) {
                    finish("驾船停止：持续无法安全前进。" + BoatNavigation.getDebugState(this));
                }
            }
            if (!_done) setDebugState(BoatNavigation.getDebugState(this));
            return null;
        }
        BoatNavigation.release(mod, this);
        if (player.isPassenger()) {
            finish(player.getVehicle() instanceof AbstractBoat
                    ? "船操作失败：当前处于乘客座位，首座不由你驾驶；请先下船并选择空船。"
                    : "船操作失败：当前乘坐其他载具，请先下车后再登船。");
            return null;
        }
        if (_mountedOnce) {
            finish("驾船中断：已离开船，未自动重新登船。");
            return null;
        }
        if (nextTick && ++_boardingTicks > 600) {
            finish("登船失败：接近或等待登船超过 30 秒，请换一艘可接近的船。");
            return null;
        }
        if (_boat == null || !boardable(_boat, player) || _boat.level() != mod.level()) {
            _boat = findBoat(mod, _requestedId);
            _approach = null;
            if (_boat == null) {
                if (_requestedId != null) {
                    finish("登船失败：指定船未加载、已被占用、沉水、拴在你身上或不在 64 格内。");
                    return null;
                }
                _autoBoard = new AutoBoatBoardTask(preferredPlacementPoint(player, _goal));
                setDebugState("Preparing an inventory boat and safe water for boarding.");
                return _autoBoard;
            }
        }
        Vec3 point = interactionPoint(player, _boat);
        if (!player.isWithinEntityInteractionRange(_boat, 0) || !clearInteraction(mod, point)) {
            _interactCooldown = 0;
            if (_approach == null) _approach = new GetToEntityTask(_boat, 2);
            setDebugState("Approaching boat #" + _boat.getId() + ".");
            return _approach;
        }
        // Child cancellation happens after onTick; perform the interaction next frame.
        if (hasWalkingChild()) {
            setDebugState("Stopping the walking path before boarding.");
            return null;
        }
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        mod.getInputControls().release(Input.SNEAK);
        if (player.isSecondaryUseActive() || player.isUsingItem()) {
            setDebugState("Waiting for sneak/item use to finish before boarding.");
            return null;
        }
        LookHelper.lookAt(mod, point);
        if (nextTick && _interactCooldown > 0) --_interactCooldown;
        if (_interactCooldown > 0) {
            setDebugState("Waiting for the server to confirm boat boarding.");
            return null;
        }
        if (_interactions >= 4) {
            finish("登船失败：服务器未确认登船，请检查船是否能正常右键乘坐。");
            return null;
        }
        InteractionHand hand = boardingHand(player);
        if (hand == null) {
            finish("登船失败：双手和快捷栏均拿着拴绳或剪刀，请腾出一只手。");
            return null;
        }
        mod.getController().interact(player, _boat, new EntityHitResult(_boat, point), hand);
        _interactions++;
        _interactCooldown = 20;
        setDebugState("Boarding boat #" + _boat.getId() + "; waiting for server confirmation.");
        return null;
    }

    private Task dismountTick(AltoClef mod, boolean nextTick) {
        LocalPlayer player = mod.getPlayer();
        if (_recovery != null) {
            if (!_recovery.isFinished(mod)) return _recovery;
            String result = _recovery.getResult();
            if (_recovery.isActive()) _recovery.stop(mod);
            finish("已下船；" + result);
            return null;
        }
        if (player.isPassenger()) {
            if (!(player.getVehicle() instanceof AbstractBoat boat)) {
                finish("下船失败：当前乘坐的不是船。");
                return null;
            }
            if (_dismountBoatUUID == null) _dismountBoatUUID = boat.getUUID();
            else if (!_dismountBoatUUID.equals(boat.getUUID())) {
                finish("下船中断：当前乘坐的船已变化。");
                return null;
            }
            _dismountSeen = false;
            BoatNavigation.requestDismount(mod, this);
            if (nextTick) {
                if (BoatNavigation.isDismountRequested(this)) _dismountSent = true;
                if (_dismountSent && ++_dismountAckTicks > 100) {
                    finish("下船失败：已发送下船输入，但服务器尚未确认。");
                    return null;
                }
                if (!_dismountSent && ++_dismountPrepareTicks > 1200) {
                    finish("下船准备超时：船仍未停稳或没有安全下船位置。");
                    return null;
                }
            }
            setDebugState(BoatNavigation.getDebugState(this));
        } else {
            BoatNavigation.release(mod, this);
            if (!_dismountSeen) {
                _dismountSeen = true;
                _dismountTick = player.tickCount;
            } else if (player.tickCount - _dismountTick > 0) {
                _recovery = new BoatRecoveryTask(_dismountBoatUUID);
                setDebugState("Checking optional recovery of the dismounted boat.");
                return _recovery;
            }
        }
        return null;
    }

    private Task autoBoardTick(AltoClef mod) {
        BoatNavigation.release(mod, this);
        if (!_autoBoard.isFinished(mod)) return _autoBoard;
        boolean success = _autoBoard.succeeded();
        String result = _autoBoard.getResult();
        if (_autoBoard.isActive()) _autoBoard.stop(mod);
        _autoBoard = null;
        if (!success) {
            finish(result);
            return null;
        }
        AbstractBoat boat = BoatNavigation.getControlledBoat(mod.getPlayer());
        if (boat == null) {
            finish("自动登船中断：服务器确认后已离开驾驶位。");
            return null;
        }
        _boat = boat;
        _mountedOnce = true;
        setDebugState("Automatic boarding completed; handing control to boat navigation.");
        // Returning null stops the old child in Task.tick. Even though the child
        // is already done, taking this parent's lease here would race cleanup.
        return null;
    }

    private static Vec3 preferredPlacementPoint(LocalPlayer player, GoalXZ goal) {
        Vec3 direction = goal == null ? player.getLookAngle()
                : new Vec3(goal.getX() + .5 - player.getX(), 0, goal.getZ() + .5 - player.getZ());
        direction = new Vec3(direction.x, 0, direction.z).normalize();
        if (direction.lengthSqr() < .5) direction = new Vec3(0, 0, 1);
        return player.position().add(direction.scale(3));
    }

    private boolean hasWalkingChild() {
        return thisOrChildSatisfies(task -> task != this && task.isActive() && !task.stopped());
    }

    private InteractionHand boardingHand(LocalPlayer player) {
        if (!changesLeash(player.getMainHandItem())) return InteractionHand.MAIN_HAND;
        if (!changesLeash(player.getOffhandItem())) return InteractionHand.OFF_HAND;
        for (int slot = 0; slot < 9; slot++) {
            if (changesLeash(player.getInventory().getItem(slot))) continue;
            _originalSlot = player.getInventory().getSelectedSlot();
            _boardingSlot = slot;
            player.getInventory().setSelectedSlot(slot);
            return InteractionHand.MAIN_HAND;
        }
        return null;
    }

    private static boolean changesLeash(ItemStack stack) {
        return stack.is(Items.LEAD) || stack.is(Items.SHEARS);
    }

    private static Vec3 interactionPoint(LocalPlayer player, AbstractBoat boat) {
        AABB box = boat.getBoundingBox();
        Vec3 eye = player.getEyePosition();
        return new Vec3(Mth.clamp(eye.x, box.minX + .02, box.maxX - .02),
                Mth.clamp(eye.y, box.minY + .02, box.maxY - .02),
                Mth.clamp(eye.z, box.minZ + .02, box.maxZ - .02));
    }

    private static boolean clearInteraction(AltoClef mod, Vec3 point) {
        Vec3 eye = mod.getPlayer().getEyePosition();
        var hit = mod.level().clip(new ClipContext(eye, point,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mod.getPlayer()));
        return hit.getType() == HitResult.Type.MISS
                || eye.distanceToSqr(hit.getLocation()) >= eye.distanceToSqr(point) - .0001;
    }

    private static boolean boardable(AbstractBoat boat, LocalPlayer player) {
        return boat.isAlive() && !boat.isRemoved() && boat.getFirstPassenger() == null
                && !boat.isEyeInFluid(FluidTags.WATER) && !boat.isUnderWater()
                && boat.getLeashHolder() != player
                && boat.distanceToSqr(player) <= SEARCH_RADIUS * SEARCH_RADIUS;
    }

    private static AbstractBoat findBoat(AltoClef mod, Integer requestedId) {
        if (requestedId != null) {
            Entity entity = mod.level().getEntity(requestedId);
            return entity instanceof AbstractBoat boat && boardable(boat, mod.getPlayer()) ? boat : null;
        }
        return nearbyBoats(mod).stream().filter(boat -> boardable(boat, mod.getPlayer())).findFirst().orElse(null);
    }

    private static List<AbstractBoat> nearbyBoats(AltoClef mod) {
        List<AbstractBoat> result = new ArrayList<>();
        if (mod.getPlayer() == null || mod.level() == null) return result;
        for (Entity entity : mod.level().entitiesForRendering()) {
            if (entity instanceof AbstractBoat boat && boat.isAlive() && !boat.isRemoved()
                    && boat.distanceToSqr(mod.getPlayer()) <= SEARCH_RADIUS * SEARCH_RADIUS) result.add(boat);
        }
        result.sort(Comparator.comparingDouble((AbstractBoat boat) -> boat.distanceToSqr(mod.getPlayer()))
                .thenComparingInt(Entity::getId));
        return result;
    }

    public static String ridingStatus(LocalPlayer player) {
        if (player == null || player.getVehicle() == null) return "未乘坐载具";
        Entity vehicle = player.getVehicle();
        return vehicle.getName().getString() + " ID=" + vehicle.getId()
                + (vehicle instanceof AbstractBoat boat
                ? (boat.getControllingPassenger() == player ? "（船驾驶位）" : "（船乘客位，无驾驶权）") : "");
    }

    /** Lists actual loaded boats, including chest boats and rafts, for both command and model. */
    public static String status(AltoClef mod) {
        if (mod.getPlayer() == null || mod.level() == null) return "无法读取船：未进入世界。";
        StringBuilder result = new StringBuilder("当前载具：").append(ridingStatus(mod.getPlayer())).append('\n');
        if (mod.getPlayer().getVehicle() instanceof AbstractBoat)
            result.append("驾驶状态：").append(BoatNavigation.status(mod)).append('\n');
        List<AbstractBoat> boats = nearbyBoats(mod);
        if (boats.isEmpty()) return result.append("64 格内无已放置的船；board/goto 可从背包放置普通船或竹筏，并在安全水面登船。\n").toString();
        result.append("附近已加载船（board 可传 entity_id）：\n");
        for (int index = 0; index < Math.min(20, boats.size()); index++) {
            AbstractBoat boat = boats.get(index);
            String availability = boat.getControllingPassenger() == mod.getPlayer() ? "正在驾驶"
                    : boardable(boat, mod.getPlayer()) ? "可登船并驾驶"
                    : boat.getFirstPassenger() != null ? "首座已占用"
                    : boat.getLeashHolder() == mod.getPlayer() ? "拴在你身上，需先解绳" : "沉水，暂不可登船";
            result.append("ID=").append(boat.getId()).append(" ").append(boat.getName().getString())
                    .append(" | 距离 ").append(Math.round(boat.distanceTo(mod.getPlayer())))
                    .append(" | 坐标 ").append(boat.blockPosition().getX()).append(' ')
                    .append(boat.blockPosition().getY()).append(' ').append(boat.blockPosition().getZ())
                    .append(" | ").append(availability).append('\n');
        }
        return result.toString();
    }

    private void finish(String result) {
        _done = true;
        _result = result;
        setDebugState(result);
        // UserTaskChain's normal completion callback does not call Task.onStop.
        if (_mod != null) {
            if (_approach != null && _approach.isActive()) _approach.stop(_mod);
            if (_autoBoard != null && _autoBoard.isActive()) _autoBoard.stop(_mod);
            if (_recovery != null && _recovery.isActive()) _recovery.stop(_mod);
            BoatNavigation.release(_mod, this);
            restoreBoardingSlot(_mod);
        }
    }

    @Override
    public boolean isFinished(AltoClef mod) { return _done; }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        BoatNavigation.release(mod, this);
        restoreBoardingSlot(mod);
    }

    private void restoreBoardingSlot(AltoClef mod) {
        if (_player != null && mod.getPlayer() == _player && _boardingSlot >= 0
                && _player.getInventory().getSelectedSlot() == _boardingSlot) {
            _player.getInventory().setSelectedSlot(_originalSlot);
        }
        _boardingSlot = -1;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BoatControlTask task && task._action == _action
                && Objects.equals(task._requestedId, _requestedId) && Objects.equals(task._goal, _goal);
    }

    @Override
    protected String toDebugString() {
        return "Boat " + _action + (_requestedId == null ? "" : " #" + _requestedId)
                + (_goal == null ? "" : " " + _goal.getX() + " " + _goal.getZ());
    }
}
