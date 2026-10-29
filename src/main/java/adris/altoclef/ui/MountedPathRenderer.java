package adris.altoclef.ui;

import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.MountedNavigation;
import adris.altoclef.util.helpers.BoatNavigation;
import adris.altoclef.util.helpers.ElytraNavigation;
import adris.altoclef.util.helpers.SwimNavigation;
import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.event.events.RenderEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.Goal;
import baritone.utils.IRenderer;
import baritone.utils.PathRenderer;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.List;
import java.util.Set;

/** Shows the remaining mounted route through Baritone's existing world render pass. */
public final class MountedPathRenderer implements AbstractGameEventListener {
    private static final Color ROUTE_COLOR = new Color(0x24D9E8);
    private static final Color JUMP_COLOR = new Color(0xFF9A32);
    private static final double ROUTE_HEIGHT = 0.12;
    private static final int FADE_START = 20;
    private static final int FADE_END = 30;
    private final AltoClef _mod;

    public MountedPathRenderer(AltoClef mod) {
        _mod = mod;
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        if (!AltoClef.inGame() || !_mod.getTaskRunner().isActive()) return;
        boolean flying = ElytraNavigation.getRenderPlayer() != null;
        boolean swimming = SwimNavigation.getRenderPlayer() != null;
        Entity horse = swimming ? SwimNavigation.getRenderPlayer() : flying ? ElytraNavigation.getRenderPlayer() : MountedNavigation.getRenderHorse();
        boolean boating = !flying && horse == null && BoatNavigation.getRenderBoat() != null;
        if (boating) horse = BoatNavigation.getRenderBoat();
        if (horse == null || horse.level() != _mod.level()) return;
        Settings settings = Baritone.settings();
        // Snapshot the immutable route once; all geometry below uses these world points.
        List<Vec3> path = swimming ? SwimNavigation.getRenderPath() : flying ? ElytraNavigation.getRenderPath() : boating ? BoatNavigation.getRenderPath() : MountedNavigation.getRenderPath();
        Set<Integer> jumps = swimming || flying || boating ? Set.of() : MountedNavigation.getRenderJumpIndices();
        Goal goal = swimming ? SwimNavigation.getRenderGoal() : flying ? ElytraNavigation.getRenderGoal() : boating ? BoatNavigation.getRenderGoal() : MountedNavigation.getRenderGoal();
        if (settings.renderGoal.value && goal != null) {
            PathRenderer.drawGoal(event.getModelViewStack(), _mod.getClientBaritone().getPlayerContext(),
                    goal, event.getPartialTicks(), settings.colorGoalBox.value);
        }
        if (!settings.renderPath.value || path.size() < 2) return;
        BufferBuilder builder = IRenderer.startLines(ROUTE_COLOR, 0.75f);
        try {
            int limit = settings.fadePath.value ? Math.min(path.size(), FADE_END + 1) : path.size();
            for (int index = 0; index < limit - 1; index++) {
                float alpha = settings.fadePath.value && index > FADE_START
                        ? 0.75f * (FADE_END - index) / (FADE_END - FADE_START) : 0.75f;
                IRenderer.glColor(jumps.contains(index) ? JUMP_COLOR : ROUTE_COLOR, alpha);
                Vec3 from = path.get(index).add(0, ROUTE_HEIGHT, 0);
                Vec3 to = path.get(index + 1).add(0, ROUTE_HEIGHT, 0);
                // emitLine computes a normal, so coincident route samples must be skipped.
                if (from.distanceToSqr(to) > 1.0e-10) {
                    IRenderer.emitLine(builder, event.getModelViewStack(), from, to,
                            settings.pathRenderLineWidthPixels.value);
                }
                if (!settings.renderPathAsLine.value) {
                    IRenderer.emitAABB(builder, event.getModelViewStack(),
                            new AABB(to.x - 0.09, to.y - 0.05, to.z - 0.09,
                                    to.x + 0.09, to.y + 0.09, to.z + 0.09),
                            settings.pathRenderLineWidthPixels.value);
                }
            }
        } finally {
            IRenderer.endLines(builder, settings.renderPathIgnoreDepth.value);
        }
    }
}
