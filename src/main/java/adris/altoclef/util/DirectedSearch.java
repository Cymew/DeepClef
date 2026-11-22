package adris.altoclef.util;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasksystem.Task;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;

/** An intentional search spans observations and path segments, with no model call between them. */
final class DirectedSearch extends SurvivalAction {
    private static final float[] PITCH = {0, 3, -4, 9, -12, 19, -25, 38};
    private static final int HEADINGS = 64;
    private final String key;
    private final Block block;
    private final EntityType<?> type;
    private Vec3 heading;
    private Vec3 start;
    private Vec3 sight;
    private Entity subject;
    private GetToXZTask leg;
    private BlockPos destination;
    private SurvivalAction harvest;
    private int cursor, legs, progressTick, nextTrack;
    private double best;
    private String observation = "正在从当前站位定向查询";
    private boolean surveyOnly;
    private boolean gatherBlock;
    DirectedSearch forHarvest() { gatherBlock = true; return this; }
    private boolean hasSight;
    private boolean explored;
    boolean completedExcursion() { return explored; }
    boolean exploring() { return key.equals("explore"); }
    private int trunkApproaches;
    private BlockPos observedTrunk;
    private final java.util.Set<BlockPos> viewpoints = new java.util.HashSet<>();
    private int startingLogs;

    boolean collectingWood() { return key.equals("tree") && !surveyOnly; }

    boolean seeksTimber() {
        return !surveyOnly && (collectingWood() || block != null && block.defaultBlockState().is(BlockTags.LOGS));
    }

    private static int logCount(net.minecraft.client.player.LocalPlayer player) {
        return player.getInventory().getNonEquipmentItems().stream()
                .filter(stack -> stack.is(net.minecraft.tags.ItemTags.LOGS))
                .mapToInt(net.minecraft.world.item.ItemStack::getCount).sum();
    }

    @Override void begin(net.minecraft.client.player.LocalPlayer player) {
        super.begin(player);
        startingLogs = logCount(player);
    }

    boolean gainedWood(net.minecraft.client.player.LocalPlayer player) { return logCount(player) > startingLogs; }

    static boolean preferSighting(boolean hasTarget, boolean previousTrunk, boolean trunk,
                                  double previousDistance, double distance) {
        if (!hasTarget) return true;
        if (trunk != previousTrunk) return trunk;
        return distance < previousDistance;
    }


    private DirectedSearch(String label, String key, Block block, EntityType<?> type, Vec3 heading) {
        super(label);
        this.key = key;
        this.block = block;
        this.type = type;
        this.heading = heading;
    }

    static DirectedSearch target(String key) {
        if (key.equals("tree")) return new DirectedSearch("寻找并接近树木", key, null, null, null);
        int split = key.indexOf(':');
        if (split <= 0) return null;
        var id = Identifier.tryParse(key.substring(split + 1));
        if (id == null) return null;
        return switch (key.substring(0, split)) {
            case "entity" -> BuiltInRegistries.ENTITY_TYPE.getOptional(id).map(t ->
                    new DirectedSearch("寻找并接近" + t.getDescription().getString(), key, null, t, null)).orElse(null);
            case "block" -> BuiltInRegistries.BLOCK.getOptional(id).map(b ->
                    new DirectedSearch("寻找并接近" + b.getName().getString(), key, b, null, null)).orElse(null);
            default -> null;
        };
    }

    record Selection(DirectedSearch search, boolean usedFallback) {}

    /** A numbered candidate is already bound. An optional registry query may replace it. */
    static Selection resolve(String target, DirectedSearch numbered, boolean readOnly,
                             java.util.function.Predicate<String> coolingDown) {
        if (!target.isBlank()) {
            var requested = target(target);
            if (requested != null) {
                if (numbered != null && numbered.gatherBlock && numbered.key.equals(target) && !readOnly) requested = numbered;
                if (readOnly) requested.queryOnly();
                if (!coolingDown.test(requested.label())) return new Selection(requested, false);
            }
        }
        if (readOnly && numbered != null) {
            if (numbered.key.equals("explore") || coolingDown.test(numbered.queryOnly().label())) numbered = null;
        }
        return new Selection(numbered, !target.isBlank());
    }

    static DirectedSearch exploreForWood(Vec3 direction) {
        return new DirectedSearch("向" + SceneObservation.bearing(direction) + "寻找并采集原木", "tree", null, null,
                direction.multiply(1, 0, 1).normalize());
    }

