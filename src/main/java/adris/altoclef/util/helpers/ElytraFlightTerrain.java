package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Loaded-first, frame-bounded flight sweeps and incremental dry landing-site selection. */
public final class ElytraFlightTerrain implements ElytraRoutePlanner.Terrain {
    private static final int MAX_BLOCK_READS = 4096, MAX_TRACE_CALLS = 500;
    private static final int LANDING_BLOCK_READS = 64;
    private static final long LANDING_NANOS = 750_000;
    public enum LandingSearchState { WAITING_FOR_CHUNKS, SEARCHING, EXHAUSTED, FOUND }
    private record Sweep(Vec3 from, Vec3 to, boolean landing) { }
    private final AltoClef mod;
    private final Map<Long, BlockState> blocks = new HashMap<>();
    private final Map<Long, Boolean> chunks = new HashMap<>();
    private final Map<Long, VoxelShape> shapes = new HashMap<>();
    private final Map<Sweep, ElytraRoutePlanner.Trace> traces = new HashMap<>();
    private Object world, player;
    private int frame = Integer.MIN_VALUE, reads, traceCalls;
    private long frameStarted, deadline;
    private final LandingSearch landingSearch = new LandingSearch();
    private boolean checkingLanding, landingUnknown, landingChunksUnknown;
    private long landingDeadline;
    private int landingReadLimit;

    public ElytraFlightTerrain(AltoClef mod) { this.mod = mod; }

    @Override public void beginFrame(int tick, long maxNanos) {
        if (world != mod.level() || player != mod.getPlayer()) {
            world = mod.level(); player = mod.getPlayer();
            landingSearch.reset();
            frame = Integer.MIN_VALUE;
        }
        if (frame == tick) {
            deadline = Math.max(deadline, frameStarted + Math.max(1, maxNanos));
            return;
        }
        frame = tick; reads = traceCalls = 0;
        blocks.clear(); chunks.clear(); shapes.clear(); traces.clear();
        frameStarted = System.nanoTime();
        deadline = frameStarted + Math.max(1, maxNanos);
    }

    public int worldReads() { return reads; }
    public int traceCalls() { return traceCalls; }

    @Override public ElytraRoutePlanner.Trace trace(Vec3 from, Vec3 to, boolean landingContact) {
        if (!finite(from) || !finite(to) || mod.getPlayer() == null || mod.level() == null)
            return ElytraRoutePlanner.Trace.UNKNOWN;
        ensureFrame();
        Sweep key = new Sweep(from, to, landingContact);
        var old = traces.get(key);
        if (old != null) return old;
        if (++traceCalls > MAX_TRACE_CALLS || exhausted()) return ElytraRoutePlanner.Trace.UNKNOWN;
        Vec3 delta = to.subtract(from);
        if (delta.lengthSqr() > 36) return ElytraRoutePlanner.Trace.UNKNOWN;
        AABB body = glideBox(from);
        AABB sweep = body.expandTowards(delta);
        if (!loaded(sweep) || !mod.level().getWorldBorder().isWithinBounds(sweep))
            return ElytraRoutePlanner.Trace.UNKNOWN;
        List<VoxelShape> obstacles = new ArrayList<>();
        for (BlockPos position : BlockPos.betweenClosed(BlockPos.containing(sweep.minX - 1, sweep.minY - 1, sweep.minZ - 1),
                BlockPos.containing(sweep.maxX + 1, sweep.maxY + 1, sweep.maxZ + 1))) {
            if (position.getY() > mod.level().getMaxY()) continue;
            BlockState state = block(position);
            if (state == null) return ElytraRoutePlanner.Trace.UNKNOWN;
            AABB blockBox = new AABB(position);
            if (sweep.intersects(blockBox) && hazardous(state)) return cache(key, ElytraRoutePlanner.Trace.BLOCKED);
            VoxelShape shape = shape(position, state);
            if (!shape.isEmpty()) obstacles.add(shape.move(position));
        }
        if (exhausted()) return ElytraRoutePlanner.Trace.UNKNOWN;
        obstacles.addAll(mod.level().getEntityCollisions(mod.getPlayer(), sweep));
        Vec3 resolved = Vec3.ZERO;
        for (Direction.Axis axis : Direction.axisStepOrder(delta)) {
            double movement = delta.get(axis);
            if (movement == 0) continue;
            double clipped = Shapes.collide(axis, body, obstacles, movement);
            Vec3 component = Vec3.ZERO.with(axis, clipped);
            body = body.move(component);
            resolved = resolved.add(component);
        }
        if (resolved.distanceToSqr(delta) < 1e-10) return cache(key, ElytraRoutePlanner.Trace.CLEAR);
        if (landingContact && delta.y <= 0 && delta.y >= -.45 && delta.horizontalDistance() <= .65
                && Math.abs(resolved.x - delta.x) < 1e-6 && Math.abs(resolved.z - delta.z) < 1e-6
                && resolved.y >= delta.y && resolved.y <= 0) {
            Vec3 contact = from.add(resolved);
            if (landingSupport(contact)) return cache(key, ElytraRoutePlanner.Trace.CLEAR);
        }
        return cache(key, ElytraRoutePlanner.Trace.BLOCKED);
    }

