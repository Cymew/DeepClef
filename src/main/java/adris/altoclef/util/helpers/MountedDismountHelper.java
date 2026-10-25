package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/** Predicts the vanilla first dismount choice without changing the rider's pose or position. */
public final class MountedDismountHelper {
    private record Candidate(Vec3 position, Pose pose) { }

    private MountedDismountHelper() { }

    public static Optional<Vec3> safeLocation(AltoClef mod, AbstractHorse horse) {
        if (mod == null || mod.getPlayer() == null || mod.level() == null || horse == null
                || !horse.isAlive() || horse.isRemoved() || horse.level() != mod.level()
                || horse.getControllingPassenger() != mod.getPlayer()) return Optional.empty();
        Candidate candidate = nativeCandidate(mod, horse);
        if (candidate == null) return Optional.empty();
        Vec3 location = candidate.position();
        if (!Double.isFinite(location.x) || !Double.isFinite(location.y) || !Double.isFinite(location.z)) {
            return Optional.empty();
        }
        var player = mod.getPlayer();
        AABB box = player.getLocalBoundsForPose(candidate.pose()).move(location);
        AABB checked = box.inflate(0, 0.05, 0);
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(checked.minX, checked.minY, checked.minZ),
                BlockPos.containing(checked.maxX, checked.maxY, checked.maxZ))) {
            if (mod.level().isOutsideBuildHeight(pos) || !mod.level().hasChunkAt(pos)) return Optional.empty();
            var state = mod.level().getBlockState(pos);
            if (!state.getFluidState().isEmpty() || MovementHelper.avoidWalkingInto(state)) return Optional.empty();
        }
        if (!DismountHelper.canDismountTo(mod.level(), player, box)) return Optional.empty();
        // This also checks the vanilla horse.position() fallback, which has not
        // gone through the ordinary side candidates' floor validation.
        AABB feet = new AABB(box.minX, box.minY - 0.05, box.minZ, box.maxX, box.minY, box.maxZ);
        for (var shape : mod.level().getBlockCollisions(player, feet)) {
            if (!shape.isEmpty()) return Optional.of(location);
        }
        return Optional.empty();
    }

    private static Candidate nativeCandidate(AltoClef mod, AbstractHorse horse) {
        var player = mod.getPlayer();
        float firstYaw = horse.getYRot() + (player.getMainArm() == HumanoidArm.RIGHT ? 90.0f : -90.0f);
        float secondYaw = horse.getYRot() + (player.getMainArm() == HumanoidArm.LEFT ? 90.0f : -90.0f);
        for (float yaw : new float[]{firstYaw, secondYaw}) {
            Vec3 offset = escapeVector(horse.getBbWidth(), player.getBbWidth(), yaw);
            double x = horse.getX() + offset.x;
            double z = horse.getZ() + offset.z;
            double limit = horse.getBoundingBox().maxY + 0.75;
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            poses: for (Pose pose : player.getDismountPoses()) {
                cursor.set(x, horse.getBoundingBox().minY, z);
                do {
                    // An unloaded floor cannot faithfully predict the server's
                    // earlier preferred candidate; do not silently try another.
                    if (mod.level().isOutsideBuildHeight(cursor) || !mod.level().hasChunkAt(cursor)) return null;
                    double floor = mod.level().getBlockFloorHeight(cursor);
                    if (cursor.getY() + floor > limit) continue poses;
                    if (DismountHelper.isBlockFloorValid(floor)) {
                        Vec3 position = new Vec3(x, cursor.getY() + floor, z);
                        if (DismountHelper.canDismountTo(mod.level(), player,
                                player.getLocalBoundsForPose(pose).move(position))) {
                            // Only after preserving this exact first native
                            // choice may safeLocation decide whether to request it.
                            return new Candidate(position, pose);
                        }
                    }
                    cursor.move(Direction.UP);
                } while (cursor.getY() < limit);
            }
        }
        return new Candidate(horse.position(), player.getPose());
    }

    /** Entity.getCollisionHorizontalEscapeVector is protected; use its exact arithmetic. */
    private static Vec3 escapeVector(double mountWidth, double riderWidth, float yaw) {
        double distance = (mountWidth + riderWidth + (double) 1.0e-5f) / 2.0;
        float x = -Mth.sin(yaw * ((float) Math.PI / 180));
        float z = Mth.cos(yaw * ((float) Math.PI / 180));
        float scale = Math.max(Math.abs(x), Math.abs(z));
        return new Vec3((double) x * distance / scale, 0, (double) z * distance / scale);
    }
}