    static DirectedSearch explore(Vec3 direction) {
        return new DirectedSearch("持续向" + SceneObservation.bearing(direction) + "探索", "explore", null, null,
                direction.multiply(1, 0, 1).normalize());
    }

    DirectedSearch queryOnly() { surveyOnly = !key.equals("explore"); return this; }
    @Override String label() { return surveyOnly ? "查询 " + key : gatherBlock ? "寻找并采集" + block.getName().getString() : super.label(); }

    String progress() { return observation; }
    boolean matches(BlockState state) {
        return key.equals("tree") ? state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) : state.is(block);
    }
    private void consider(AltoClef mod, BlockHitResult hit) {
        if (SurvivalAction.harvestHit(hit, face -> SceneObservation.lit(mod, face),
                pos -> matches(mod.level().getBlockState(pos))) == null) return;
        var point = hit.getLocation();
        boolean trunk = collectingWood() && mod.level().getBlockState(hit.getBlockPos()).is(BlockTags.LOGS)
                && SurvivalAction.harvestable(mod, hit.getBlockPos());
        if (preferSighting(sight != null, observedTrunk != null, trunk,
                sight == null ? 0 : mod.getPlayer().distanceToSqr(sight), mod.getPlayer().distanceToSqr(point))) {
            sight = point;
            observedTrunk = trunk ? hit.getBlockPos() : null;
        }
    }

    private static boolean seen(AltoClef mod, Entity e, double range) {
        var p = mod.getPlayer();
        if (e == p || !e.isAlive() || e.isRemoved() || e.level() != mod.level() || e.isSpectator() || e.isInvisibleTo(p)) return false;
        var point = e.getBoundingBox().getCenter();
        var delta = point.subtract(p.getEyePosition());
        return delta.length() <= range && SceneObservation.lit(mod, e.blockPosition())
                && SceneObservation.unobstructedTo(SceneObservation.cast(mod, delta.normalize(), delta.length()), point);
    }

    private boolean survey(AltoClef mod, double range) {
        if (key.equals("explore")) return true;
        var p = mod.getPlayer();
        if (type != null) {
            var nearby = mod.level().getEntitiesOfClass(Entity.class, p.getBoundingBox().inflate(range), e -> e.getType() == type);
            subject = nearby.stream().sorted(Comparator.comparingDouble(p::distanceToSqr))
                    .filter(e -> seen(mod, e, range)).findFirst().orElse(null);
            sight = subject == null ? null : subject.position();
            return true;
        }
        if (cursor == 0) {
            for (var hit : SceneObservation.surroundings(mod)) consider(mod, hit);
            if (key.equals("tree")) for (var pos : mod.scene().treeBearings()) {
                Vec3 dir = Vec3.atCenterOf(pos).subtract(p.getEyePosition()).normalize();
                consider(mod, SceneObservation.cast(mod, dir, range));
            }
        }
        // Bound long rays per client tick; each ray stops at opaque outlines and unloaded chunks.
        int end = Math.min(cursor + 24, HEADINGS * PITCH.length);
        for (; cursor < end; cursor++)
            consider(mod, SceneObservation.cast(mod, SceneObservation.compassRay(cursor % HEADINGS, HEADINGS, PITCH[cursor / HEADINGS]), range));
        return cursor == HEADINGS * PITCH.length;
    }

    static String terrain(boolean water, boolean rise, boolean dip) {
        var features = new java.util.ArrayList<String>();
        if (water) features.add("水面，可能需要渡水");
        if (rise) features.add("高地或陡壁");
        if (dip) features.add("明显低地，可能有沟谷");
        return features.isEmpty() ? "沿线未辨认出明显障碍，遮挡处未知" : "沿线可见" + String.join("、", features) + "，遮挡处未知";
    }

    private String corridor(AltoClef mod, Vec3 end) {
        var p = mod.getPlayer();
        var eye = p.getEyePosition();
        var flat = end.subtract(eye).multiply(1, 0, 1);
        boolean water = false, rise = false, dip = false;
        for (int pitch : new int[]{-15, -6, 0, 4, 9, 18, 32, 50}) {
            var dir = flat.normalize().add(0, -Math.tan(Math.toRadians(pitch)), 0).normalize();
            var hit = SceneObservation.cast(mod, dir, Math.min(SceneObservation.range(Minecraft.getInstance()),
                    flat.length() / Math.cos(Math.toRadians(pitch))));
            if (hit.getType() != HitResult.Type.BLOCK || !SceneObservation.lit(mod, hit.getBlockPos().relative(hit.getDirection()))) continue;
            var pos = hit.getBlockPos();
            var state = mod.level().getBlockState(pos);
            water |= state.getFluidState().is(FluidTags.WATER) && state.getCollisionShape(mod.level(), pos).isEmpty();
            boolean ground = state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.DIRT)
                    || state.is(BlockTags.SAND) || state.is(BlockTags.SNOW) || state.is(net.minecraft.world.level.block.Blocks.GRAVEL);
            rise |= ground && pos.getY() >= p.blockPosition().getY() + 4;
            dip |= ground && pos.getY() <= p.blockPosition().getY() - 5;
        }
        return terrain(water, rise, dip);
    }

    // Find a fresh visible waypoint moving forward on the same heading. No circular fallback.
    private BlockPos advance(AltoClef mod) {
        var p = mod.getPlayer();
        BlockPos next = null;
        double bestForward = 2;
        for (var hit : SceneObservation.surroundings(mod)) {
            var pos = hit.getBlockPos();
            if (mod.level().getBlockState(pos).getCollisionShape(mod.level(), pos).isEmpty()) pos = pos.below();
            if (!PlayerRoutine.validLanding(mod, pos) || SceneObservation.surfaceHit(mod, pos, false) == null) continue;
            var step = Vec3.atBottomCenterOf(pos.above()).subtract(p.position());
            double forward = step.dot(heading);
            if (forward > bestForward && forwardStep(step, heading)) { next = pos.above(); bestForward = forward; }
        }
        return next;
    }

    static boolean forwardStep(Vec3 step, Vec3 heading) {
        return step.dot(heading) > Math.max(2, step.horizontalDistance() * .65);
    }

    static boolean canopyViewpoint(Vec3 player, Vec3 canopy, BlockPos floor) {
        var feet = Vec3.atBottomCenterOf(floor.above());
        double around = feet.subtract(canopy).horizontalDistance();
        return around >= 2 && around <= 8 && feet.subtract(player).horizontalDistance() >= 3;
    }

    private Task atSighting(AltoClef mod) {
        var p = mod.getPlayer();
        if (subject != null) {
            if (seen(mod, subject, SceneObservation.range(Minecraft.getInstance())) && subject.distanceToSqr(p) <= 16)
                return finish(observation + "；已在近处确认同一实体");
            return finish(observation + "；已到最后目击位置，未确认接近同一实体");
        }
        var local = new java.util.ArrayList<>(SceneObservation.surroundings(mod));
        if (collectingWood()) {
            for (int sign : new int[]{-1, 1}) {
                var vertical = SceneObservation.cast(mod, new Vec3(0, sign, 0),
                        Math.min(p.blockInteractionRange(), SceneObservation.range(Minecraft.getInstance())));
                if (vertical.getType() == HitResult.Type.BLOCK
                        && SceneObservation.lit(mod, vertical.getBlockPos().relative(vertical.getDirection()))) local.add(vertical);
            }
        }
        if (observedTrunk != null) {
            var refreshed = SceneObservation.surfaceHit(mod, observedTrunk, false);
            if (refreshed != null) local.add(refreshed);
        }
        var hits = local.stream()
                .sorted(Comparator.comparingDouble(hit -> p.getEyePosition().distanceToSqr(hit.getLocation()))).toList();
        if (key.equals("tree") && !p.getAbilities().instabuild) {
            for (var hit : hits) {
                var pos = hit.getBlockPos();
                var state = mod.level().getBlockState(pos);
                if (!state.is(BlockTags.LOGS) || !SurvivalAction.harvestable(mod, pos)) continue;
                if (p.getEyePosition().distanceTo(hit.getLocation()) <= p.blockInteractionRange()) {
                    harvest = SurvivalAction.harvest(pos, state.getBlock());
                    return harvest;
                }
                if (trunkApproaches < 2 && p.position().subtract(Vec3.atCenterOf(pos)).horizontalDistance() > 2.5) {
                    trunkApproaches++;
                    return walk(mod, pos);
                }
            }
            if (viewpoints.size() < 3) {
                var floor = hits.stream().map(BlockHitResult::getBlockPos).distinct()
                        .filter(pos -> !viewpoints.contains(pos) && PlayerRoutine.validLanding(mod, pos))
                        .filter(pos -> canopyViewpoint(p.position(), sight, pos))
                        .min(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(sight)));
                if (floor.isPresent()) {
                    viewpoints.add(floor.get());
                    observation = "已到树冠附近，换到侧面寻找可采树干";
                    return walk(mod, floor.get().above());
                }
            }
            return finish("到达树旁，仍未找到触手可及的可采原木；本次未确认采木");
        }
        if (!surveyOnly && gatherBlock && block != null && !p.getAbilities().instabuild) {
            for (var hit : hits) {
                var pos = hit.getBlockPos();
                if (mod.level().getBlockState(pos).is(block) && SurvivalAction.harvestable(mod, pos)
                        && p.getEyePosition().distanceTo(hit.getLocation()) <= p.blockInteractionRange()) {
                    harvest = SurvivalAction.harvest(pos, block);
                    return harvest;
                }
            }
        }
        boolean visible = hits.stream().anyMatch(hit -> matches(mod.level().getBlockState(hit.getBlockPos())));
        return finish(observation + (visible ? "；已到附近并重新目击目标" : "；已到最后目击位置，无法重新确认目标"));
    }

    @Override protected Task onTick(AltoClef mod) {
        var p = mod.getPlayer();
        if (start == null) {
            start = p.position();
            if (heading == null) heading = Vec3.directionFromRotation(0, p.getYRot());
        }
        if (p.hurtTime > 0 && p.getHealth() <= 8) return finish("受伤且生命偏低，结束当前目标先处理生存");
        if (harvest != null) return harvest.done ? finish(observation + "；" + harvest.result) : harvest;
        double range = SceneObservation.range(Minecraft.getInstance());
        if (range < 1) return finish("当前可见范围不足");
        if (leg != null) {
            if (subject != null && p.tickCount >= nextTrack) {
                nextTrack = p.tickCount + 10;
                if (seen(mod, subject, range)) {
                    sight = subject.position();
                    if (subject.distanceToSqr(p) <= 16) return finish(observation + "；已在近处重新目击同一实体");
                    if (subject.blockPosition().distSqr(destination) > 9) return walk(mod, subject.blockPosition());
                }
            }
            double distance = p.position().multiply(1,0,1).distanceTo(Vec3.atCenterOf(destination).multiply(1,0,1));
            if (distance > 2 && !leg.isFinished(mod)) {
                if (distance < best - .75) { best = distance; progressTick = p.tickCount; }
                if (p.tickCount - progressTick > 180) return finish(observation + "；寻路持续无进展，结束此次目标");
                return leg;
            }
            leg = null;
            if (hasSight) return atSighting(mod);
            if (legs >= 6 || start.distanceTo(p.position()) >= 48) {
                explored = exploring() && start.subtract(p.position()).horizontalDistance() >= 8;
                return finish(explored ? "已完成同方向的本段探索" : "本段已结束，未确认目标或有效探索距离");
            }
            cursor = 0;
            sight = null;
            observedTrunk = null;
            if (key.equals("explore") && SurvivalAction.needsStarterWood(p)
                    && SceneObservation.surroundings(mod).stream().map(BlockHitResult::getBlockPos)
                    .anyMatch(pos -> mod.level().getBlockState(pos).is(BlockTags.LOGS) && SurvivalAction.harvestable(mod, pos)))
                return finish("途中已目击可采原木，结束探索并重新选事");
            return null;
        }
        if (!survey(mod, range)) return null;
        BlockPos next;
        if (sight != null) {
            hasSight = true;
            next = observedTrunk != null ? observedTrunk : BlockPos.containing(sight);
            observation = "已目击，" + SceneObservation.bearing(sight.subtract(p.position())) + "约"
                    + Math.round(sight.distanceTo(p.position())) + "格；" + corridor(mod, sight);
            if (surveyOnly) return finish(observation);
            if (observedTrunk != null && p.getEyePosition().distanceTo(sight) <= p.blockInteractionRange())
                return atSighting(mod);
            if (subject != null && subject.distanceToSqr(p) <= 16) return finish(observation + "；已接近目标");
        } else {
            if (surveyOnly) return finish("当前站位的加载可见范围约 " + (int) range + " 格，采样中未目击目标");
            next = advance(mod);
            if (next == null) return finish("当前站位向目标方向没有可核验的落脚点");
            observation = (key.equals("explore") ? "沿原方向探索" : "当前加载可见范围内暂未目击，沿原方向换观察点")
                    + "；" + corridor(mod, Vec3.atCenterOf(next));
        }
        mod.log("定向观察：" + label() + "。" + observation);
        result = observation + "；尚未确认到达";
        return walk(mod, next);
    }

    private Task walk(AltoClef mod, BlockPos next) {
        destination = next;
        leg = new GetToXZTask(next.getX(), next.getZ());
        best = Double.POSITIVE_INFINITY;
        progressTick = mod.getPlayer().tickCount;
        legs++;
        return leg;
    }
}