    /** Includes standing headroom; a glide-sized box alone cannot authorize takeoff. */
    public boolean standingClear(Vec3 feet) {
        if (!finite(feet) || mod.getPlayer() == null || mod.level() == null) return false;
        ensureFrame();
        AABB body = mod.getPlayer().getDimensions(Pose.STANDING).makeBoundingBox(feet).inflate(.06, 0, .06);
        return clearBody(body);
    }

    public boolean clearTakeoff(Vec3 position, Vec3 direction) {
        if (!finite(position) || !finite(direction) || mod.getPlayer() == null || mod.level() == null) return false;
        ensureFrame();
        Vec3 horizontal = direction.multiply(1, 0, 1);
        if (horizontal.lengthSqr() < 1e-7 || !standingClear(position)) return false;
        horizontal = horizontal.normalize();
        Vec3 previous = position;
        for (int index = 1; index <= 12; index++) {
            Vec3 next = position.add(horizontal.scale(index * 8.0 / 12)).add(0, index, 0);
            AABB body = mod.getPlayer().getDimensions(Pose.STANDING).makeBoundingBox(previous)
                    .expandTowards(next.subtract(previous)).inflate(.06, 0, .06);
            if (!clearBody(body)) return false;
            previous = next;
        }
        return true;
    }

    public LandingSearchState landingSearchState() { return landingSearch.state; }

    /** Two candidates per tick; unknown terrain never counts as a rejected landing site. */
    public Optional<Vec3> findLanding(Vec3 requested, int tick) {
        return findLanding(requested, tick, 18);
    }

    /** Expand around the same destination only after a complete loaded search has failed. */
    public Optional<Vec3> findLanding(Vec3 requested, int tick, int radius) {
        beginFrame(tick, 2_000_000);
        if (!finite(requested) || mod.level() == null || mod.getPlayer() == null) return Optional.empty();
        checkingLanding = true;
        landingDeadline = Math.min(deadline, System.nanoTime() + LANDING_NANOS);
        landingReadLimit = Math.min(MAX_BLOCK_READS, reads + LANDING_BLOCK_READS);
        try {
            return landingSearch.update(requested, tick, radius, this::probeLanding,
                    this::validateLanding, this::exhausted);
        } finally { checkingLanding = false; }
    }

    private LandingProbeResult probeLanding(Vec3 requested, int[] offset) {
        landingUnknown = landingChunksUnknown = false;
        int x = Mth.floor(requested.x) + offset[0], z = Mth.floor(requested.z) + offset[1];
        if (!loadedColumn(x, z)) return new LandingProbeResult(landingValidation(false), null);
        int top = mod.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        for (int drop = 0; drop < 3; drop++) {
            if (exhausted()) return new LandingProbeResult(landingValidation(false), null);
            BlockPos floor = new BlockPos(x, top - 1 - drop, z);
            BlockState state = block(floor);
            if (state == null || hazardous(state)) return new LandingProbeResult(landingValidation(false), null);
            VoxelShape shape = shape(floor, state);
            if (shape.isEmpty()) continue;
            Vec3 point = new Vec3(x + .5, floor.getY() + shape.max(Direction.Axis.Y), z + .5);
            // Preserve unknown flags from the height/floor reads when checking full support.
            boolean supported = landingSupport(point);
            return new LandingProbeResult(landingValidation(supported), point);
        }
        return new LandingProbeResult(LandingValidation.INVALID, null);
    }

    private LandingValidation validateLanding(Vec3 point) {
        landingUnknown = landingChunksUnknown = false;
        return landingValidation(landingSupport(point));
    }

    private LandingValidation landingValidation(boolean supported) {
        if (exhausted()) landingUnknown = true;
        if (landingUnknown) return landingChunksUnknown ? LandingValidation.UNKNOWN_CHUNKS : LandingValidation.UNKNOWN_BUDGET;
        return supported ? LandingValidation.VALID : LandingValidation.INVALID;
    }

