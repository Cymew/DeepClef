package adris.altoclef.util;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.misc.PlaceBedAndSetSpawnTask;
import adris.altoclef.tasksystem.Task;
import net.minecraft.core.BlockPos;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;


/** Prefer a witnessed bed, including village beds, or use a bed carried by the player. */
final class NightSleep extends SurvivalAction {
    private final Task useBed = new PlaceBedAndSetSpawnTask().stayInBed();
    private boolean slept;
    static boolean preparationTime(long clock) {
        int t=Math.floorMod(clock,24000);
        return t>=11000 && t<23000;
    }
    static boolean due(AltoClef mod) {
        return mod.level().dimension()==Level.OVERWORLD && preparationTime(mod.level().getOverworldClockTime());
    }

    private NightSleep() { super("使用 @bed 备床并过夜"); }

    static boolean night(AltoClef mod) {
        return mod.level().dimension() == Level.OVERWORLD && mod.level().isDarkOutside()
                && SurvivalAction.night(true, mod.level().getOverworldClockTime());
    }

    private static boolean safe(AltoClef mod, BlockPos pos) {
        var rule = mod.level().environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, pos);
        return !rule.destroyOnUse() && !rule.destroyOnLeave();
    }

    static NightSleep candidate(AltoClef mod) {
        return due(mod) && safe(mod, mod.getPlayer().blockPosition()) ? new NightSleep() : null;
    }

    @Override protected Task onTick(AltoClef mod) {
        if (mod.getPlayer().isSleeping()) {
            slept = true;
            result = "已确认入睡，等待醒来";
            return null;
        }
        if (slept) return finish("已入睡并醒来，重新观察天色");
        if (!due(mod)) return finish("睡眠时段已结束");
        if (!safe(mod, mod.getPlayer().blockPosition())) return finish("当前位置不可用床");
        result = "原生 @bed 任务执行中，尚未确认入睡";
        return useBed;
    }
    boolean enteredBed() { return slept; }

    @Override boolean retryLater() { return !slept; }
}
