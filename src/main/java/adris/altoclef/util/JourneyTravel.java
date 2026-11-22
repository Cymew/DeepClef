package adris.altoclef.util;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.EnterNetherPortalTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.GoToStrongholdPortalTask;
import adris.altoclef.tasks.speedrun.KillEnderDragonTask;
import adris.altoclef.tasksystem.Task;
import java.util.Map;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;

final class JourneyTravel extends SurvivalAction {
    private enum Leg {
        NETHER("利用已有门或用桶浇筑传送门，进入下界"),
        RETURN("返回主世界寻找要塞"),
        STRONGHOLD("定位主世界要塞，开启传送门并进入末地"),
        DRAGON("击败末影龙");
        final String title;
        Leg(String title) { this.title = title; }
    }

    private final Leg leg;
    private final Task nativeTask;
    private final PlayerJourney journey;

    private JourneyTravel(Leg leg, PlayerJourney journey) {
        super(leg.title);
        this.leg = leg;
        this.journey = journey;
        nativeTask = switch (leg) {
            case NETHER -> new EnterNetherPortalTask(Dimension.NETHER);
            case RETURN -> new DefaultGoToDimensionTask(Dimension.OVERWORLD);
            case STRONGHOLD -> new GoToStrongholdPortalTask(PlayerJourney.TARGET_EYES);
            case DRAGON -> new KillEnderDragonTask();
        };
    }

    static JourneyTravel candidate(AltoClef mod, PlayerJourney journey, Map<String,Integer> stock) {
        var stage = journey.next();
        var dimension = mod.level().dimension();
        var prep = journey.preparation(stock);
        Leg leg = null;
        if (dimension == Level.OVERWORLD && stage == PlayerJourney.Chapter.NETHER && prep == null)
            leg = Leg.NETHER;
        if (stage == PlayerJourney.Chapter.END) {
            if (dimension == Level.OVERWORLD && prep != null && prep.item().equals("minecraft:blaze_rod"))
                leg = Leg.NETHER;
            if (dimension == Level.NETHER && prep == null) leg = Leg.RETURN;
            if (dimension == Level.OVERWORLD && prep == null) leg = Leg.STRONGHOLD;
        }
        if (stage == PlayerJourney.Chapter.DRAGON) {
            if (dimension == Level.END) leg = Leg.DRAGON;
            if (dimension == Level.OVERWORLD) leg = Leg.STRONGHOLD;
            if (dimension == Level.NETHER) leg = Leg.RETURN;
        }
        return leg == null ? null : new JourneyTravel(leg, journey);
    }

    @Override protected void onStart(AltoClef mod) {
        if (leg == Leg.STRONGHOLD || leg == Leg.DRAGON) {
            mod.getBlockTracker().trackBlock(Blocks.END_PORTAL, Blocks.END_PORTAL_FRAME);
            mod.getBehaviour().push();
            mod.getBehaviour().allowWalkingOn(pos -> mod.level().hasChunkAt(pos)
                    && mod.level().getBlockState(pos).is(Blocks.END_PORTAL));
        }
    }

    @Override protected void onStop(AltoClef mod, Task interrupt) {
        if (leg == Leg.STRONGHOLD || leg == Leg.DRAGON) {
            mod.getBehaviour().pop();
            mod.getBlockTracker().stopTracking(Blocks.END_PORTAL, Blocks.END_PORTAL_FRAME);
        }
    }

    @Override protected Task onTick(AltoClef mod) {
        var dimension = mod.level().dimension();
        if (leg == Leg.NETHER && dimension == Level.NETHER || leg == Leg.RETURN && dimension == Level.OVERWORLD
                || leg == Leg.STRONGHOLD && dimension == Level.END) return finish("已到达目标维度");
        if (leg == Leg.DRAGON && dimension == Level.END && mod.getBlockTracker().anyFound(Blocks.END_PORTAL)) {
            journey.mark(PlayerJourney.Chapter.DRAGON.name());
            return finish("末地返回传送门已开启");
        }
        if (leg == Leg.DRAGON && dimension != Level.END) return finish("已离开末地，重新核对进度");
        if (leg == Leg.STRONGHOLD && dimension == Level.OVERWORLD) {
            var tracker = mod.getBlockTracker();
            var portal = tracker.getNearestTracking(Blocks.END_PORTAL);
            if (portal.isPresent()) return new GetToBlockTask(portal.get().above(), false);
            var frame = tracker.getNearestTracking(pos -> mod.level().hasChunkAt(pos)
                    && mod.level().getBlockState(pos).is(Blocks.END_PORTAL_FRAME)
                    && !mod.level().getBlockState(pos).getValue(EndPortalFrameBlock.HAS_EYE), Blocks.END_PORTAL_FRAME);
            if (frame.isPresent()) {
                if (!mod.getItemStorage().hasItem(Items.ENDER_EYE)) return finish("框架仍缺末影之眼，需补给");
                return new InteractWithBlockTask(Items.ENDER_EYE, frame.get());
            }
        }
        return nativeTask;
    }
}
