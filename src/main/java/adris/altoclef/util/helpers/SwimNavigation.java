package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.control.SwimInput;
import adris.altoclef.tasksystem.TaskChain;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** One swimming lease owns look, movement and oxygen decisions until arbitration yields it. */
public final class SwimNavigation {
    public enum Outcome { RUNNING, ARRIVED, SHORE, FAILED }
    private static SwimNavigation current;
    private static final Input[] KEYS={Input.MOVE_FORWARD,Input.MOVE_BACK,Input.MOVE_LEFT,Input.MOVE_RIGHT,Input.JUMP,Input.SNEAK,Input.SPRINT};
    private final LocalPlayer player;
    private final ClientLevel world;
    private final BlockPos goal;
    private final boolean surface, ascend;
    private final SwimInput input=new SwimInput();
    private SwimRoutePlanner routeSearch, airSearch;
    private List<Vec3> route=List.of(), escape=List.of();
    private int requestTick, lastTick=-1, routeTick=-100, airTick=-100, stalled, arrivalSamples, refugeLoops;
    private int doorTick=-100, originalSlot=-1, equippedSlot=-1;
    private BlockPos pendingDoor;
    private final Set<BlockPos> attemptedDoors=new HashSet<>();
    private Vec3 progressPosition, lastRefuge;
    private float yaw,pitch,previousYaw,previousPitch;
    private boolean forward,jump,sneak,sprint,refilling,aborting;
    private boolean compactPose;
    private String message="Planning loaded water.";
    public SwimNavigation(AltoClef mod, BlockPos goal, boolean surface, boolean ascend) {
        this.player=mod.getPlayer(); this.world=mod.level(); this.goal=goal.immutable(); this.surface=surface; this.ascend=ascend;
        yaw=previousYaw=player.getYRot(); pitch=previousPitch=player.getXRot(); progressPosition=player.position();
        compactPose=player.isSwimming();
    }
    public String result() { return message; }
    public static boolean ownsSwimming(AltoClef mod) { return isActive() && current.player==mod.getPlayer(); }
    public static boolean handlesOxygen(AltoClef mod) {
        if (ownsSwimming(mod)) return true;
        var root=mod.getUserTaskChain().getCurrentTask();
        return root!=null && root.thisOrChildSatisfies(t ->
                t instanceof adris.altoclef.tasks.movement.SwimTask && !t.isFinished(mod)
                || t instanceof adris.altoclef.tasks.movement.CustomBaritoneGoalTask travel && travel.hasSwimController());
    }
    private static boolean isActive() { return current!=null && current.player==Minecraft.getInstance().player
            && current.world==Minecraft.getInstance().level && current.player.isAlive() && !current.player.isPassenger()
            && current.requestTick>=current.player.tickCount-1; }
    public static boolean needsPriority(AltoClef mod) { return ownsSwimming(mod) && (mod.getPlayer().isUnderWater() || current.refilling); }
    public static String status(AltoClef mod) {
        if(mod.getPlayer()==null)return "未进入世界。";
        return "氧气="+mod.getPlayer().getAirSupply()+"/"+mod.getPlayer().getMaxAirSupply()+"，游泳姿态="+mod.getPlayer().isSwimming()
                +" | "+(ownsSwimming(mod)?current.message:"Swimming idle");
    }
    private boolean protectedAir() {
        if(player.getAbilities().invulnerable)return true;
        for(var effect:List.of(MobEffects.WATER_BREATHING,MobEffects.CONDUIT_POWER)) {
            var value=player.getEffect(effect);
            if(value!=null && (value.isInfiniteDuration() || value.getDuration()>160))return true;
        }
        return false;
    }
    public Outcome tick(AltoClef mod) {
        if(mod.getPlayer()!=player || mod.level()!=world || !player.isAlive() || player.isPassenger()
                || player.isFallFlying() || player.getAbilities().flying || player.isInLava())return fail("游泳停止：玩家/世界/载具状态变化。");
        if(current!=this) {
            releaseAll(mod); current=this; routeSearch=airSearch=null; route=escape=List.of(); routeTick=airTick=-100;
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getPathingBehavior().forceCancel();
        }
        requestTick=player.tickCount;
        if(lastTick==requestTick)return Outcome.RUNNING;
        lastTick=requestTick; previousYaw=yaw; previousPitch=pitch;
        forward=jump=sneak=sprint=false;
        if(compactPose!=player.isSwimming()) {
            compactPose=player.isSwimming();routeSearch=airSearch=null;routeTick=airTick=-100;
        }
        mod.getClientBaritone().getPathingBehavior().requestPause();
        SwimTerrain terrain=new SwimTerrain(mod);
        Vec3 pos=player.position();
        var here=terrain.kind(player.blockPosition());
        boolean bubble=here==SwimRoutePlanner.Kind.UP || here==SwimRoutePlanner.Kind.DOWN;
        // Vanilla replenishes air at the eyes, not merely when the feet touch a column.
        boolean eyesInBubble=world.getBlockState(BlockPos.containing(player.getEyePosition())).is(Blocks.BUBBLE_COLUMN);
        boolean breathing=!player.isUnderWater() || eyesInBubble || protectedAir();
        if(!player.isInWater() && player.onGround() && !refilling && !ascend) {
            if(surface && !reached(pos))return finish(Outcome.SHORE,"已游到可站立岸边，水路结束。");
            if(!reached(pos))return fail("已离开水体，水下目标尚未到达。");
        }
        // Search for air independently of the long route. All search work shares one frame deadline.
        long deadline=System.nanoTime()+2_000_000;
        if(escape.isEmpty() || requestTick-airTick>=20) {
            // The common open-ocean case needs a cheap vertical proof, not a spherical flood fill.
            List<Vec3> vertical=new ArrayList<>(); Vec3 previous=pos;
            for(int dy=0;dy<=36 && System.nanoTime()<deadline;dy++) {
                BlockPos cell=player.blockPosition().above(dy);
                var kind=terrain.kind(cell); Vec3 point=terrain.point(cell);
                if(kind==SwimRoutePlanner.Kind.BLOCKED || !terrain.clear(previous,point))break;
                vertical.add(point);previous=point;
                if(kind.breath()) {
                    if(escape.isEmpty() || remaining(pos,vertical)<remaining(pos,escape))escape=List.copyOf(vertical);
                    break;
                }
            }
        }
        if(airSearch==null && (escape.isEmpty() || requestTick-airTick>=20)) {
            airSearch=new SwimRoutePlanner(terrain,player.blockPosition(),goal,true,false,false); airTick=requestTick;
        }
        if(airSearch!=null) {
            airSearch.step(80,deadline);
            if(airSearch.done()) {
                if(airSearch.reached() && (escape.isEmpty() || remaining(pos,airSearch.path())<remaining(pos,escape)))escape=airSearch.path();
                else if(!validEnd(terrain,escape))escape=List.of();
                airSearch=null;
            }
        }
        if(!validEnd(terrain,escape))escape=List.of();
        double escapeLength=remaining(pos,escape);
        double speed=player.getFoodData().getFoodLevel()>6 && !player.hasEffect(MobEffects.BLINDNESS) ? .11 : .06;
        if(ascend || surface && here==SwimRoutePlanner.Kind.WATER || !protectedAir() && SwimRoutePlanner.needsAir(player.getAirSupply(),player.getMaxAirSupply(),
                escape.isEmpty()?20:escapeLength,speed))refilling=true;
        if(refilling && breathing && (!ascend || !player.isUnderWater() || eyesInBubble)
                && (!surface || here!=SwimRoutePlanner.Kind.WATER)) {
            message="Refilling air before continuing.";
            if(player.getAirSupply()>=player.getMaxAirSupply()-4 || protectedAir()) {
                if(ascend)return finish(Outcome.ARRIVED,"已抵达可呼吸位置并补充氧气。");
                if(aborting)return fail("已退回空气处；剩余水路超出当前氧气/通行能力，未到达目标。");
                if(lastRefuge!=null && pos.distanceTo(lastRefuge)<3)refugeLoops++; else refugeLoops=0;
                lastRefuge=pos;
                if(refugeLoops>=2)return fail("已安全补氧，但重复尝试仍无法推进；需要中继空气空间或水下呼吸效果。");
                refilling=false; routeSearch=null; routeTick=-100;
            } else {
                // Let a column move vertically while staying inside it; never fight it with repeated routes.
                jump=!bubble && !player.onGround();
                sneak=here==SwimRoutePlanner.Kind.DOWN || magmaBelow();
                Vec3 centre=new Vec3(Math.floor(pos.x)+.5,pos.y,Math.floor(pos.z)+.5);
                if(Math.hypot(pos.x-centre.x,pos.z-centre.z)>.17)steer(terrain,centre,false);
                return Outcome.RUNNING;
            }
        }
        if(!refilling && reached(pos)) {
            if(++arrivalSamples>=3)return finish(Outcome.ARRIVED,"已确认抵达游泳目标。");
        } else arrivalSamples=0;
        // Door placement is an emergency fallback, not a source of imaginary oxygen.
        if(refilling && !breathing && (escape.isEmpty() || player.getAirSupply()<escapeLength/speed+35)) {
            if(doorRescue(mod,terrain))return Outcome.RUNNING;
        }
        if(refilling) {
            message=ascend?"Finding breathable water/air.":"Returning to air reserve.";
            if(!follow(terrain,escape)) {
                // A verified vertical step is useful while an escape search is still pending.
                Vec3 up=pos.add(0,1.2,0);
                if(terrain.clear(pos,up))steer(terrain,up,true);
                else { jump=false; message="Searching around the submerged ceiling for air."; }
            }
        } else {
            if(routeSearch==null && (route.isEmpty() || requestTick-routeTick>=35 || remaining(pos,route)<10)) {
                routeSearch=new SwimRoutePlanner(terrain,player.blockPosition(),goal,false,surface,surface); routeTick=requestTick;
            }
            if(routeSearch!=null) {
                routeSearch.step(100,deadline);
                if(routeSearch.done()) {
                    List<Vec3> candidate=routeSearch.path();
                    if(candidate.size()>1 && joinable(terrain,pos,candidate))route=candidate;
                    routeSearch=null;
                }
            }
            message=surface?"Swimming continuously along the surface.":"Following 3D water route.";
            if(!breathing && !protectedAir() && escape.isEmpty()) {
                message="Locating a reachable oxygen reserve before diving farther.";
                if(terrain.clear(pos,pos.add(0,1,0))) { jump=true; pitch=SwimRoutePlanner.turn(pitch,-65,10); }
            } else if(!follow(terrain,route)) {
                message="Planning the next loaded water corridor.";
                jump=surface && !breathing;
                // Enter the native swimming pose in verified open water while the longer search runs.
                if(!player.isSwimming() && player.isUnderWater() && !surface && breathingOrReserve()) {
                    Vec3 ahead=new Vec3(goal.getX()+.5-pos.x,0,goal.getZ()+.5-pos.z).normalize().scale(.7).add(pos);
                    if(terrain.clear(pos,ahead))steer(terrain,ahead,true);
                }
            }
        }
        if(magmaBelow())sneak=true;
        if(pos.distanceTo(progressPosition)>1.5) { progressPosition=pos; stalled=0; }
        else stalled++;
        if(stalled>160 && !refilling) { refilling=aborting=true; route=List.of(); }
        if(stalled>420)return fail("水路受阻且无法继续推进，未确认到达；已交还生存保护。");
        return Outcome.RUNNING;
    }
    private boolean reached(Vec3 p) {
        return atGoal(p,goal,surface);
    }
    static boolean atGoal(Vec3 p,BlockPos goal,boolean surface) {
        return Math.hypot(p.x-goal.getX()-.5,p.z-goal.getZ()-.5)<.8
                && (surface || Math.abs(p.y-goal.getY())<.85);
    }
    private boolean breathingOrReserve() { return protectedAir() || player.getAirSupply()>200 && !escape.isEmpty(); }
    private static boolean validEnd(SwimTerrain t,List<Vec3> route) {
        return !route.isEmpty() && t.kind(BlockPos.containing(route.getLast())).breath();
    }
    private static int nearest(Vec3 pos,List<Vec3> path) {
        int best=0; double d=Double.POSITIVE_INFINITY;
        for(int i=0;i<path.size();i++) { double n=pos.distanceToSqr(path.get(i)); if(n<d) {d=n;best=i;} }
        return best;
    }
    private static double remaining(Vec3 pos,List<Vec3> path) {
        return path.isEmpty()?Double.POSITIVE_INFINITY:SwimRoutePlanner.length(pos,path.subList(nearest(pos,path),path.size()));
    }
    private static boolean joinable(SwimTerrain t,Vec3 pos,List<Vec3> path) {
        return !path.isEmpty() && t.clear(pos,path.get(nearest(pos,path)));
    }
    private boolean follow(SwimTerrain terrain,List<Vec3> path) {
        if(path.isEmpty())return false;
        Vec3 pos=player.position(), aim=null;
        int start=nearest(pos,path);
        double lead=Math.max(2,Math.min(4,player.getDeltaMovement().length()*12));
        for(int i=start;i<path.size() && i<=start+6;i++) {
            Vec3 p=path.get(i);
            if(pos.distanceTo(p)>lead+1 || !terrain.clear(pos,p))break;
            aim=p;
        }
        if(aim==null)return false;
        if(aim.distanceTo(pos)<.3 && start==path.size()-1) {
            jump=surface && player.isUnderWater(); return true;
        }
        steer(terrain,aim,true); return true;
    }
    private void steer(SwimTerrain terrain,Vec3 aim,boolean allowSprint) {
        Vec3 delta=aim.subtract(player.position());
        double horizontal=delta.horizontalDistance();
        float targetYaw=horizontal<.08?yaw:(float)Math.toDegrees(Math.atan2(-delta.x,delta.z));
        float targetPitch=(float)-Math.toDegrees(Math.atan2(delta.y,Math.max(.05,horizontal)));
        yaw=SwimRoutePlanner.turn(yaw,targetYaw,14);
        pitch=SwimRoutePlanner.turn(pitch,Mth.clamp(targetPitch,-80,80),10);
        float error=Math.abs(Mth.wrapDegrees(targetYaw-yaw));
        // Native forward thrust is horizontal (yaw), even when pitch is vertical.
        // Use jump/sneak alone in a shaft instead of orbiting its centre with W held.
        forward=error<65 && horizontal>.22;
        sprint=forward && allowSprint && player.getFoodData().getFoodLevel()>6 && !player.hasEffect(MobEffects.BLINDNESS);
        jump=delta.y>.25 || surface && player.isUnderWater() && !refilling;
        sneak=delta.y<-.45 && !surface || magmaBelow();
        // Check actual inertial heading as well as the chosen path chord.
        Vec3 velocity=player.getDeltaMovement();
        Vec3 predicted=player.position().add(velocity.scale(3));
        if(velocity.length()>.06 && !terrain.clear(player.position(),predicted)) { forward=false; sprint=false; }
    }
    private boolean magmaBelow() {
        BlockPos p=player.blockPosition();
        for(int i=0;i<=2;i++)if(world.hasChunkAt(p.below(i)) && world.getBlockState(p.below(i)).is(Blocks.MAGMA_BLOCK))return true;
        return false;
    }
    private boolean doorRescue(AltoClef mod,SwimTerrain terrain) {
        if(pendingDoor!=null) {
            if(world.getBlockState(pendingDoor).getBlock() instanceof DoorBlock
                    && world.getFluidState(pendingDoor).isEmpty()) {
                if(player.tickCount-doorTick>100) {pendingDoor=null;return false;}
                Vec3 air=new Vec3(pendingDoor.getX()+.5,pendingDoor.getY()+.08,pendingDoor.getZ()+.5);
                if(terrain.clear(player.position(),air)) { escape=List.of(air); steer(terrain,air,false); }
                else pendingDoor=null;
                if(player.getAirSupply()>=player.getMaxAirSupply()-4)pendingDoor=null;
                return true;
            }
            if(player.tickCount-doorTick<30) { jump=true; return true; }
            pendingDoor=null; // Do not spam a denied/stale placement.
        }
        if(player.tickCount-doorTick<60 || player.isUsingItem() || attemptedDoors.size()>=16)return false;
        Item door=null;
        for(int i=0;i<36;i++) {
            var stack=player.getInventory().getItem(i);
            if(!stack.isEmpty() && stack.typeHolder().is(ItemTags.WOODEN_DOORS)) {door=stack.getItem();break;}
        }
        if(door==null)return false;
        BlockPos feet=player.blockPosition();
        for(int dy=0;dy>=-3;dy--)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
            BlockPos at=feet.offset(dx,dy,dz), floor=at.below();
            if(attemptedDoors.contains(at) || !terrain.loaded(floor) || !terrain.loaded(at.above())
                    || !terrain.water(at) || !terrain.water(at.above())
                    || !world.getBlockState(at).canBeReplaced() || !world.getBlockState(at.above()).canBeReplaced()
                    || !world.getBlockState(floor).isFaceSturdy(world,floor,Direction.UP)
                    || world.getBlockEntity(floor)!=null)continue;
            Vec3 centre=new Vec3(at.getX()+.5,at.getY()+.08,at.getZ()+.5);
            if(!terrain.clear(player.position(),centre))continue;
            Vec3 hitPoint=new Vec3(at.getX()+.5,at.getY()-.001,at.getZ()+.5);
            if(player.getEyePosition().distanceTo(hitPoint)>player.blockInteractionRange()-.25)continue;
            BlockHitResult hit=world.clip(new ClipContext(player.getEyePosition(),hitPoint,
                    ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,player));
            if(!hit.getBlockPos().equals(floor) || hit.getDirection()!=Direction.UP)continue;
            if(originalSlot<0)originalSlot=player.getInventory().getSelectedSlot();
            if(!mod.getSlotHandler().forceEquipItem(new Item[]{door},false))return false;
            equippedSlot=player.getInventory().getSelectedSlot();
            if(!(player.getMainHandItem().getItem() instanceof BlockItem item) || !(item.getBlock() instanceof DoorBlock))return false;
            Vec3 look=hit.getLocation().subtract(player.getEyePosition());
            yaw=(float)Math.toDegrees(Math.atan2(-look.x,look.z)); pitch=(float)-Math.toDegrees(Math.atan2(look.y,look.horizontalDistance()));
            applyLook(mod);
            Minecraft.getInstance().gameMode.useItemOn(player,InteractionHand.MAIN_HAND,hit);
            attemptedDoors.add(at.immutable()); pendingDoor=at.immutable(); doorTick=player.tickCount;
            airSearch=null; airTick=-100; message="Placing a carried door; waiting for real air/oxygen confirmation.";
            return true;
        }
        return false;
    }
    private Outcome finish(Outcome outcome,String text) { message=text; return outcome; }
    private Outcome fail(String text) { return finish(Outcome.FAILED,text); }
    public void release(AltoClef mod) { if(current==this)releaseAll(mod); }
    public static void prepareFrame(AltoClef mod,TaskChain chain) { if(current!=null && chain!=mod.getUserTaskChain())releaseAll(mod); }
    public static void endTick(AltoClef mod) {
        if(current==null)return;
        if(!mod.getTaskRunner().isActive() || !isActive() || current.requestTick!=current.player.tickCount) { releaseAll(mod);return; }
        applyInputs(mod);applyLook(mod);
    }
    public static void applyInputs(AltoClef mod) {
        if(!ownsSwimming(mod))return;
        var c=current;
        for(Input key:KEYS) {
            boolean pressed=key==Input.MOVE_FORWARD&&c.forward || key==Input.JUMP&&c.jump || key==Input.SNEAK&&c.sneak || key==Input.SPRINT&&c.sprint;
            if(pressed)mod.getInputControls().hold(key);else mod.getInputControls().release(key);
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(key,pressed);
        }
        c.input.intent(c.forward,c.jump,c.sneak,c.sprint); c.player.input=c.input;
    }
    public static void applyLook(AltoClef mod) {
        if(ownsSwimming(mod)) { current.player.setYRot(current.yaw);current.player.setYHeadRot(current.yaw);current.player.setXRot(current.pitch); }
    }
    public static void releaseAll(AltoClef mod) {
        if(current==null)return;
        var c=current; current=null;
        if(mod.getPlayer()==c.player) {
            if(c.player.input==c.input)c.player.input=new KeyboardInput(Minecraft.getInstance().options);
            if(c.originalSlot>=0 && c.player.getInventory().getSelectedSlot()==c.equippedSlot)c.player.getInventory().setSelectedSlot(c.originalSlot);
        }
        for(Input key:KEYS) { mod.getInputControls().release(key);mod.getClientBaritone().getInputOverrideHandler().setInputForceState(key,false); }
    }
    public static LocalPlayer getRenderPlayer() { return isActive()?current.player:null; }
    public static List<Vec3> getRenderPath() { return isActive()?(current.refilling?current.escape:current.route):List.of(); }
    public static baritone.api.pathing.goals.Goal getRenderGoal() { return isActive()?new baritone.api.pathing.goals.GoalBlock(current.goal):null; }
    public static float getRenderYaw(Entity e,float t,float fallback) {
        return isActive()&&e==current.player?SpearHelper.interpolateYaw(current.previousYaw,current.yaw,t):fallback;
    }
    public static float getRenderPitch(Entity e,float t,float fallback) {
        return isActive()&&e==current.player?Mth.lerp(Mth.clamp(t,0,1),current.previousPitch,current.pitch):fallback;
    }
}
