package adris.altoclef.util;

import adris.altoclef.AltoClef;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Bounded, session-local references acquired from visible surfaces of entities. */
final class VisibleEntityTargets {
    record Sight(Entity entity, Vec3 feet, Vec3 aim, int tick) {
        boolean needsApproach(Vec3 eye, double reach) { return eye.distanceTo(aim) > reach - .15; }
    }
    private final Map<String, Sight> sightings = new LinkedHashMap<>(16, .75f, true);
    private final String prefix = "mob-" + UUID.randomUUID().toString().substring(0,8) + "-";
    private Object level, connection;
    private long serial;

    static Vec3 visiblePoint(AltoClef mod, Entity e) {
        return visiblePoint(mod, e, true);
    }

    static Vec3 visiblePoint(AltoClef mod, Entity e, boolean requireView) {
        if (e.level() != mod.level() || e == mod.getPlayer() || !e.isAlive() || e.isSpectator() || e.isInvisibleTo(mod.getPlayer())) return null;
        var center = e.getBoundingBox().getCenter();
        if (SceneObservation.visibleEntityPoint(mod, center, requireView)) return center;
        return SceneObservation.visibleEntityPoint(mod, e.getEyePosition(), requireView) ? e.getEyePosition() : null;
    }

    private void refresh(AltoClef mod) {
        var player = mod.getPlayer();
        if (level != mod.level() || connection != player.connection) {
            sightings.clear();
            level = mod.level();
            connection = player.connection;
        }
        sightings.values().removeIf(s -> player.tickCount - s.tick() > 1200);
    }

    String remember(Sight sight) {
        String ref = null;
        for (var entry : sightings.entrySet()) {
            if (entry.getValue().entity() == sight.entity()) { ref = entry.getKey(); break; }
        }
        if (ref == null) ref = prefix + (++serial);
        sightings.put(ref, sight);
        if (sightings.size() > 64) sightings.remove(sightings.keySet().iterator().next());
        return ref;
    }

    void update(String ref, Sight sight) {
        if (sightings.containsKey(ref)) sightings.put(ref, sight);
    }

    Sight resolve(AltoClef mod, String ref) {
        refresh(mod);
        var sight = sightings.get(ref);
        if (sight == null) throw new IllegalArgumentException("实体引用已过期，请原地按名称查询 entities");
        return sight;
    }

    String list(AltoClef mod, int radius, String keyword, boolean livingOnly) {
        var player = mod.getPlayer();
        if (player == null || mod.level() == null) return "当前未进入世界";
        refresh(mod);
        String query = keyword == null ? "" : keyword.strip().toLowerCase(Locale.ROOT);
        var candidates = mod.level().getEntitiesOfClass(Entity.class, player.getBoundingBox().inflate(radius),
                e -> (!livingOnly || e instanceof LivingEntity) && player.distanceToSqr(e) <= radius * radius);
        candidates.sort(Comparator.comparingDouble(player::distanceToSqr));
        var lines = new ArrayList<String>();
        for (var e : candidates) {
            String label = e.getName().getString() + " " + e.getType().getDescription().getString() + " " + BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            if (!query.isEmpty() && !label.toLowerCase(Locale.ROOT).contains(query)) continue;
            var point = visiblePoint(mod, e);
            if (point == null) continue;
            String ref = remember(new Sight(e, e.position(), point, player.tickCount));
            String state = e instanceof Sheep sheep ? " " + (sheep.isBaby() ? "幼年" : "成年") + (sheep.isSheared() ? " 已剪毛" : " 有羊毛") : "";
            lines.add("target_ref=" + ref + " " + label + state + " 位置=" + e.blockPosition().toShortString()
                    + " 约" + Math.round(player.distanceTo(e)) + "格 " + DeepSeekAgent.direction(e.position().subtract(player.position())));
            if (lines.size() == 3) break;
        }
        return lines.isEmpty() ? "视野内未辨认到匹配实体。" : String.join("\n", lines);
    }
}
