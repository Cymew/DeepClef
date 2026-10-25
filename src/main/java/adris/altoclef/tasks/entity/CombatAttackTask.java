package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.CombatPreferences;
import adris.altoclef.util.helpers.PvpCombat;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import java.util.Optional;

/**
 * Attack one target with the currently selected {@link CombatPreferences.Style}.
 *
 * <p>The target may be a loaded player name (PvP) or an entity type such as
 * {@code zombie} / {@code minecraft:skeleton}. The actual fighting is delegated
 * to {@link PvpCombat}, which honours the style and adds shield/strafing.
 */
public class CombatAttackTask extends Task {

    /** Give up when no matching target is visible for this long. */
    private static final int GIVE_UP_TICKS = 20 * 20;

    private final String _targetSpec;
    private Entity _target;
    private int _lostTicks;
    private boolean _giveUp;

    public CombatAttackTask(String targetSpec) {
        _targetSpec = targetSpec == null ? "" : targetSpec.trim();
    }

    @Override
    protected void onStart(AltoClef mod) {
        _target = null;
        _lostTicks = 0;
        _giveUp = false;
        PvpCombat.reset(mod);
        adris.altoclef.util.helpers.StorageHelper.closeInterferingScreens();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (!AltoClef.inGame()) return null;
        if (_target == null || !_target.isAlive() || _target.isRemoved()) {
            _target = findTarget(mod).orElse(null);
        }
        if (_target == null) {
            _lostTicks++;
            setDebugState("Waiting for " + _targetSpec);
            if (_lostTicks >= GIVE_UP_TICKS) {
                _giveUp = true;
                return null;
            }
            return new TimeoutWanderTask(2);
        }
        _lostTicks = 0;
        setDebugState("Combat " + CombatPreferences.getStyle() + " vs "
                + _target.getName().getString() + " (" + PvpCombat.getMode() + ")");
        return PvpCombat.tick(mod, _target);
    }

    private Optional<Entity> findTarget(AltoClef mod) {
        // A loaded player name wins over entity types (PvP).
        if (mod.getEntityTracker().isPlayerLoaded(_targetSpec)) {
            return mod.getEntityTracker().getPlayerEntity(_targetSpec).map(player -> (Entity) player);
        }
        EntityType<?> type = resolveType(_targetSpec);
        if (type == null || mod.level() == null || mod.getPlayer() == null) return Optional.empty();
        Entity best = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity entity : mod.level().entitiesForRendering()) {
            if (!(entity instanceof LivingEntity) || !entity.isAlive() || entity.isRemoved()) continue;
            if (entity == mod.getPlayer() || entity.getType() != type) continue;
            double sq = entity.distanceToSqr(mod.getPlayer());
            if (sq < bestSq) {
                bestSq = sq;
                best = entity;
            }
        }
        return Optional.ofNullable(best);
    }

    private static EntityType<?> resolveType(String spec) {
        if (spec == null || spec.isBlank()) return null;
        Identifier id = Identifier.tryParse(spec.contains(":") ? spec : "minecraft:" + spec);
        if (id == null) return null;
        return BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
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
        return other instanceof CombatAttackTask task && task._targetSpec.equals(_targetSpec);
    }

    @Override
    protected String toDebugString() {
        return "Combat attack " + _targetSpec;
    }
}