    private boolean landingSupport(Vec3 feet) {
        if (!finite(feet) || !standingClear(feet)) return false;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            double x = feet.x + dx, z = feet.z + dz;
            BlockPos at = BlockPos.containing(x, feet.y - .01, z);
            BlockState state = block(at);
            if (state == null || hazardous(state) || state.is(BlockTags.LEAVES)) return false;
            VoxelShape floor = shape(at, state);
            if (floor.isEmpty() || Math.abs(at.getY() + floor.max(Direction.Axis.Y) - feet.y) > .03) return false;
            double localX = x - at.getX(), localZ = z - at.getZ();
            boolean supported = false;
            for (AABB box : floor.toAabbs()) if (localX > box.minX && localX < box.maxX
                    && localZ > box.minZ && localZ < box.maxZ && Math.abs(at.getY() + box.maxY - feet.y) <= .03) {
                supported = true; break;
            }
            if (!supported) return false;
            if (!clearBody(mod.getPlayer().getDimensions(Pose.STANDING).makeBoundingBox(new Vec3(x, feet.y, z)))) return false;
        }
        return true;
    }

    private boolean clearBody(AABB body) {
        if (!loaded(body) || !mod.level().getWorldBorder().isWithinBounds(body)) return false;
        for (BlockPos at : BlockPos.betweenClosed(BlockPos.containing(body.minX, body.minY, body.minZ),
                BlockPos.containing(Math.nextDown(body.maxX), Math.nextDown(body.maxY), Math.nextDown(body.maxZ)))) {
            if (at.getY() > mod.level().getMaxY()) continue;
            BlockState state = block(at);
            if (state == null || hazardous(state)) return false;
            for (AABB obstacle : shape(at, state).toAabbs()) if (body.intersects(obstacle.move(at))) return false;
        }
        return !exhausted() && mod.level().getEntityCollisions(mod.getPlayer(), body).isEmpty();
    }

    private AABB glideBox(Vec3 at) {
        AABB nativeBox = mod.getPlayer().getDimensions(Pose.FALL_FLYING).makeBoundingBox(at);
        return new AABB(nativeBox.minX - .1, nativeBox.minY, nativeBox.minZ - .1,
                nativeBox.maxX + .1, nativeBox.maxY + .1, nativeBox.maxZ + .1);
    }
    private ElytraRoutePlanner.Trace cache(Sweep key, ElytraRoutePlanner.Trace value) { traces.put(key, value); return value; }
    private boolean loaded(AABB area) {
        if (area.minY < mod.level().getMinY() || !Double.isFinite(area.maxY)) return false;
        for (int x = Mth.floor(area.minX) >> 4; x <= Mth.floor(area.maxX) >> 4; x++)
            for (int z = Mth.floor(area.minZ) >> 4; z <= Mth.floor(area.maxZ) >> 4; z++)
                if (!loadedColumn(x << 4, z << 4)) return false;
        return true;
    }
    private boolean loadedColumn(int x, int z) {
        if (exhausted()) return false;
        long key = ((long)(x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL);
        boolean loaded = chunks.computeIfAbsent(key, ignored -> mod.level().hasChunkAt(new BlockPos(x, 0, z)));
        if (!loaded && checkingLanding) { landingUnknown = true; landingChunksUnknown = true; }
        return loaded;
    }
    private BlockState block(BlockPos at) {
        if (at.getY() > mod.level().getMaxY()) return Blocks.AIR.defaultBlockState();
        long key = at.asLong();
        BlockState cached = blocks.get(key);
        if (cached != null) return cached;
        if (at.getY() < mod.level().getMinY() || exhausted() || !loadedColumn(at.getX(), at.getZ())) return null;
        reads++;
        BlockState state = mod.level().getBlockState(at);
        blocks.put(key, state); return state;
    }
    private VoxelShape shape(BlockPos at, BlockState state) {
        return shapes.computeIfAbsent(at.asLong(), ignored -> state.getCollisionShape(mod.level(), at));
    }
    private boolean exhausted() {
        long now = System.nanoTime();
        boolean exhausted = reads >= MAX_BLOCK_READS || now >= deadline
                || checkingLanding && (reads >= landingReadLimit || now >= landingDeadline);
        if (exhausted && checkingLanding) landingUnknown = true;
        return exhausted;
    }
    private void ensureFrame() {
        if (mod.getPlayer() != null) beginFrame(mod.getPlayer().tickCount, 2_000_000);
    }
    private static boolean hazardous(BlockState state) {
        return !state.getFluidState().isEmpty() || state.is(BlockTags.FIRE) || state.is(BlockTags.CLIMBABLE)
                || state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.WITHER_ROSE)
                || state.is(Blocks.COBWEB) || state.is(Blocks.POWDER_SNOW);
    }
    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
    private static int[][] landingOffsets(int maximumRadius) {
        List<int[]> result = new ArrayList<>(); result.add(new int[]{0, 0});
        for (int radius = 3; radius <= maximumRadius; radius += 3) for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4;
            result.add(new int[]{(int)Math.round(Math.cos(angle) * radius), (int)Math.round(Math.sin(angle) * radius)});
        }
        return result.toArray(int[][]::new);
    }

    enum LandingValidation { VALID, INVALID, UNKNOWN_CHUNKS, UNKNOWN_BUDGET }
    record LandingProbeResult(LandingValidation validation, Vec3 point) { }
    @FunctionalInterface interface LandingProbe { LandingProbeResult probe(Vec3 target, int[] offset); }

    /** Production search memory, replayable without fabricating a Minecraft world. */
    static final class LandingSearch {
        private Vec3 target, cached;
        private int radius, cursor, cachedIndex, rejectedCount, checked = Integer.MIN_VALUE / 2, exhaustedAt;
        private int[][] offsets = new int[0][];
        private boolean[] rejected = new boolean[0];
        private LandingSearchState state = LandingSearchState.SEARCHING;

        Optional<Vec3> update(Vec3 requested, int tick, int requestedRadius, LandingProbe probe,
                              Function<Vec3, LandingValidation> recheck, BooleanSupplier budgetSpent) {
            int boundedRadius = Mth.clamp(requestedRadius, 3, 96);
            if (target == null || target.subtract(requested).horizontalDistanceSqr() > 16 || radius != boundedRadius) {
                target = requested; radius = boundedRadius; offsets = landingOffsets(radius);
                cached = null; cursor = rejectedCount = 0; rejected = new boolean[offsets.length];
                checked = Integer.MIN_VALUE / 2; state = LandingSearchState.SEARCHING;
            }
            if (cached != null) {
                if (tick - checked < 10) { state = LandingSearchState.FOUND; return Optional.of(cached); }
                LandingValidation validation = recheck.apply(cached);
                if (validation == LandingValidation.VALID) {
                    checked = tick; state = LandingSearchState.FOUND; return Optional.of(cached);
                }
                if (unknown(validation)) {
                    state = unknownState(validation);
                    return Optional.of(cached);
                }
                cached = null;
                reject(cachedIndex);
                if (rejectedCount >= offsets.length) exhaustedAt = tick;
            }
            if (rejectedCount >= offsets.length) {
                state = LandingSearchState.EXHAUSTED;
                if (tick - exhaustedAt < 40) return Optional.empty();
                cursor = rejectedCount = 0; rejected = new boolean[offsets.length];
            }
            state = LandingSearchState.SEARCHING;
            for (int candidate = 0; candidate < 2 && rejectedCount < offsets.length; candidate++) {
                if (budgetSpent.getAsBoolean()) return Optional.empty();
                while (rejected[cursor]) cursor = (cursor + 1) % offsets.length;
                LandingProbeResult result = probe.probe(target, offsets[cursor]);
                if (result.validation() == LandingValidation.UNKNOWN_BUDGET) return Optional.empty();
                if (result.validation() == LandingValidation.UNKNOWN_CHUNKS) {
                    state = LandingSearchState.WAITING_FOR_CHUNKS;
                    // Keep it unresolved, while allowing an adjacent loaded column to be tested.
                    cursor = (cursor + 1) % offsets.length;
                    continue;
                }
                if (result.validation() == LandingValidation.VALID && finite(result.point())) {
                    cached = result.point(); cachedIndex = cursor; checked = tick;
                    state = LandingSearchState.FOUND; return Optional.of(cached);
                }
                reject(cursor);
                cursor = (cursor + 1) % offsets.length;
            }
            if (rejectedCount >= offsets.length) { state = LandingSearchState.EXHAUSTED; exhaustedAt = tick; }
            return Optional.empty();
        }

        private void reject(int index) {
            if (!rejected[index]) { rejected[index] = true; rejectedCount++; }
        }

        private static boolean unknown(LandingValidation validation) {
            return validation == LandingValidation.UNKNOWN_CHUNKS || validation == LandingValidation.UNKNOWN_BUDGET;
        }
        private static LandingSearchState unknownState(LandingValidation validation) {
            return validation == LandingValidation.UNKNOWN_CHUNKS ? LandingSearchState.WAITING_FOR_CHUNKS : LandingSearchState.SEARCHING;
        }
        void reset() {
            target = cached = null; cursor = radius = cachedIndex = rejectedCount = 0;
            offsets = new int[0][]; rejected = new boolean[0];
            checked = Integer.MIN_VALUE / 2; state = LandingSearchState.SEARCHING;
        }
    }
}
