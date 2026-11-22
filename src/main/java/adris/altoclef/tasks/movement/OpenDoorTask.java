package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.SceneObservation;
import adris.altoclef.util.helpers.LandRouteWatch;
import adris.altoclef.util.helpers.LookHelper;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Open one visually selected door. Only a server update can confirm a locally predicted click. */
public final class OpenDoorTask extends Task {
    private static OpenDoorTask pending;
    private final LandRouteWatch route = new LandRouteWatch();
    private final Set<BlockPos> tried = new HashSet<>();
    private ClientLevel world;
    private BlockPos door, stand;
    private Block material;
    private int scanIndex, ticks, clickTick, nextPlan;
    private int aimingTicks;
    private boolean serverOpen;
    private Goal goal;
    private Vec3 progress;
    private int progressTick;
    private String result;

    public String result() { return result == null ? "开门已停止，未确认完成。" : result; }
    @Override public boolean isFinished(AltoClef mod) { return result!=null; }
    @Override protected boolean isEqual(Task other) { return this==other; }
    @Override protected String toDebugString() { return "打开可见的门"; }

    @Override protected void onStart(AltoClef mod) {
        if (world==null) world=mod.level();
        mod.getBehaviour().push();
        mod.getBehaviour().avoidBlockBreaking(p -> true);
        mod.getBehaviour().avoidBlockPlacing(p -> true);
        mod.getBehaviour().avoidWalkingThrough(p -> p.equals(door) || p.below().equals(door));
        stopPath(mod);
        stand=null;
        progress=null;
        nextPlan=ticks;
        if (clickTick!=0) pending=this;
    }

    @Override protected Task onTick(AltoClef mod) {
        var player=mod.getPlayer();
        if (++ticks>300 || player==null || world!=mod.level() || !player.isAlive())
            return finish("开门未完成，任务超时或世界已变化。");
        if (door==null) {
            BlockPos seen=SceneObservation.visibleDoor(mod,scanIndex,32);
            scanIndex+=32;
            if (seen==null) return scanIndex>=SceneObservation.DOOR_SCAN_SAMPLES ? finish("16 格视野内未辨认到门。") : null;
            BlockState state=world.getBlockState(seen);
            door=state.getValue(DoorBlock.HALF)==DoubleBlockHalf.UPPER ? seen.below() : seen;
            material=state.getBlock();
            if (state.getValue(DoorBlock.OPEN)) return finish("看见目标门已经打开。");
            if (!DoorBlock.isWoodenDoor(state)) return finish("这扇门不能用手打开。");
        }
        if (!world.hasChunkAt(door)) return finish("目标已离开加载范围。");
        var state=world.getBlockState(door);
        if (clickTick!=0) {
            if (confirmed(serverOpen,material,state))
                return finish("已确认目标门打开。");
            if (ticks-clickTick>60) return finish("未收到目标门已打开的确认。");
            return null;
        }
        if (!state.is(material)) return finish("原目标已变化，开门未完成。");
        if (state.getValue(DoorBlock.OPEN) && (SceneObservation.visibleBlock(mod,door)
                || SceneObservation.visibleBlock(mod,door.above()))) return finish("看见目标门已经打开。");
        var hit=reachable(mod,player.getEyePosition());
        if (hit!=null) {
            if (goal!=null) stopPath(mod);
            if (state.getValue(DoorBlock.OPEN)) return finish("已观察到目标门打开。");
            LookHelper.lookAt(mod,hit.getLocation(),false);
            // Wait until the client's own pick agrees, including an entity blocking the cursor.
            if (!(Minecraft.getInstance().hitResult instanceof BlockHitResult pick) || pick.getType()!=HitResult.Type.BLOCK
                    || !pick.getBlockPos().equals(hit.getBlockPos()) || player.isSecondaryUseActive()
                    || pick.getLocation().distanceTo(player.getEyePosition())>player.blockInteractionRange()) {
                setDebugState("正在对准门的可点击表面。");
                return ++aimingTicks>=40 ? finish("准星未能对准目标门，未执行点击。") : null;
            }
            pending=this;
            clickTick=ticks;
            Minecraft.getInstance().gameMode.useItemOn(player,InteractionHand.MAIN_HAND,pick);
            setDebugState("已使用目标门，等待服务端确认。");
            return null;
        }
        aimingTicks=0;
        if (route.changed(mod)) { stopPath(mod); stand=null; nextPlan=ticks+1; return null; }
        if (ticks<nextPlan) return null;
        if (progress==null || progress.distanceToSqr(player.position())>.25) {
            progress=player.position();
            progressTick=ticks;
        } else if (ticks-progressTick>40) {
            if (stand!=null) tried.add(stand);
            stand=null;
            stopPath(mod);
            progressTick=ticks;
        }
        if (player.blockPosition().distSqr(door)<=49 && stand==null) {
            stand=chooseStand(mod);
            if (stand==null && player.blockPosition().distSqr(door)<=9) return finish("门前没有找到可站立并可点击的位置。");
        }
        Goal wanted=stand==null ? new GoalNear(door,3) : new GoalBlock(stand);
        var process=mod.getClientBaritone().getCustomGoalProcess();
        if (!wanted.equals(goal) || !process.isActive()) {
            process.setGoalAndPath(wanted);
            goal=wanted;
        }
        nextPlan=ticks+4;
        setDebugState("走向门前可交互的位置。");
        return null;
    }

