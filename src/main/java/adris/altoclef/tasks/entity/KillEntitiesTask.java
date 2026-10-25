package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.SpearHelper;
import adris.altoclef.util.helpers.MountedCombat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Kill all entities of a type
 */
public class KillEntitiesTask extends DoToClosestEntityTask {
    private final Predicate<Entity> _shouldKill;
    private final Class<?>[] _entities;

    public KillEntitiesTask(Predicate<Entity> shouldKill, Class<?>... entities) {
        super(KillEntityTask::new, shouldKill, entities);
        _shouldKill = shouldKill;
        _entities = entities;
    }

    public KillEntitiesTask(Class<?>... entities) {
        this(entity -> true, entities);
    }

    @Override
    protected Optional<Entity> getClosestTo(AltoClef mod, Vec3 pos) {
        Optional<Entity> closest = super.getClosestTo(mod, pos);
        Optional<Entity> committed = MountedCombat.getTarget().or(SpearHelper::getCombatTarget)
                .filter(entity -> isValid(mod, entity));
        if (committed.isPresent() && (SpearHelper.isCharging() || closest.isEmpty()
                || keepCombatTarget(committed.get().distanceTo(mod.getPlayer()),
                closest.get().distanceTo(mod.getPlayer())))) return committed;
        return closest;
    }

    /** Switching near-equal targets resets aim/recovery; require a useful distance gain. */
    public static boolean keepCombatTarget(double currentDistance, double closestDistance) {
        return currentDistance <= 12 && currentDistance <= closestDistance + 2;
    }

    @Override
    protected boolean isValid(AltoClef mod, Entity entity) {
        return super.isValid(mod, entity) && _shouldKill.test(entity)
                && Arrays.stream(_entities).anyMatch(type -> type.isInstance(entity));
    }
}
