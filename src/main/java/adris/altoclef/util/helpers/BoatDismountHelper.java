package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Optional;

/** Predicts vanilla's first boat dismount choice without changing the passenger. */
public final class BoatDismountHelper {
    private record Candidate(Vec3 position, Pose pose) { }

    private BoatDismountHelper() { }

    public static Optional<Vec3> safeLocation(AltoClef mod, AbstractBoat boat) {
        if (mod == null || mod.getPlayer() == null || mod.level() == null || boat == null
                || !boat.isAlive() || boat.isRemoved() || boat.level() != mod.level()
                || mod.getPlayer().getVehicle() != boat) return Optional.empty();
        Candidate candidate = nativeCandidate(mod, boat);
        if (candidate == null) return Optional.empty();
        Vec3 location = candidate.position();
        if (!Double.isFinite(location.x) || !Double.isFinite(location.y) || !Double.isFinite(location.z)) {
            return Optional.empty();
        }
        var player = mod.getPlayer();
        AABB box = player.getLocalBoundsForPose(candidate.pose()).move(location);
        // Collision shapes can extend beyond their block (for example a fence).
        if (!loaded(mod, box.inflate(1, 0.05, 1))) return Optional.empty();
        AABB checked = new AABB(box.minX, box.minY - 0.30, box.minZ,
                box.maxX, box.maxY + 0.05, box.maxZ);
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(checked.minX, checked.minY, checked.minZ),
                BlockPos.containing(checked.maxX, checked.maxY, checked.maxZ))) {
            var state = mod.level().getBlockState(pos);
            var fluid = state.getFluidState();
            boolean ordinaryWater = fluid.is(FluidTags.WATER) && state.getBlock() instanceof LiquidBlock;
            if ((!fluid.isEmpty() && !ordinaryWater)
                    || (MovementHelper.avoidWalkingInto(state) && !ordinaryWater)
                    || state.is(Blocks.MAGMA_BLOCK)) return Optional.empty();
        }
        if (!DismountHelper.canDismountTo(mod.level(), player, box)) return Optional.empty();
        BlockPos eye = BlockPos.containing(location.x,
                location.y + player.getEyeHeight(candidate.pose()), location.z);
        if (!mod.level().getFluidState(eye).isEmpty()) return Optional.empty();

        // Native fallback is above the boat's centre, so a small gap to the
        // water surface is expected. Never accept an unsupported air fallback.
        AABB feet = new AABB(box.minX, box.minY - 0.05, box.minZ, box.maxX, box.minY, box.maxZ);
        for (var shape : mod.level().getBlockCollisions(player, feet)) {
            if (!shape.isEmpty()) return Optional.of(location);
        }
        return waterSupport(mod, box) ? Optional.of(location) : Optional.empty();
    }

    private static Candidate nativeCandidate(AltoClef mod, AbstractBoat boat) {
        var player = mod.getPlayer();
        // AbstractBoat deliberately uses rider yaw and width * sqrt(2), not
        // horse side offsets or the boat's facing direction.
        Vec3 offset = escapeVector(boat.getBbWidth() * Mth.SQRT_OF_TWO,
                player.getBbWidth(), player.getYRot());
        double x = boat.getX() + offset.x;
        double z = boat.getZ() + offset.z;
        BlockPos target = BlockPos.containing(x, boat.getBoundingBox().maxY, z);
        BlockPos below = target.below();
        if (!loaded(mod, target) || !loaded(mod, below) || !loaded(mod, below.below())) return null;
        if (!mod.level().isWaterAt(below)) {
            var targets = new ArrayList<Vec3>(2);
            double floor = mod.level().getBlockFloorHeight(target);
            if (DismountHelper.isBlockFloorValid(floor)) {
                targets.add(new Vec3(x, target.getY() + floor, z));
            }
            double lowerFloor = mod.level().getBlockFloorHeight(below);
            if (DismountHelper.isBlockFloorValid(lowerFloor)) {
                targets.add(new Vec3(x, below.getY() + lowerFloor, z));
            }
            for (Pose pose : player.getDismountPoses()) {
                for (Vec3 position : targets) {
                    AABB box = player.getLocalBoundsForPose(pose).move(position);
                    // Unknown earlier candidates cannot be skipped in favour
                    // of a different, apparently safe, native destination.
                    if (!loaded(mod, box.inflate(1, 0.05, 1))) return null;
                    if (DismountHelper.canDismountTo(mod.level(), player, box)) {
                        return new Candidate(position, pose);
                    }
                }
            }
        }
        return new Candidate(new Vec3(boat.getX(), boat.getBoundingBox().maxY, boat.getZ()), player.getPose());
    }

    private static boolean waterSupport(AltoClef mod, AABB box) {
        double inset = 0.001;
        for (double x : new double[]{box.minX + inset, (box.minX + box.maxX) / 2, box.maxX - inset}) {
            for (double z : new double[]{box.minZ + inset, (box.minZ + box.maxZ) / 2, box.maxZ - inset}) {
                BlockPos pos = BlockPos.containing(x, box.minY - 0.30, z);
                if (!loaded(mod, pos)) return false;
                var state = mod.level().getBlockState(pos);
                var fluid = state.getFluidState();
                if (!fluid.is(FluidTags.WATER) || !(state.getBlock() instanceof LiquidBlock)
                        || pos.getY() + fluid.getHeight(mod.level(), pos) < box.minY - 0.30) return false;
            }
        }
        return true;
    }

    private static boolean loaded(AltoClef mod, AABB box) {
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            if (!loaded(mod, pos)) return false;
        }
        return true;
    }

    private static boolean loaded(AltoClef mod, BlockPos pos) {
        return !mod.level().isOutsideBuildHeight(pos) && mod.level().hasChunkAt(pos);
    }

    /** Exact Entity.getCollisionHorizontalEscapeVector arithmetic (protected in vanilla). */
    private static Vec3 escapeVector(double boatWidth, double riderWidth, float yaw) {
        double distance = (boatWidth + riderWidth + (double) 1.0e-5f) / 2.0;
        float x = -Mth.sin(yaw * ((float) Math.PI / 180));
        float z = Mth.cos(yaw * ((float) Math.PI / 180));
        float scale = Math.max(Math.abs(x), Math.abs(z));
        return new Vec3((double) x * distance / scale, 0, (double) z * distance / scale);
    }
}