    private BlockPos chooseStand(AltoClef mod) {
        var player=mod.getPlayer();
        var origin=player.blockPosition();
        BlockPos best=null;
        double distance=Double.POSITIVE_INFINITY;
        for (BlockPos p:BlockPos.betweenClosed(origin.offset(-4,-2,-4),origin.offset(4,2,4))) {
            double score=p.distSqr(origin);
            if (score==0 || score>16 || score>=distance || p.distSqr(door)>25 || tried.contains(p)
                    || p.equals(door) || p.equals(door.above())) continue;
            double floor=LandRouteWatch.dryStance(world,p);
            if (!Double.isFinite(floor)) continue;
            var eye=new Vec3(p.getX()+.5,floor+player.getEyeHeight(),p.getZ()+.5);
            if (reachable(mod,eye)!=null) { best=p.immutable(); distance=score; }
        }
        return best;
    }

    private BlockHitResult reachable(AltoClef mod,Vec3 eye) {
        return reachable(world,door,material,eye,mod.getPlayer().blockInteractionRange(),
                net.minecraft.world.phys.shapes.CollisionContext.of(mod.getPlayer()));
    }

    static BlockHitResult reachable(net.minecraft.world.level.BlockGetter world,BlockPos door,Block material,
                                    Vec3 eye,double reach,net.minecraft.world.phys.shapes.CollisionContext body) {
        for (BlockPos half:List.of(door,door.above())) {
            var state=world.getBlockState(half);
            if (!state.is(material) || Vec3.atCenterOf(half).distanceTo(eye)>reach+1) continue;
            var shape=state.getShape(world,half);
            if (shape.isEmpty()) continue;
            Vec3 aim=shape.bounds().getCenter().add(half.getX(),half.getY(),half.getZ());
            var hit=world.clip(new ClipContext(eye,aim,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,body));
            if (hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(half)
                    && hit.getLocation().distanceTo(eye)<=reach-.02) return hit;
        }
        return null;
    }

    public static void serverState(ClientLevel level,BlockPos pos,BlockState state) {
        var task=pending;
        if (task!=null && task.world==level && (pos.equals(task.door) || pos.equals(task.door.above())))
            task.serverOpen=state.is(task.material) && state.getValue(DoorBlock.OPEN)
                    && (state.getValue(DoorBlock.HALF)==DoubleBlockHalf.LOWER)==pos.equals(task.door);
    }

    static boolean confirmed(boolean serverOpen,Block material,BlockState lower) {
        return serverOpen && lower.is(material) && lower.getValue(DoorBlock.OPEN)
                && lower.getValue(DoorBlock.HALF)==DoubleBlockHalf.LOWER;
    }

    private Task finish(String reason) { result=reason; return null; }
    private void stopPath(AltoClef mod) {
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        goal=null;
        route.reset();
    }
    @Override protected void onStop(AltoClef mod,Task interrupt) {
        if (pending==this) pending=null;
        stopPath(mod);
        mod.getBehaviour().pop();
    }
}
