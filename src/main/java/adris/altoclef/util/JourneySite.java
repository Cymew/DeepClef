package adris.altoclef.util;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.entity.KillEntityTask;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasksystem.Task;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrialSpawnerBlock;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState;
import java.util.Comparator;

/** A side trip owns only its witnessed landmark. The inventory project remains suspended. */
final class JourneySite extends SurvivalAction {
    private final BlockPos pos;
    private final Block block;
    private final Task approach;
    private final PlayerJourney journey;
    private boolean activeTrial;
    private int started, nextUse;
    private final String key;

    static JourneySite candidate(AltoClef mod, BlockPos pos, PlayerJourney journey) {
        var block = mod.level().getBlockState(pos).getBlock();
        String name;
        if (block == Blocks.TRIAL_SPAWNER) name = "支线：参加目击的试炼刷怪笼挑战";
        else if (block == Blocks.REINFORCED_DEEPSLATE) name = "支线：考察疑似远古城市的强化深板岩遗迹";
        else if (block == Blocks.NETHER_PORTAL && mod.level().dimension() == net.minecraft.world.level.Level.OVERWORLD
                && journey.next() == PlayerJourney.Chapter.NETHER) name = "从目击的下界传送门进入下界";
        else if (block == Blocks.END_PORTAL_FRAME && journey.next() == PlayerJourney.Chapter.END
                && !mod.level().getBlockState(pos).getValue(net.minecraft.world.level.block.EndPortalFrameBlock.HAS_EYE)
                && mod.getPlayer().getInventory().countItem(net.minecraft.world.item.Items.ENDER_EYE) > 0)
            name = "给目击的末地传送门框架装入末影之眼";
        else if (block == Blocks.END_PORTAL && mod.level().dimension() != net.minecraft.world.level.Level.END
                && journey.next() == PlayerJourney.Chapter.END) name = "从已开启的末地传送门进入末地";
        else return null;
        var action = new JourneySite(mod, pos, block, name, journey);
        return journey.has(action.key) ? null : action;
    }

    private JourneySite(AltoClef mod, BlockPos pos, Block block, String name, PlayerJourney journey) {
        super(name + " " + pos.toShortString());
        this.pos = pos; this.block = block; this.journey = journey;
        key = "site:" + block.getDescriptionId() + ":" + mod.level().dimension().identifier() + ":" + pos.asLong();
        approach = block == Blocks.NETHER_PORTAL || block == Blocks.END_PORTAL
                ? new GetToBlockTask(pos) : new GetWithinRangeOfBlockTask(pos, 3, false);
    }

    @Override protected Task onTick(AltoClef mod) {
        var p = mod.getPlayer();
        if (started == 0) started = p.tickCount;
        if (p.getHealth() < 12 || p.getFoodData().getFoodLevel() < 8) return finish("状态不足，暂离现场");
        if (!mod.level().hasChunkAt(pos) || !mod.level().getBlockState(pos).is(block)) return finish("原地标已变化");
        if (!p.blockPosition().closerThan(pos, 6)) return approach;
        if (block == Blocks.NETHER_PORTAL || block == Blocks.END_PORTAL)
            return approach.isFinished(mod) ? null : approach;
        var hit = SceneObservation.surfaceHit(mod, pos, false);
        if (hit == null) return finish("近处未能复核原地标");
        if (block == Blocks.END_PORTAL_FRAME) {
            if (mod.level().getBlockState(pos).getValue(net.minecraft.world.level.block.EndPortalFrameBlock.HAS_EYE))
                return finish("已复核框架内有末影之眼");
            if (p.getEyePosition().distanceTo(hit.getLocation()) > p.blockInteractionRange()) return approach;
            if (p.getInventory().countItem(net.minecraft.world.item.Items.ENDER_EYE) == 0) return finish("随身末影之眼已用尽");
            if (mod.getSlotHandler().forceEquipItem(net.minecraft.world.item.Items.ENDER_EYE)
                    && p.getMainHandItem().is(net.minecraft.world.item.Items.ENDER_EYE) && p.tickCount >= nextUse) {
                mod.getController().useItemOn(p, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
                nextUse = p.tickCount + 10;
            }
            return null;
        }
        if (block == Blocks.REINFORCED_DEEPSLATE) {
            journey.mark(key);
            return finish("已在近处查验强化深板岩；这是远古城市线索");
        }
        var phase = mod.level().getBlockState(pos).getValue(TrialSpawnerBlock.STATE);
        if (phase == TrialSpawnerState.ACTIVE) {
            activeTrial = true;
            var mob = mod.level().getEntitiesOfClass(Monster.class, p.getBoundingBox().inflate(16),
                    e -> e.isAlive() && e.blockPosition().closerThan(pos, 16) && VisibleEntityTargets.visiblePoint(mod, e, false) != null)
                    .stream().min(Comparator.comparingDouble(p::distanceToSqr));
            if (mob.isPresent()) return new KillEntityTask(mob.get());
        }
        if (phase == TrialSpawnerState.COOLDOWN || phase == TrialSpawnerState.EJECTING_REWARD) {
            if (activeTrial) journey.mark(key);
            return finish(activeTrial ? "已观察到本次试炼从战斗进入结算" : "到达时试炼已结束");
        }
        return p.tickCount - started > 1800 ? finish("未见试炼结算，返回主线") : null;
    }
}
