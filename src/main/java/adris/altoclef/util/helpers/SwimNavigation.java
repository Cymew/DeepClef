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
    private final boolean surface, ascend, shoreExit;
    private final SwimInput input=new SwimInput();
    private final SwimJourney journey=new SwimJourney();
    private SwimRoutePlanner routeSearch, airSearch, surfaceSearch, diveSearch;
    private List<Vec3> surfaceEscape=List.of();
    private int surfaceSearchTick=-100, diveTick=-100;
    private List<Vec3> route=List.of(), escape=List.of();
    private int requestTick, lastTick=-1, routeTick=-100, airTick=-100, stalled, arrivalSamples, refugeLoops;
    private int doorTick=-100, originalSlot=-1, equippedSlot=-1;
    private int refugeTick=-1000, dryProbeTick=-100, verticalOffset, verticalTick=-100;
    private boolean nearbyWater=true, verticalDone;
    private BlockPos verticalOrigin;
    private final List<Vec3> verticalRoute=new ArrayList<>();
    private BlockPos pendingDoor;
    private final Set<BlockPos> attemptedDoors=new HashSet<>();
    private Vec3 progressPosition, lastRefuge;
    private float yaw,pitch,previousYaw,previousPitch;
    private boolean forward,jump,sneak,sprint,refilling,aborting;
    private boolean compactPose;
    private String message="Planning loaded water.";
    public SwimNavigation(AltoClef mod, BlockPos goal, boolean surface, boolean ascend) {
        this(mod,goal,surface,ascend,false);
    }
    public SwimNavigation(AltoClef mod, BlockPos goal, boolean surface, boolean ascend, boolean shoreExit) {
        this.player=mod.getPlayer(); this.world=mod.level(); this.goal=goal.immutable(); this.surface=surface; this.ascend=ascend;
        this.shoreExit=shoreExit;
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
            releaseAll(mod); current=this; routeSearch=airSearch=surfaceSearch=diveSearch=null; route=escape=surfaceEscape=List.of(); routeTick=airTick=-100;verticalOrigin=null;
            mod.getClientBaritone().getCustomGoalProcess().onLostControl();
            mod.getClientBaritone().getPathingBehavior().forceCancel();
        }
        requestTick=player.tickCount;
        if(lastTick==requestTick)return Outcome.RUNNING;
        lastTick=requestTick; previousYaw=yaw; previousPitch=pitch;
        forward=jump=sneak=sprint=false;
        if(compactPose!=player.isSwimming()) {
            compactPose=player.isSwimming();routeSearch=airSearch=surfaceSearch=diveSearch=null;routeTick=airTick=-100;verticalOrigin=null;
        }
        mod.getClientBaritone().getPathingBehavior().requestPause();
        SwimTerrain terrain=new SwimTerrain(mod);
        Vec3 pos=player.position();
        var here=terrain.kind(player.blockPosition());
        boolean bubble=here==SwimRoutePlanner.Kind.UP || here==SwimRoutePlanner.Kind.DOWN;
        // Vanilla replenishes air at the eyes, not merely when the feet touch a column.
        boolean eyesInBubble=world.getBlockState(BlockPos.containing(player.getEyePosition())).is(Blocks.BUBBLE_COLUMN);
        boolean breathing=!player.isUnderWater() || eyesInBubble || protectedAir();
        if(!player.isInWater() && player.onGround() && !refilling && !ascend && !reached(pos)) {
            if(shouldHandOff(shoreExit,here))return finish(Outcome.SHORE,"已游到可站立岸边，交回陆路寻路。");
            if(requestTick-dryProbeTick>=20) {nearbyWater=terrain.hasNearbyWater(player.blockPosition());dryProbeTick=requestTick;}
            if(!nearbyWater && here!=SwimRoutePlanner.Kind.AIR)return fail("已远离可接入水体，原目标尚未到达；陆路请用 goto。");
        }
        // Independent slices: a difficult air search must not starve the travel route.
        long airDeadline=System.nanoTime()+1_000_000;
        probeVertical(terrain,pos,airDeadline);
        if(airSearch==null && (escape.isEmpty() || requestTick-airTick>=20)) {
            airSearch=new SwimRoutePlanner(terrain,player.blockPosition(),goal,true,false,false); airTick=requestTick;
        }
        if(airSearch!=null) {
            airSearch.step(40,System.nanoTime()+500_000);
            if(airSearch.done()) {
                if(airSearch.reached() && joinable(terrain,pos,airSearch.path())
                        && (escape.isEmpty() || remaining(pos,airSearch.path())<remaining(pos,escape)))escape=airSearch.path();
                else if(!validEnd(terrain,escape))escape=List.of();
                airSearch=null;
            }
        }
        if(!validEnd(terrain,escape))escape=List.of();
        double escapeLength=remaining(pos,escape);
        double speed=player.getFoodData().getFoodLevel()>6 && !player.hasEffect(MobEffects.BLINDNESS) ? .15 : .06;
        double horizontal=Math.hypot(pos.x-goal.getX()-.5,pos.z-goal.getZ()-.5);
        if(!surface && !ascend && journey.considerSurface(pos.distanceTo(Vec3.atCenterOf(goal)),horizontal,
                player.getAirSupply(),speed,protectedAir()))resetTravelRoute();
        BlockPos surfaceCell=surfaceCell(terrain,player.blockPosition());
        boolean physicalSurface=breathing && surfaceCell!=null;
        if(surfaceCell!=null && terrain.clear(pos,terrain.point(surfaceCell))) {
            List<Vec3> localAir=List.of(terrain.point(surfaceCell));
            if(remaining(pos,localAir)<escapeLength) {escape=localAir;escapeLength=remaining(pos,localAir);}
        }
        if(journey.observeSurface(physicalSurface)) {
            resetTravelRoute();stalled=0;progressPosition=pos;
            if(player.getAirSupply()<player.getMaxAirSupply()-4 && !protectedAir())refilling=true;
        }
        // Small surface bobbing uses the normal forward route plus jump, not a retreat to an old refuge.
        boolean surfaceClimb=surfaceTravel() && !breathing && surfaceCell==null;
        refilling=shouldRefill(refilling,ascend,breathing,protectedAir(),here,player.getAirSupply(),player.getMaxAirSupply(),escapeLength,speed);
        if(refilling && journey.phase()==SwimJourney.Phase.DIVING) {journey.requestSurface();resetTravelRoute();}
        if(refilling && breathing && (!ascend || !player.isUnderWater() || eyesInBubble)
                && (!surface || here!=SwimRoutePlanner.Kind.WATER)) {
            message="Refilling air before continuing.";
            if(player.getAirSupply()>=player.getMaxAirSupply()-4 || protectedAir()) {
                if(ascend)return finish(Outcome.ARRIVED,"已抵达可呼吸位置并补充氧气。");
                if(lastRefuge!=null && pos.distanceTo(lastRefuge)<3 && requestTick-refugeTick>100)refugeLoops++; else refugeLoops=0;
                lastRefuge=pos;refugeTick=requestTick;
                if(aborting || refugeLoops>=1) {
                    // Try a different travel strategy before interpreting repeated oxygen retreats as failure.
                    if(!surface && !ascend && journey.phase()==SwimJourney.Phase.DIRECT) {
                        journey.requestSurface();resetTravelRoute();aborting=false;refugeLoops=0;
                    } else if(aborting || refugeLoops>=2) {
                        return fail("已补氧并尝试水面接近，但剩余路线仍无法推进；原目标尚未到达。");
                    }
                }
                refilling=false; pendingDoor=null; routeSearch=null; routeTick=-100; stalled=0;progressPosition=pos;
            } else {
                // Let a column move vertically while staying inside it; never fight it with repeated routes.
                jump=here==SwimRoutePlanner.Kind.SURFACE && !bubble && !player.onGround();
                sneak=here==SwimRoutePlanner.Kind.DOWN || magmaBelow();
                Vec3 centre=new Vec3(Math.floor(pos.x)+.5,pos.y,Math.floor(pos.z)+.5);
                if(Math.hypot(pos.x-centre.x,pos.z-centre.z)>.17)steer(terrain,centre,false);
                if(here==SwimRoutePlanner.Kind.AIR)jump=false;
                if(here==SwimRoutePlanner.Kind.DOWN || magmaBelow())sneak=true;
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
        if(!refilling && journey.phase()==SwimJourney.Phase.ASCENDING) {
            message="Ascending for a surface approach to the underwater destination.";
            if(!validSurfaceEnd(terrain,surfaceEscape))surfaceEscape=List.of();
            if(surfaceEscape.isEmpty() && surfaceSearch==null && requestTick-surfaceSearchTick>=20) {
                surfaceSearch=new SwimRoutePlanner(terrain,player.blockPosition(),goal,true,false,false,true);
                surfaceSearchTick=requestTick;
            }
            if(surfaceSearch!=null) {
                surfaceSearch.step(100,System.nanoTime()+2_000_000);
                if(surfaceSearch.done()) {
                    if(surfaceSearch.reached() && joinable(terrain,pos,surfaceSearch.path()))surfaceEscape=surfaceSearch.path();
                    surfaceSearch=null;
                }
            }
            if(!follow(terrain,surfaceEscape)) {
                surfaceEscape=List.of();
                swimSearchStep(terrain);
            }
        } else if(refilling || surfaceClimb) {
            message=ascend?"Finding breathable water/air.":"Returning to air reserve.";
            if(!follow(terrain,escape)) {
                escape=List.of();
                swimSearchStep(terrain);
            }
        } else {
            long travelDeadline=System.nanoTime()+2_000_000;
            // Only admit a final dive after the complete loaded route and return-air budget are known.
            if(journey.phase()==SwimJourney.Phase.SURFACE && horizontal<=8 && breathing) {
                if(diveSearch==null && surfaceCell!=null && requestTick-diveTick>=20) {
                    diveSearch=new SwimRoutePlanner(terrain,surfaceCell,goal,false,false,false);diveTick=requestTick;
                }
                if(diveSearch!=null) {
                    diveSearch.step(100,Math.min(travelDeadline,System.nanoTime()+1_000_000));
                    if(diveSearch.done()) {
                        List<Vec3> candidate=diveSearch.path();
                        double length=SwimRoutePlanner.length(pos,candidate);
                        if(joinable(terrain,pos,candidate) && journey.beginDive(diveSearch.reached(),length,
                                player.getAirSupply(),speed,protectedAir())) {
                            route=candidate;routeSearch=null;routeTick=requestTick;
                            ArrayList<Vec3> back=new ArrayList<>(candidate);Collections.reverse(back);escape=List.copyOf(back);
                        } else if(horizontal<1 && player.getAirSupply()>=player.getMaxAirSupply()-4) {
                            return fail("已沿水面抵达目标附近并补满氧，但尚无氧气足够且可验证的下潜往返路线；需要中继空气或水下呼吸。");
                        } else if(diveSearch.reached() && SwimJourney.fitsDive(length,player.getMaxAirSupply(),speed)) {
                            refilling=true;
                        }
                        diveSearch=null;
                    }
                }
            }
            if(routeSearch==null && (route.isEmpty() || requestTick-routeTick>=35 || remaining(pos,route)<10)) {
                BlockPos start=surfaceTravel() && surfaceCell!=null?surfaceCell:player.blockPosition();
                routeSearch=new SwimRoutePlanner(terrain,start,goal,false,surfaceTravel(),surfaceTravel()); routeTick=requestTick;
            }
            if(routeSearch!=null) {
                routeSearch.step(100,travelDeadline);
                if(routeSearch.done()) {
                    List<Vec3> candidate=routeSearch.path();
                    if(candidate.size()>1 && joinable(terrain,pos,candidate))route=candidate;
                    routeSearch=null;
                }
            }
            message=surfaceTravel()?"Swimming along the surface before the final approach.":"Following 3D water route.";
            if(!follow(terrain,route)) {
                message="Planning the next loaded water corridor.";
                jump=surfaceTravel() && !breathing;
                // Enter the native swimming pose in verified open water while the longer search runs.
                if(!player.isSwimming() && player.isUnderWater() && !surfaceTravel() && breathingOrReserve()) {
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
    private boolean surfaceTravel() {return surface || journey.phase()==SwimJourney.Phase.SURFACE;}
    private static BlockPos surfaceCell(SwimTerrain t,BlockPos feet) {
        for(BlockPos p:List.of(feet,feet.above(),feet.below()))if(t.kind(p)==SwimRoutePlanner.Kind.SURFACE)return p;
        return null;
    }
    private void resetTravelRoute() {route=List.of();routeSearch=diveSearch=null;routeTick=diveTick=-100;}
    private static boolean validSurfaceEnd(SwimTerrain t,List<Vec3> path) {
        return !path.isEmpty() && t.kind(BlockPos.containing(path.getLast()))==SwimRoutePlanner.Kind.SURFACE;
    }
    static boolean atGoal(Vec3 p,BlockPos goal,boolean surface) {
        return Math.hypot(p.x-goal.getX()-.5,p.z-goal.getZ()-.5)<.8
                && (surface || Math.abs(p.y-goal.getY())<.85);
    }
    private boolean breathingOrReserve() { return protectedAir() || player.getAirSupply()>200; }
    static boolean shouldHandOff(boolean automatic,SwimRoutePlanner.Kind here) {
        return automatic && here==SwimRoutePlanner.Kind.SHORE;
    }
    static boolean shouldRefill(boolean active,boolean ascend,boolean breathing,boolean protectedAir,
                                SwimRoutePlanner.Kind here,int air,int maximum,double distance,double speed) {
        if(active || ascend)return true;
        if(protectedAir)return false;
        if(breathing)return (here==SwimRoutePlanner.Kind.AIR || here==SwimRoutePlanner.Kind.UP || here==SwimRoutePlanner.Kind.DOWN)
                && air<maximum*.85;
        return SwimRoutePlanner.needsAir(air,maximum,distance,speed);
    }
    /** Keep the vertical probe cursor across ticks, including deep water with an expensive collision query. */
    private void probeVertical(SwimTerrain terrain,Vec3 pos,long deadline) {
        if(verticalOrigin==null || verticalOrigin.distSqr(player.blockPosition())>9
                || verticalDone && requestTick-verticalTick>=20) {
            verticalOrigin=player.blockPosition();verticalOffset=0;verticalDone=false;verticalRoute.clear();verticalTick=requestTick;
        }
        while(!verticalDone && System.nanoTime()<deadline) {
            BlockPos cell=ascentProbeCell(verticalOrigin,goal,verticalOffset++);
            var kind=terrain.kind(cell);Vec3 point=terrain.point(cell);
            Vec3 previous=verticalRoute.isEmpty()?pos:verticalRoute.getLast();
            if(kind==SwimRoutePlanner.Kind.BLOCKED || !terrain.clear(previous,point)) {verticalDone=true;break;}
            verticalRoute.add(point);
            if(kind.breath()) {
                if(joinable(terrain,pos,verticalRoute) && (escape.isEmpty() || remaining(pos,verticalRoute)<remaining(pos,escape)))escape=List.copyOf(verticalRoute);
                if(kind==SwimRoutePlanner.Kind.SURFACE) {surfaceEscape=List.copyOf(verticalRoute);verticalDone=true;}
            }
            if(verticalOffset>48)verticalDone=true;
        }
    }
    private static boolean validEnd(SwimTerrain t,List<Vec3> route) {
        return !route.isEmpty() && t.kind(BlockPos.containing(route.getLast())).breath();
    }
    static BlockPos ascentProbeCell(BlockPos origin,BlockPos goal,int rise) {
        Vec3 heading=new Vec3(goal.getX()-origin.getX(),0,goal.getZ()-origin.getZ()).normalize();
        return origin.offset((int)Math.round(heading.x*rise),rise,(int)Math.round(heading.z*rise));
    }
    /** Keep moving in a verified corridor while the global, resumable search works around the roof. */
    private boolean swimSearchStep(SwimTerrain terrain) {
        Vec3 pos=player.position();
        double heading=Math.atan2(goal.getZ()+.5-pos.z,goal.getX()+.5-pos.x);
        for(double dy:new double[]{.8,0})for(int offset:new int[]{0,1,-1,2,-2,3,-3,4}) {
            double angle=heading+offset*Math.PI/4;
            Vec3 aim=pos.add(Math.cos(angle)*1.2,dy,Math.sin(angle)*1.2);
            if(terrain.clear(pos,aim)) {steer(terrain,aim,true);return true;}
        }
        message="Searching for a traversable route around the submerged obstacle.";
        return false;
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
            jump=surfaceTravel() && player.isUnderWater(); return true;
        }
        steer(terrain,aim,true); return true;
    }
    private void steer(SwimTerrain terrain,Vec3 aim,boolean allowSprint) {
        Vec3 delta=aim.subtract(player.position());
        // A vertical waypoint alone cannot produce W thrust: find a checked diagonal heading toward it.
        if(player.isInWater() && delta.y>.25 && delta.horizontalDistance()<.3) {
            double heading=Math.atan2(goal.getZ()+.5-player.getZ(),goal.getX()+.5-player.getX());
            for(int offset:new int[]{0,1,-1,2,-2,3,-3,4}) {
                double angle=heading+offset*Math.PI/4;
                Vec3 diagonal=player.position().add(Math.cos(angle)*.8,Math.min(1,delta.y),Math.sin(angle)*.8);
                if(terrain.clear(player.position(),diagonal)) {delta=diagonal.subtract(player.position());break;}
            }
        }
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
        jump=!player.isInWater() && delta.y>.25;
        sneak=shouldDive(player.isInWater(),surfaceTravel(),delta.y) || magmaBelow();
        // Check actual inertial heading as well as the chosen path chord.
        Vec3 velocity=predictionVelocity(player.getDeltaMovement(),player.onGround());
        Vec3 predicted=player.position().add(velocity.scale(3));
        if(velocity.length()>.06 && !terrain.clear(player.position(),predicted)) { forward=false; sprint=false; }
    }
    static Vec3 predictionVelocity(Vec3 velocity,boolean grounded) {
        // Vanilla retains a small gravity component on the floor. It is not an impending floor collision.
        return grounded && velocity.y<0 ? new Vec3(velocity.x,0,velocity.z) : velocity;
    }
    static boolean shouldDive(boolean inWater,boolean surface,double heightDelta) {
        // Crouching on a bank prevents stepping off into the water in the first place.
        return inWater && !surface && heightDelta<-.45;
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
    public static List<Vec3> getRenderPath() {
        return isActive()?(current.refilling?current.escape:current.journey.phase()==SwimJourney.Phase.ASCENDING?current.surfaceEscape:current.route):List.of();
    }
    public static baritone.api.pathing.goals.Goal getRenderGoal() { return isActive()?new baritone.api.pathing.goals.GoalBlock(current.goal):null; }
    public static float getRenderYaw(Entity e,float t,float fallback) {
        return isActive()&&e==current.player?SpearHelper.interpolateYaw(current.previousYaw,current.yaw,t):fallback;
    }
    public static float getRenderPitch(Entity e,float t,float fallback) {
        return isActive()&&e==current.player?Mth.lerp(Mth.clamp(t,0,1),current.previousPitch,current.pitch):fallback;
    }
}
