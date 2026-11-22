package adris.altoclef.util;

import adris.altoclef.AltoClef;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Task-local references to observed block positions. Client chunk indices supply candidates, never observations. */
final class VisibleInteractions {
    private record Anchor(BlockPos pos, Block block) {}
    record Seen(BlockHitResult hit, BlockState state, String kind, double distance, double angle) {}
    private final Map<String, Anchor> anchors = new LinkedHashMap<>();
    private Object level, owner;

    private void bind(AltoClef mod) {
        if (level != mod.level() || owner != mod.getPlayer()) {
            anchors.clear();
            level = mod.level();
            owner = mod.getPlayer();
        }
    }

    String remember(BlockPos pos, Block block) {
        Anchor anchor = new Anchor(pos.immutable(), block);
        for (var e : anchors.entrySet()) if (anchor.equals(e.getValue())) return e.getKey();
        if (anchors.size() >= 96) anchors.remove(anchors.keySet().iterator().next());
        String ref = "seen-" + UUID.randomUUID().toString().substring(0, 12);
        anchors.put(ref, anchor);
        return ref;
    }

    BlockHitResult reacquire(AltoClef mod, String ref) {
        bind(mod);
        Anchor anchor = anchors.get(ref);
        if (anchor == null) throw new IllegalArgumentException("本任务没有该目击引用，请用 look 观察");
        // A previously seen position may be behind us now. Turning back is allowed, clicking through a wall is not.
        var hit = SceneObservation.surfaceHit(mod, anchor.pos, false);
        if (hit == null || !mod.level().getBlockState(anchor.pos).is(anchor.block))
            throw new IllegalArgumentException("上次目击点 " + anchor.pos.toShortString() + " 当前无法确认，请按已知位置靠近或重新观察");
        return hit;
    }

    static List<Seen> visible(AltoClef mod, String keyword) {
        return visible(mod, keyword, null);
    }

