package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.entity.KillEntityTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasks.movement.SearchChunkForBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrialSpawnerBlock;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/** Activates visible trial spawners, fights actual spawned mobs and collects their native key reward. */
public final class TrialKeyAcquisitionTask extends ResourceTask {
    private final boolean ominous;
    private final int count;
    private final Map<BlockPos, Integer> revisitAfter = new HashMap<>();
    private final Task explore = new SearchChunkForBlockTask(Blocks.TRIAL_SPAWNER, Blocks.TUFF_BRICKS, Blocks.CHISELED_TUFF);
    private BlockPos site;
    private int startedTick, cooldownTick = -1;
    private boolean success;
    private String failure;
    private Task drink;
    public TrialKeyAcquisitionTask(boolean ominous, int count) {
        super(ominous ? Items.OMINOUS_TRIAL_KEY : Items.TRIAL_KEY, count);
        if (count < 1 || count > 2304) throw new IllegalArgumentException("Invalid trial key count");
        this.ominous = ominous; this.count = count;
    }
    public boolean succeeded() { return success && failure == null; }
    public String getResult() { return failure != null ? failure : success ? "已通过原版试炼获取钥匙。" : "试炼钥匙尚未获取完成。"; }
    @Override public boolean isFinished(AltoClef mod) { if (failure != null) return true; success = mod != null && super.isFinished(mod); return success; }
    @Override protected void onResourceStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.TRIAL_SPAWNER);
        mod.getBehaviour().avoidBlockBreaking(pos -> mod.level().hasChunkAt(pos) && mod.level().getBlockState(pos).is(Blocks.TRIAL_SPAWNER));
        mod.getBehaviour().addProtectedItems(Items.OMINOUS_BOTTLE, Items.TRIAL_KEY, Items.OMINOUS_TRIAL_KEY);
    }
    @Override protected Task onResourceTick(AltoClef mod) {
        if (mod.getPlayer().isCreative() || mod.getPlayer().isSpectator()) { failure = "当前模式无法激活原版试炼刷怪笼；请在生存或冒险模式获取钥匙。"; return null; }
        if (mod.level().getDifficulty() == Difficulty.PEACEFUL) { failure = "和平难度不会激活试炼刷怪笼，当前不能从试炼获取钥匙。"; return null; }
        if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
        int tick = mod.getPlayer().tickCount;
        if (site == null) {
            site = mod.getBlockTracker().getNearestTracking(pos -> mod.level().hasChunkAt(pos)
                    && mod.level().getBlockState(pos).is(Blocks.TRIAL_SPAWNER)
                    && tick >= revisitAfter.getOrDefault(pos, 0)
                    && (ominous || !mod.level().getBlockState(pos).getValue(TrialSpawnerBlock.OMINOUS)), Blocks.TRIAL_SPAWNER).orElse(null);
            if (site != null) { startedTick = tick; cooldownTick = -1; }
        }
        if (site == null) { setDebugState("Exploring for trial chambers and an eligible trial spawner."); return explore; }
        if (!mod.level().hasChunkAt(site)) return new GetWithinRangeOfBlockTask(site, 4);
        var state = mod.level().getBlockState(site);
        if (!state.is(Blocks.TRIAL_SPAWNER) || tick - startedTick > 4800) { defer(tick, 600); return null; }
        TrialSpawnerState phase = state.getValue(TrialSpawnerBlock.STATE);
        boolean isOminous = state.getValue(TrialSpawnerBlock.OMINOUS);
        if (!ominous && isOminous) { defer(tick, 36000); return null; }
        if (ominous && !isOminous && !mod.getPlayer().hasEffect(MobEffects.BAD_OMEN) && !mod.getPlayer().hasEffect(MobEffects.TRIAL_OMEN)) {
            if (drink == null || drink.isFinished(mod)) drink = new DrinkOminousBottleTask();
            return drink;
        }
        if (phase == TrialSpawnerState.COOLDOWN && (!ominous || isOminous)) {
            if (cooldownTick < 0) cooldownTick = tick;
            if (tick - cooldownTick > 60) { defer(tick, 36000); return null; }
        }
        if (phase == TrialSpawnerState.INACTIVE && tick - startedTick > 100) { defer(tick, 1200); return null; }
        if (phase == TrialSpawnerState.ACTIVE) {
            Entity nearest = null;
            for (Entity hostile : mod.level().entitiesForRendering()) {
                if (!(hostile instanceof Monster || hostile instanceof Slime) || !hostile.isAlive()
                        || hostile.position().distanceToSqr(Vec3.atCenterOf(site)) > 32 * 32) continue;
                if (nearest == null || hostile.distanceToSqr(mod.getPlayer()) < nearest.distanceToSqr(mod.getPlayer())) nearest = hostile;
            }
            if (nearest != null) { setDebugState("Defeating the active trial's mobs before collecting its native reward."); return new KillEntityTask(nearest); }
        }
        Vec3 eye = mod.getPlayer().getEyePosition(), centre = Vec3.atCenterOf(site);
        var hit = mod.level().clip(new ClipContext(eye, centre, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mod.getPlayer()));
        if (eye.distanceToSqr(centre) > 10 * 10 || hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(site))
            return new GetWithinRangeOfBlockTask(site, 3);
        // Stay close to the reward ejection. ResourceTask picks up matching item entities first.
        if (phase == TrialSpawnerState.EJECTING_REWARD || phase == TrialSpawnerState.WAITING_FOR_REWARD_EJECTION || phase == TrialSpawnerState.COOLDOWN)
            return new GetWithinRangeOfBlockTask(site, 2);
        if (tick - startedTick > 1200 && phase == TrialSpawnerState.WAITING_FOR_PLAYERS) { defer(tick, 1200); return null; }
        setDebugState("Waiting in the native trial activation range; current state: " + phase.getSerializedName());
        return null;
    }
    private void defer(int tick, int delay) { revisitAfter.put(site, tick + delay); site = null; cooldownTick = -1; }
    @Override protected void onResourceStop(AltoClef mod, Task interruptTask) { mod.getBlockTracker().stopTracking(Blocks.TRIAL_SPAWNER); }
    @Override protected boolean isEqualResource(ResourceTask other) { return other instanceof TrialKeyAcquisitionTask task && task.ominous == ominous; }
    @Override protected boolean shouldAvoidPickingUp(AltoClef mod) { return false; }
    @Override protected String toDebugStringName() { return "Get " + (ominous ? "ominous " : "") + "trial key x" + count; }
}
