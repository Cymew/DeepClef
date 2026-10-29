package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.SwimNavigation;
import net.minecraft.core.BlockPos;

/** Explicit swimming starts in water; normal goto handles walking to/from the water. */
public final class SwimTask extends Task {
    private final BlockPos goal;
    private final boolean surface, ascend;
    private SwimNavigation navigation;
    private boolean done, success;
    private String result="游泳已中断，尚未确认到达。";
    private SwimTask(BlockPos goal,boolean surface,boolean ascend) { this.goal=goal;this.surface=surface;this.ascend=ascend; }
    public static SwimTask travel(BlockPos goal) { return new SwimTask(goal,false,false); }
    public static SwimTask surface(int x,int z) { return new SwimTask(new BlockPos(x,0,z),true,false); }
    public static SwimTask ascend() { return new SwimTask(BlockPos.ZERO,true,true); }
    public String getResult() { return result; }
    public boolean succeeded() { return done&&success; }
    public static SwimTask forExecution(Task current,SwimTask requested) {
        return current instanceof SwimTask old && old.equals(requested)?old:requested;
    }
    @Override protected void onStart(AltoClef mod) { }
    @Override protected Task onTick(AltoClef mod) {
        if(done)return null;
        if(mod.getPlayer()==null || mod.level()==null) {done=true;result="未进入世界。";return null;}
        if(navigation==null) {
            if(!mod.getPlayer().isInWater() && !ascend) {done=true;result="请先进入水体，再使用 swim；陆水混合行程请用 goto。";return null;}
            navigation=new SwimNavigation(mod,ascend?mod.getPlayer().blockPosition():goal,surface,ascend);
        }
        var outcome=navigation.tick(mod);setDebugState(navigation.result());
        if(outcome!=SwimNavigation.Outcome.RUNNING) {
            done=true;success=outcome==SwimNavigation.Outcome.ARRIVED;result=navigation.result();navigation.release(mod);
            if(outcome==SwimNavigation.Outcome.SHORE)result="已抵达岸边，但未确认原目标；请用 goto 继续陆路。";
        }
        return null;
    }
    @Override public boolean isFinished(AltoClef mod) {return done;}
    @Override protected void onStop(AltoClef mod,Task interrupt) {if(navigation!=null)navigation.release(mod);}
    @Override protected boolean isEqual(Task other) {return other instanceof SwimTask s&&s.goal.equals(goal)&&s.surface==surface&&s.ascend==ascend;}
    @Override protected String toDebugString() {return ascend?"Swimming to air":"Swimming "+(surface?"on surface to ":"in 3D to ")+goal;}
}