    static List<Seen> visible(AltoClef mod, String keyword, List<BlockHitResult> surroundings) {
        var mc = Minecraft.getInstance();
        var player = mod.getPlayer();
        if (player == null || mod.level() == null || mc.getCameraEntity() != player || player.isSpectator())
            return List.of();
        Vec3 eye = player.getEyePosition(), forward = player.getViewVector(1);
        double limit = Math.min(SceneObservation.NEAR, SceneObservation.range(mc));
        var right = Vec3.directionFromRotation(0, player.getYRot() + 90);
        String term = Objects.toString(keyword, "").strip().toLowerCase(Locale.ROOT);
        double v = SceneObservation.verticalTangent(mc), h = v * SceneObservation.aspect(mc);
        var seen = new LinkedHashMap<BlockPos, Seen>();
        var candidates = new ArrayList<BlockEntity>();
        int minX = (int)Math.floor((eye.x - limit - 1) / 16), maxX = (int)Math.floor((eye.x + limit + 1) / 16);
        int minZ = (int)Math.floor((eye.z - limit - 1) / 16), maxZ = (int)Math.floor((eye.z + limit + 1) / 16);
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            var chunk = mod.level().getChunkSource().getChunkNow(x, z);
            if (chunk == null) continue;
            for (var entity : chunk.getBlockEntities().values()) {
                Vec3 delta = Vec3.atCenterOf(entity.getBlockPos()).subtract(eye);
                if (delta.length() <= limit + .87 && (surroundings != null || mayProject(delta, right, forward, h, v))) candidates.add(entity);
            }
        }
        candidates.sort(Comparator.comparingInt((BlockEntity e) ->
                matches(e.getBlockState(), kind(e.getBlockState(), e), term) ? 0 : 1)
                .thenComparingDouble(e -> eye.distanceToSqr(Vec3.atCenterOf(e.getBlockPos()))));
        var neighbours = new ArrayList<BlockPos>();
        for (var entity : candidates.stream().limit(64).toList()) {
            var hit = SceneObservation.surfaceHit(mod, entity.getBlockPos(), surroundings == null);
            if (hit != null) {
                if (neighbours.size() < 8) neighbours.add(hit.getBlockPos());
                add(mod, seen, hit, eye, forward, right, term);
            }
        }
        // Blocks such as crafting tables have no block entity. Sample the actual screen and neighbours of visible furniture.
        for (var pos : neighbours) for (var face : Direction.Plane.HORIZONTAL) {
            var p = pos.relative(face);
            if (seen.containsKey(p) || !mod.level().hasChunkAt(p)) continue;
            var state = mod.level().getBlockState(p);
            if (state.isAir()) continue;
            var hit = SceneObservation.surfaceHit(mod, p, surroundings == null);
            if (hit != null) add(mod, seen, hit, eye, forward, right, term);
        }
        if (surroundings != null) {
            for (var hit : surroundings) add(mod, seen, hit, eye, forward, right, term);
        } else for (int y = -2; y <= 2; y++) for (int x = -3; x <= 3; x++) {
            var dir = SceneObservation.viewRay(forward, player.getYRot(), x * h * .30, y * v * .43);
            var hit = SceneObservation.cast(mod, dir, limit);
            if (hit.getType() == HitResult.Type.BLOCK && SceneObservation.lit(mod, hit.getBlockPos().relative(hit.getDirection())))
                add(mod, seen, hit, eye, forward, right, term);
        }
        return seen.values().stream().sorted(Comparator.comparingInt((Seen s) -> s.kind.equals("方块实体") ? 1 : 0)
                .thenComparingDouble(Seen::distance)).limit(12).sorted(Comparator.comparingDouble(Seen::angle)).toList();
    }

    String observe(AltoClef mod, String keyword) {
        bind(mod);
        var player = mod.getPlayer();
        if (player == null || mod.level() == null || Minecraft.getInstance().getCameraEntity() != player || player.isSpectator())
            return "当前无法从玩家视角观察交互方块。";
        var selected = visible(mod, keyword);
        var out = new StringBuilder("当前视野内可见交互候选");
        if (selected.isEmpty()) return out.append("：未辨认到匹配目标。").toString();
        out.append("（从画面左到右，最多12个）");
        for (var s : selected) {
            var pos = s.hit.getBlockPos();
            out.append("\n").append(remember(pos, s.state.getBlock())).append(" ").append(s.kind).append(" ")
                    .append(BuiltInRegistries.BLOCK.getKey(s.state.getBlock())).append(" @ ").append(pos.toShortString())
                    .append(" ").append(side(s.angle)).append(" ").append(Math.round(s.distance * 10) / 10.0).append("格 ")
                    .append(s.distance <= player.blockInteractionRange() ? "可触及" : "需靠近");
        }
        return out.toString();
    }

    static String kind(BlockState state, BlockEntity entity) {
        Block b = state.getBlock();
        if (b instanceof AbstractChestBlock<?>) return "箱子/容器";
        if (b instanceof ShulkerBoxBlock || b instanceof BarrelBlock || entity instanceof Container) return "容器";
        if (b instanceof BedBlock) return "床";
        if (b instanceof CraftingTableBlock) return "工作台";
        if (b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock) return "门";
        if (b instanceof LeverBlock || state.is(BlockTags.BUTTONS)) return "开关";
        if (entity instanceof MenuProvider) return "菜单";
        return entity != null ? "方块实体" : "";
    }

    static boolean matches(BlockState state, String kind, String term) {
        return term.isEmpty() || kind.contains(term) || BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().contains(term)
                || state.getBlock().getName().getString().toLowerCase(Locale.ROOT).contains(term);
    }

    // Only reject an indexed block if its bounding sphere lies wholly outside a frustum plane.
    static boolean mayProject(Vec3 delta, Vec3 right, Vec3 forward, double horizontal, double vertical) {
        double depth = delta.dot(forward), r = .87;
        return depth >= -r && Math.abs(delta.dot(right)) <= depth * horizontal + r * Math.hypot(1, horizontal)
                && Math.abs(delta.dot(right.cross(forward))) <= depth * vertical + r * Math.hypot(1, vertical);
    }

    static double angle(Vec3 delta, Vec3 right, Vec3 forward) {
        return Math.toDegrees(Math.atan2(delta.dot(right), delta.dot(forward)));
    }
    static String side(double angle) {
        return angle < -3 ? "左侧" : angle > 3 ? "右侧" : "正前";
    }

    private static void add(AltoClef mod, Map<BlockPos, Seen> seen, BlockHitResult hit, Vec3 eye, Vec3 forward, Vec3 right, String term) {
        BlockPos pos = hit.getBlockPos();
        if (seen.containsKey(pos)) return;
        var state = mod.level().getBlockState(pos);
        String kind = kind(state, mod.level().getBlockEntity(pos));
        if (kind.isEmpty() && state.getMenuProvider(mod.level(), pos) != null) kind = "工作界面";
        if (kind.isEmpty() || !matches(state, kind, term)) return;
        seen.put(pos, new Seen(hit, state, kind, eye.distanceTo(hit.getLocation()), angle(Vec3.atCenterOf(pos).subtract(eye), right, forward)));
    }
}
