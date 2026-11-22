package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.util.DeepSeekAgent;
import baritone.api.pathing.calc.IPath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.function.Predicate;

/** Supplies vertical controls only. The active Baritone path still owns steering and arrival. */
public final class NativeWaterTravel {
    static final double MIN_CROSSING = 12;
    private IPath path;
    private int lastWaterNode = -1;
    private int probeIndex = -1, probeTick;
    private SurfaceSwim stroke = new SurfaceSwim();

    public boolean apply(AltoClef mod, LocalPlayer player) {
        if (mod == null || mod.getPlayer() != player || !player.isInWater() || !player.isAlive()
                || player.isPassenger() || player.isSpectator() || player.getAbilities().flying || player.isFallFlying()
                || mod.getUserTaskChain() == null || mod.backgroundPaused() || DeepSeekAgent.isManuallyPaused()) {
            clear();
            return false;
        }
        var mc = Minecraft.getInstance();
        var keys = player.input.keyPresses;
        if (mc.isPaused() || mc.gui.screen() != null || mc.gui.overlay() != null || mc.getCameraEntity() != player
                || keys.shift() || mc.options.keyShift.isDown() || SwimNavigation.handlesOxygen(mod)) {
            clear();
            return false;
        }
        var chain = mod.getUserTaskChain();
        if (mod.allowsBackgroundMovement() && !DeepSeekAgent.isBusy() && (!chain.isActive() || chain.isRunningIdleTask())
                && !mod.getClientBaritone().getPathingBehavior().isPathing()) {
            clear();
            // Keep buoyancy throughout the model/idle gap, including the frame after ascent finishes.
            if (new SwimTerrain(mod).waterSurface(player.getBlockX(), player.getBlockZ(), player.getY()) == null) return false;
            player.input.keyPresses = new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), true, false, keys.sprint());
            return true;
        }
        var executor = mod.getClientBaritone().getPathingBehavior().getCurrent();
        if (!chain.isActive() || mod.getTaskRunner().getCurrentTaskChain() != chain || executor == null) {
            clear();
            return false;
        }
        if (!keys.forward()) return false;
        if (path != executor.getPath()) {
            clear();
            path = executor.getPath();
        }
        var points = path.positions();
        int i = executor.getPosition();
        var world = mod.level();
        if (i < 0 || i + 1 >= points.size() || points.get(i).distSqr(player.blockPosition()) > 9
                || !openWater(world, points.get(i)) || !openWater(world, points.get(i + 1))) {
            lastWaterNode = -1;
            return false;
        }
        if (i >= lastWaterNode && (i != probeIndex || player.tickCount - probeTick >= 10)) {
            probeIndex = i;
            probeTick = player.tickCount;
            lastWaterNode = crossingEnd(points, i, pos -> openWater(world, pos));
        }
        if (i >= lastWaterNode) return false;
        var terrain = new SwimTerrain(mod);
        var surface = terrain.waterSurface(player.getBlockX(), player.getBlockZ(), player.getY());
        if (surface == null || SwimNavigation.magmaBelow(player)) return false;
        var next = stroke.forPlayer(player, terrain, surface, player.tickCount);
        player.input.keyPresses = new Input(keys.forward(), keys.backward(), keys.left(), keys.right(),
                next.jump(), next.sneak(), next.sprint());
        player.setXRot(SwimRoutePlanner.turn(player.getXRot(), next.pitch(), 12));
        return true;
    }

    static int crossingEnd(List<? extends BlockPos> points, int start, Predicate<BlockPos> open) {
        double length = 0;
        int end = start;
        while (end + 1 < points.size() && end - start < 32) {
            BlockPos a = points.get(end), b = points.get(end + 1);
            int dx = b.getX()-a.getX(), dz = b.getZ()-a.getZ();
            if (Math.abs(dx) > 1 || Math.abs(dz) > 1 || dx == 0 && dz == 0
                    || Math.abs(a.getY()-b.getY()) > 1 || !open.test(b)) break;
            length += Math.hypot(dx, dz);
            end++;
        }
        return length >= MIN_CROSSING ? end : -1;
    }

    private static boolean openWater(Level world, BlockPos node) {
        if (!world.hasChunkAt(node)) return false;
        BlockPos water = world.getFluidState(node).is(FluidTags.WATER) ? node : node.below();
        var air = water.above();
        return world.getFluidState(water).is(FluidTags.WATER) && !world.getBlockState(water).is(Blocks.BUBBLE_COLUMN)
                && world.getBlockState(water).getCollisionShape(world, water).isEmpty()
                && world.getFluidState(air).isEmpty() && world.getBlockState(air).getCollisionShape(world, air).isEmpty();
    }

    private void clear() {
        if (path == null) return;
        path = null;
        lastWaterNode = probeIndex = -1;
        stroke = new SurfaceSwim();
    }
}
