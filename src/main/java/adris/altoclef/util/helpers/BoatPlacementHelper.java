package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Optional;

/** Loaded-water placement checks; never creates or changes an entity. */
public final class BoatPlacementHelper {
    // All 26.3 ordinary boats, including the raft; intentionally excludes chest boats.
    public static Item[] ordinaryBoats() {
        return new Item[]{Items.OAK_BOAT, Items.SPRUCE_BOAT, Items.BIRCH_BOAT,
                Items.JUNGLE_BOAT, Items.ACACIA_BOAT, Items.DARK_OAK_BOAT,
                Items.MANGROVE_BOAT, Items.CHERRY_BOAT, Items.PALE_OAK_BOAT,
                Items.POPLAR_BOAT, Items.BAMBOO_RAFT};
    }

    public record Site(Vec3 aim, Vec3 placement) { }
    private record SearchPoint(double x, double z, double score) { }

    private static final double HALF_WIDTH = 1.375 / 2;
    private static final double HEIGHT = .5625;

    private BoatPlacementHelper() { }

    public static boolean isOrdinaryBoatItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        for (Item item : ordinaryBoats()) if (stack.is(item)) return true;
        return false;
    }

    public static boolean hasBoatInInventory(LocalPlayer player) {
        if (player == null) return false;
        for (int slot = 0; slot < 36; slot++) {
            if (isOrdinaryBoatItem(player.getInventory().getItem(slot))) return true;
        }
        return isOrdinaryBoatItem(player.getOffhandItem());
    }

    /** A bounded half-block search, nearest forward water first. Caller should throttle retries. */
    public static Optional<Site> findSite(AltoClef mod, Vec3 preferredPoint) {
        if (mod == null || mod.getPlayer() == null || mod.level() == null) return Optional.empty();
        LocalPlayer player = mod.getPlayer();
        if (player.isPassenger() || !player.isAlive()) return Optional.empty();
        Vec3 origin = player.position();
        Vec3 direction = preferredPoint == null ? player.getLookAngle() : preferredPoint.subtract(origin);
        direction = new Vec3(direction.x, 0, direction.z).normalize();
        var points = new ArrayList<SearchPoint>();
        // Retain integer centres for two-block canals, rather than searching only block centres.
        double centreX = Math.rint(origin.x * 2) / 2;
        double centreZ = Math.rint(origin.z * 2) / 2;
        double maxRange = Math.min(4.25, player.blockInteractionRange());
        for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) {
            double x = centreX + dx * .5, z = centreZ + dz * .5;
            double vx = x - origin.x, vz = z - origin.z;
            double distance = Math.hypot(vx, vz);
            if (distance < 1.25 || distance > maxRange) continue;
            double forward = (vx * direction.x + vz * direction.z) / distance;
            points.add(new SearchPoint(x, z, distance + (1 - forward) * 2));
        }
        points.sort(Comparator.comparingDouble(SearchPoint::score));
        int inspected = 0;
        for (SearchPoint point : points) {
            if (++inspected > 80) break;
            for (int dy = 1; dy >= -2; dy--) {
                BlockPos water = BlockPos.containing(point.x(), origin.y + dy, point.z());
                if (!loaded(mod.level(), water)) continue;
                var state = mod.level().getBlockState(water);
                var fluid = state.getFluidState();
                if (!ordinaryWater(state) || fluid.getHeight(mod.level(), water) < .75) continue;
                double top = water.getY() + fluid.getHeight(mod.level(), water);
                if (player.getEyePosition().y <= top + .02) continue;
                Vec3 aim = new Vec3(point.x(), top - .03, point.z());
                Optional<Site> result = traceSite(mod, aim);
                if (result.isPresent()) return result;
            }
        }
        return Optional.empty();
    }

    /** Recheck the exact native ray just before use: OUTLINE + ANY + blockInteractionRange. */
    public static Optional<Site> traceSite(AltoClef mod, Vec3 aim) {
        LocalPlayer player = mod.getPlayer();
        Vec3 from = player.getEyePosition();
        Vec3 direction = aim.subtract(from).normalize();
        if (direction.lengthSqr() < .5) return Optional.empty();
        Vec3 to = from.add(direction.scale(player.blockInteractionRange()));
        BlockHitResult hit = mod.level().clip(new ClipContext(from, to,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player));
        if (hit.getType() != HitResult.Type.BLOCK || hit.getDirection() != Direction.UP
                || !loaded(mod.level(), hit.getBlockPos())
                || !ordinaryWater(mod.level().getBlockState(hit.getBlockPos()))) return Optional.empty();
        Vec3 placement = hit.getLocation();
        if (!validPlacement(mod, placement)) return Optional.empty();
        // BoatItem only rejects pickable entities containing the eye, not every entity
        // crossed by the ray. Preserve that native condition without a fake entity.
        AABB pickBounds = player.getBoundingBox().expandTowards(direction.scale(5)).inflate(1);
        for (Entity entity : mod.level().getEntities(player, pickBounds, EntitySelector.CAN_BE_PICKED)) {
            if (entity.getBoundingBox().inflate(entity.getPickRadius()).contains(from)) return Optional.empty();
        }
        return Optional.of(new Site(aim, placement));
    }

    /** The actual view ray, used after LookHelper applies its native yaw/pitch. */
    public static Optional<Site> currentViewSite(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        return traceSite(mod, player.getEyePosition().add(player.getViewVector(1)));
    }

    private static boolean validPlacement(AltoClef mod, Vec3 at) {
        Level level = mod.level();
        LocalPlayer player = mod.getPlayer();
        AABB spawn = new AABB(at.x - HALF_WIDTH, at.y, at.z - HALF_WIDTH,
                at.x + HALF_WIDTH, at.y + HEIGHT, at.z + HALF_WIDTH);
        AABB floatHull = spawn.move(0, -HEIGHT * .65, 0);
        // Reserve standing rider height and both possible normal boat/raft offsets.
        AABB rider = new AABB(at.x - .75, at.y - .25, at.z - .75,
                at.x + .75, at.y + player.getDimensions(Pose.STANDING).height() + .65, at.z + .75);
        AABB checked = spawn.minmax(floatHull).minmax(rider).inflate(.01);
        if (!loaded(level, checked.inflate(1, 0, 1))) return false;
        if (!level.noCollision(player, spawn) || !level.noCollision(player, floatHull)
                || !level.noCollision(player, rider) || spawn.intersects(player.getBoundingBox())) return false;
        // A newly created boat collides with pushable mobs as well as solid
        // entities. Player-context noCollision alone omits those mobs.
        for (Entity entity : level.getEntities(player, checked, EntitySelector.NO_SPECTATORS)) {
            if (entity.isPushable() || entity.canBeCollidedWith(player)) return false;
        }
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(checked.minX, checked.minY - .4, checked.minZ),
                BlockPos.containing(checked.maxX, checked.maxY, checked.maxZ))) {
            var state = level.getBlockState(pos);
            var fluid = state.getFluidState();
            if ((!fluid.isEmpty() && !ordinaryWater(state)) || state.is(Blocks.MAGMA_BLOCK)
                    || MovementHelper.avoidWalkingInto(state) && !ordinaryWater(state)) return false;
        }
        // Check the whole footprint in both axes. A source/flowing water seam must
        // not become a hidden ledge, bubble column or shallow submerged obstacle.
        for (double x : new double[]{spawn.minX + .001, at.x, spawn.maxX - .001}) {
            for (double z : new double[]{spawn.minZ + .001, at.z, spawn.maxZ - .001}) {
                BlockPos water = BlockPos.containing(x, at.y - .05, z);
                var state = level.getBlockState(water);
                if (!ordinaryWater(state)) return false;
                double top = water.getY() + state.getFluidState().getHeight(level, water);
                if (Math.abs(top - at.y) > .125) return false;
            }
        }
        return true;
    }

    private static boolean ordinaryWater(net.minecraft.world.level.block.state.BlockState state) {
        return state.getFluidState().is(FluidTags.WATER) && state.getBlock() instanceof LiquidBlock;
    }

    private static boolean loaded(Level level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.hasChunkAt(pos);
    }

    private static boolean loaded(Level level, AABB box) {
        if (box.minY < level.getMinY() || box.maxY >= level.getMaxY()) return false;
        for (int x = Mth.floor(box.minX) >> 4; x <= Mth.floor(box.maxX) >> 4; x++) {
            for (int z = Mth.floor(box.minZ) >> 4; z <= Mth.floor(box.maxZ) >> 4; z++) {
                if (!level.hasChunkAt(new BlockPos(x << 4, Mth.floor(box.minY), z << 4))) return false;
            }
        }
        return true;
    }

    /** Match the server's new entity to the consumed ordinary boat item. */
    public static boolean matchesItem(AbstractBoat boat, Item item) {
        return boat != null && boat.getPickResult().is(item);
    }
}
