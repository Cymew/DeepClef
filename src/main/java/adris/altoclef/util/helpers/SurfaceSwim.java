package adris.altoclef.util.helpers;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;

/** Vertical inputs for a verified open surface segment. Horizontal steering stays with the route owner. */
final class SurfaceSwim {
    enum Phase {
        DIP("起游：下沉至眼部入水"), START("起游：水下前进并疾跑"),
        CRUISE("水线疾游"), FLOAT("水面浮行");

        final String label;
        Phase(String label) { this.label = label; }
    }

    record Controls(Phase phase, boolean jump, boolean sneak, boolean sprint, float pitch) {}
    private int attemptTicks;
    private int retryAt;

    Controls forPlayer(LocalPlayer player, SwimTerrain terrain,
                       Vec3 surfacePoint, int tick) {
        var world = player.level();
        var pos = player.position();
        BlockPos cell = BlockPos.containing(surfacePoint);
        double line = cell.getY() + world.getFluidState(cell).getHeightForCamera(world,cell);
        boolean eyesWet = player.isEyeInFluid(FluidTags.WATER);
        int reserve = player.isSwimming() ? 90 : Math.min(180,player.getMaxAirSupply()/2);
        boolean canSprint = (player.getFoodData().hasEnoughFood() || player.getAbilities().mayfly)
                && !player.isMobilityRestricted() && !player.isUsingItem() && (SwimNavigation.protectedAir(player) || player.getAirSupply()>reserve);
        double dipY = line - player.getEyeHeight() - .10;
        Vec3 dip = new Vec3(pos.x, Math.min(pos.y,dipY), pos.z);
        boolean room = canSprint && !eyesWet && !player.isSwimming()
                && terrain.water(BlockPos.containing(dip)) && terrain.swept(pos,dip);
        return update(tick, canSprint, room, eyesWet, player.isSwimming(),
                player.getEyeY()-line, player.getDeltaMovement().y);
    }

    Controls update(int tick, boolean canSprint, boolean roomToDip, boolean eyesWet, boolean swimming,
                    double eyeAboveWater, double verticalSpeed) {
        if (swimming && canSprint) {
            attemptTicks = 0;
            retryAt = 0;
            // Leave a breathing margin while keeping the swimming body's feet immersed.
            // Anticipate inertia before releasing jump, especially just after the pose changes.
            double error = .075 - eyeAboveWater - 3.5 * verticalSpeed;
            float pitch = (float)Math.clamp(-42 * error, -24, 24);
            return new Controls(Phase.CRUISE, error > .035, false, true, pitch);
        }
        if (canSprint && (roomToDip || eyesWet) && tick >= retryAt) {
            if (++attemptTicks <= 32) {
                return eyesWet ? new Controls(Phase.START, false, false, true, 0)
                        : new Controls(Phase.DIP, false, true, false, 12);
            }
            retryAt = tick + 40;
        }
        attemptTicks = 0;
        return new Controls(Phase.FLOAT, eyesWet || eyeAboveWater < .02, false, false, 0);
    }
}
