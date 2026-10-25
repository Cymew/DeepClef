package adris.altoclef.util.helpers;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.function.LongSupplier;

/** A rolling, same-level ice-road graph. World observations are only made during update. */
public final class IceRoadNavigator {
    public interface Terrain {
        enum Cell { ICE, AIR, BLOCKED, UNKNOWN }
        Cell cell(int x, int z);
        boolean traversable(Vec3 from, Vec3 to);
    }
    public record Guidance(Vec3 target, boolean terminal, boolean planning, boolean blocked, double speedLimit) { }
    private enum Phase { IDLE, DIRECT, FLOOD, SEARCH, CENTRE, SIMPLIFY, CURVE, JOIN, VALIDATE }
    private static final int LIMIT = 32768, RADIUS = 512, WORK_LIMIT = 1024;
    private static final double MARGIN = 1.6875;
    private final LongSupplier clock;
    private static final int[][] DIRS = {{1,0},{0,1},{-1,0},{0,-1},{1,1},{-1,1},{-1,-1},{1,-1}};
    private static final class Node {
        final int x,z; final boolean gap;
        double cost = Double.POSITIVE_INFINITY, clearance = Double.NaN;
        Node parent;
        Vec3 recoveryPoint;
        Node(int x,int z,boolean gap) { this.x=x; this.z=z; this.gap=gap; }
        Vec3 point(double y) { return recoveryPoint==null ? new Vec3(x+.5,y,z+.5) : recoveryPoint; }
    }
    private record Open(Node node,double cost,double score,long order) { }
    private record Edge(long from,long to) { }
    private Goal goal;
    private Phase phase = Phase.IDLE;
    private Vec3 anchor;
    private Vec3 observedPosition,observedVelocity;
    private final ArrayDeque<Long> joinCells=new ArrayDeque<>();
    private int observedTick;
    private boolean retryFromObserved,joinedCandidate;
    private int startedTick, nextPlanTick;
    private Map<Long,Node> building = new HashMap<>(), activeRoad = Map.of();
    private final Map<Long,Terrain.Cell> cells = new HashMap<>();
    private final ArrayDeque<Node> flood = new ArrayDeque<>();
    private final Set<Edge> gapEdges = new HashSet<>();
    private Node floodNode, best, finish, seedNode;
    private int floodDirection;
    private boolean gapMode, unknown, reached;
    private long order;
    private PriorityQueue<Open> open;
    private List<Vec3> raw = List.of(), centred = new ArrayList<>(), candidate = new ArrayList<>();
    private List<Vec3> route = List.of();
    private boolean routeReached;
    private Vec3 publishedAt;
    private int cursor, curveIndex, validateIndex;
    private Vec3 validateFrom;
    private boolean rawFallback;
    private double frameMargin;
    private double pursuitLimit=Double.POSITIVE_INFINITY;
    private boolean straightRoute;
    private boolean sharpRoute,sharpBuilding, recoveryRoute;
    private int directX,directZ,directDistance;
    private Node directLast;
    private Node directGoal;
    private int directGoalDistance;

    public IceRoadNavigator() { this(System::nanoTime); }
    IceRoadNavigator(LongSupplier clock) { this.clock=Objects.requireNonNull(clock); }

    public List<Vec3> path() { return route; }
    public boolean isStraightRoute() { return straightRoute; }
    public boolean isGentleRoute() { return !sharpRoute && !recoveryRoute; }
    public boolean reachesGoal() { return routeReached; }
    public void reset() { retry(); goal = null; }
    public void retry() {
        BoatIceCruisePlanner.reset(this);
        phase=Phase.IDLE; route=List.of(); activeRoad=Map.of(); building=new HashMap<>();
        cells.clear(); flood.clear(); gapEdges.clear(); publishedAt=null; nextPlanTick=Integer.MIN_VALUE;
        joinCells.clear(); retryFromObserved=joinedCandidate=false;
    }

    public Guidance update(Vec3 position, Vec3 velocity, Goal destination, Terrain terrain, int tick, long maxNanos) {
        pursuitLimit=Double.POSITIVE_INFINITY;
        if (!finite(position) || !finite(velocity) || destination==null || terrain==null) {
            reset(); return new Guidance(null,false,false,true,0);
        }
        if (!Objects.equals(goal,destination)) { retry(); goal=destination; }
        observedPosition=position; observedVelocity=velocity; observedTick=tick;
        if(!route.isEmpty() && !inside(position,activeRoad) && phase==Phase.IDLE) {
            begin(position,terrain,tick);
        }
        boolean extend = route.isEmpty() || !routeReached && (remaining(position)<320
                || publishedAt!=null && publishedAt.distanceToSqr(position)>4096);
        if (phase==Phase.IDLE && tick>=nextPlanTick && (extend || retryFromObserved)) begin(position,terrain,tick);
        long started=clock.getAsLong();
        int work=0;
        while (phase!=Phase.IDLE && work++<WORK_LIMIT && clock.getAsLong()-started<maxNanos) {
            switch (phase) {
                case DIRECT -> { directOne(terrain); work+=24; }
                case FLOOD -> floodOne(terrain,tick);
                case SEARCH -> searchOne(terrain);
                case CENTRE -> centreOne();
                case SIMPLIFY -> simplifyOne();
                case CURVE -> curveOne();
                case JOIN -> joinOne(terrain);
                case VALIDATE -> validateOne(terrain,tick);
                default -> { }
            }
        }
        if (!route.isEmpty()) {
            double intended=Math.min(MARGIN,clearance(project(position).point));
            double actual=clearance(position);
            // One recovery allowance for the whole forecast, not a margin
            // which silently erodes at each predicted step.
            double limit=straightRoute || sharpRoute?MARGIN-.04:.8075;
            frameMargin=Math.max(0,Math.min(limit,Math.min(intended-.04,actual-.25)));
        }
        Guidance result=preview(position,velocity);
        if (result.target()!=null && !terrain.traversable(position,result.target())) {
            // A long pursuit chord may clip a corner even though each piece of
            // the published road is valid. Shorten the tangent before treating
            // that as a changed road and clearing the live steering target.
            if (!result.terminal()) {
                pursuitLimit=Math.min(12,position.distanceTo(result.target()))*.5;
                for (int attempt=0;attempt<4;attempt++,pursuitLimit*=.5) {
                    Guidance local=preview(position,velocity);
                    if (local.target()!=null && terrain.traversable(position,local.target())) return local;
                }
                pursuitLimit=Double.POSITIVE_INFINITY;
            }
            // A changed block cannot be hidden by the cached graph.
            if (phase==Phase.IDLE) { nextPlanTick=tick; begin(position,terrain,tick); }
            return new Guidance(null,false,true,false,0);
        }
        if (result.target()==null) return new Guidance(null,false,phase!=Phase.IDLE,phase==Phase.IDLE,0);
        return new Guidance(result.target(),result.terminal(),phase!=Phase.IDLE,false,result.speedLimit());
    }

    private void begin(Vec3 position,Terrain terrain,int tick) {
        retryFromObserved=joinedCandidate=false; joinCells.clear();
        anchor=position; startedTick=tick; building=new HashMap<>(); cells.clear(); flood.clear(); gapEdges.clear();
        floodNode=null; floodDirection=0; gapMode=false; unknown=false; reached=false; finish=null; best=null;
        int cx=(int)Math.floor(position.x), cz=(int)Math.floor(position.z);
        Node seed=null; double distance=Double.POSITIVE_INFINITY;
        for (int dx=-2;dx<=2;dx++) for (int dz=-2;dz<=2;dz++) {
            int x=cx+dx,z=cz+dz;
            if (cell(terrain,x,z)!=Terrain.Cell.ICE) continue;
            Vec3 point=new Vec3(x+.5,position.y,z+.5);
            double d=point.distanceToSqr(position);
            if (d<distance && terrain.traversable(position,point)) { seed=new Node(x,z,false); distance=d; }
        }
        if (seed==null) { phase=Phase.IDLE; nextPlanTick=tick+20; return; }
        building.put(key(seed.x,seed.z),seed); flood.add(seed); best=seed; seedNode=seed;
        Terrain.Cell seatedCell=cell(terrain,cx,cz);
        if (seatedCell!=Terrain.Cell.ICE && seatedCell!=Terrain.Cell.UNKNOWN) {
            // Only the already occupied cell is exempted for an inward recovery.
            // Its vertex is the actual boat position, not the cell centre, which
            // could first move the hull farther onto stone or over open air.
            // The seed edge was checked above against native support and drag;
            // this virtual vertex never supplies ice contact or starts a flood.
            Node seated=new Node(cx,cz,true); building.put(key(cx,cz),seated);
            seated.recoveryPoint=position;
            linkGap(seated,seed); seedNode=seated;
        }
        phase=Phase.FLOOD;
        double dx=0,dz=0;
        if(goal instanceof GoalXZ g) { dx=g.getX()+.5-position.x; dz=g.getZ()+.5-position.z; }
        else if(goal instanceof GoalBlock g) { dx=g.x+.5-position.x; dz=g.z+.5-position.z; }
        if(Math.max(Math.abs(dx),Math.abs(dz))>64 && Math.min(Math.abs(dx),Math.abs(dz))<1) {
            directX=Math.abs(dx)>Math.abs(dz)?(dx>0?1:-1):0;
            directZ=directX==0?(dz>0?1:-1):0;
            directDistance=0; directLast=seedNode; directGoal=null; phase=Phase.DIRECT;
        }
    }

    /** Scan loaded straight road cross-sections before doing a general flood.
     * This gives a highway hundreds of verified blocks without visiting every
     * outward graph edge. Turns/holes simply return to the general road search. */
    private void directOne(Terrain terrain) {
        int cx=(int)Math.floor(anchor.x)+directX*directDistance;
        int cz=(int)Math.floor(anchor.z)+directZ*directDistance;
        ArrayList<Node> row=new ArrayList<>();
        for(int side=-12;side<=12;side++) {
            int x=cx+directZ*side,z=cz-directX*side;
            if(cell(terrain,x,z)!=Terrain.Cell.ICE) continue;
            Node node=building.computeIfAbsent(key(x,z),k->new Node(x,z,false)); row.add(node);
        }
        Node centre=building.get(key(cx,cz));
        if(centre==null || row.isEmpty() || directDistance>=RADIUS) {
            if(directGoal!=null) { prepare(directGoal,true); return; }
            if(directDistance>=96) { prepare(directLast,false); return; }
            flood.clear(); for(Node n:building.values()) if(!n.gap) flood.add(n);
            for(Node n:building.values()) n.parent=null;
            phase=Phase.FLOOD; return;
        }
        if(directDistance>0) centre.parent=directLast;
        directLast=centre;
        if(directGoal==null && goal.isInGoal(BlockPos.containing(centre.point(anchor.y)))) {
            directGoal=centre; directGoalDistance=directDistance;
        }
        // Cache the real road beyond a goal too. Otherwise the end of the
        // planning data becomes a fictitious road edge under the arriving hull.
        if(directGoal!=null && directDistance>=directGoalDistance+4) { prepare(directGoal,true); return; }
        directDistance++;
    }

    private Terrain.Cell cell(Terrain terrain,int x,int z) {
        long key=key(x,z); Terrain.Cell old=cells.get(key);
        if (old!=null) return old;
        Terrain.Cell found=terrain.cell(x,z); cells.put(key,found);
        if (found==Terrain.Cell.UNKNOWN) unknown=true;
        return found;
    }

    private void floodOne(Terrain terrain,int tick) {
        if (floodNode==null) {
            // Large open sheets publish a long checked prefix rather than scanning
            // every loaded chunk before moving. Narrow roads normally flood in full.
            if (flood.isEmpty() || building.size()>=LIMIT
                    || building.size()>=4096 && tick-startedTick>=10 && progress(best)>128) {
                if (!gapMode && finish==null && (progress(best)<96 || !unknown)) {
                    gapMode=true;
                    for (Node node:building.values()) if (!node.gap) flood.add(node);
                    if (!flood.isEmpty()) return;
                }
                beginSearch(); return;
            }
            floodNode=flood.removeFirst(); floodDirection=0;
        }
        if (floodDirection>=DIRS.length) { floodNode=null; return; }
        int[] d=DIRS[floodDirection++]; int x=floodNode.x+d[0], z=floodNode.z+d[1];
        if (Math.abs(x-anchor.x)>RADIUS || Math.abs(z-anchor.z)>RADIUS) return;
        Terrain.Cell next=cell(terrain,x,z);
        if (next==Terrain.Cell.ICE && (d[0]==0 || d[1]==0
                || cell(terrain,floodNode.x+d[0],floodNode.z)==Terrain.Cell.ICE
                && cell(terrain,floodNode.x,floodNode.z+d[1])==Terrain.Cell.ICE)) {
            add(x,z,false); return;
        }
        if (!gapMode || next!=Terrain.Cell.AIR) return;
        int beyondX=x+d[0], beyondZ=z+d[1];
        if (Math.abs(beyondX-anchor.x)>RADIUS || Math.abs(beyondZ-anchor.z)>RADIUS
                || cell(terrain,beyondX,beyondZ)!=Terrain.Cell.ICE) return;
        Vec3 from=floodNode.point(anchor.y), to=new Vec3(beyondX+.5,anchor.y,beyondZ+.5);
        if (!terrain.traversable(from,to)) return;
        Node middle=add(x,z,true), end=add(beyondX,beyondZ,false);
        if (middle==null || end==null) return;
        linkGap(floodNode,middle); linkGap(middle,end);
    }
    private void linkGap(Node a,Node b) {
        gapEdges.add(new Edge(key(a.x,a.z),key(b.x,b.z)));
        gapEdges.add(new Edge(key(b.x,b.z),key(a.x,a.z)));
    }
    private Node add(int x,int z,boolean gap) {
        long k=key(x,z); Node node=building.get(k);
        if (node==null && building.size()<LIMIT) {
            node=new Node(x,z,gap); building.put(k,node); if (!gap) flood.addLast(node);
            if (best==null || estimate(node.point(anchor.y))<estimate(best.point(anchor.y))) best=node;
            if (goal.isInGoal(BlockPos.containing(node.point(anchor.y)))) finish=node;
        }
        return node;
    }
    private double progress(Node node) { return node==null ? 0 : node.point(anchor.y).distanceTo(anchor); }
    private void beginSearch() {
        open=new PriorityQueue<>(Comparator.comparingDouble(Open::score).thenComparingLong(Open::order));
        Node seed=seedNode;
        if (seed==null) { phase=Phase.IDLE; return; }
        seed.cost=0; open.add(new Open(seed,0,estimate(seed.point(anchor.y)),order++));
        best=seed; finish=null; phase=Phase.SEARCH;
    }
    private void searchOne(Terrain terrain) {
        if (open.isEmpty()) { prepare(best,false); return; }
        Open queued=open.poll(); Node node=queued.node();
        if (queued.cost()>node.cost+1e-7) return;
        Vec3 point=node.point(anchor.y);
        if (estimate(point)<estimate(best.point(anchor.y))) best=node;
        if (goal.isInGoal(BlockPos.containing(point))) { prepare(node,true); return; }
        for (int[] d:DIRS) {
            Node next=building.get(key(node.x+d[0],node.z+d[1]));
            if (next==null) continue;
            if (d[0]!=0 && d[1]!=0 && !(building.containsKey(key(node.x+d[0],node.z))
                    && building.containsKey(key(node.x,node.z+d[1])))
                    && !gapEdges.contains(new Edge(key(node.x,node.z),key(next.x,next.z)))) continue;
            if (Double.isNaN(next.clearance)) next.clearance=clearance(next.point(anchor.y),building);
            double penalty=1+4*Math.pow(Math.max(0,MARGIN-next.clearance),2);
            double turn=0;
            if (node.parent!=null) {
                double ax=node.x-node.parent.x,az=node.z-node.parent.z;
                double dot=(ax*d[0]+az*d[1])/(Math.hypot(ax,az)*Math.hypot(d[0],d[1]));
                turn=(1-Math.max(-1,Math.min(1,dot)))*.8;
            }
            double cost=node.cost+Math.hypot(d[0],d[1])*penalty+turn;
            if (cost>=next.cost-1e-7) continue;
            next.cost=cost; next.parent=node;
            open.add(new Open(next,cost,cost+estimate(next.point(anchor.y))*1.2,order++));
        }
    }
    private void prepare(Node end,boolean complete) {
        reached=complete; ArrayList<Vec3> points=new ArrayList<>();
        for (Node n=end;n!=null;n=n.parent) points.add(n.point(anchor.y));
        Collections.reverse(points); points.addFirst(anchor);
        raw=List.copyOf(points); centred=new ArrayList<>(); cursor=0; rawFallback=false; phase=Phase.CENTRE;
        List<Vec3> runs=compress(raw); int axes=0;
        for(int index=1;index<runs.size();index++) {
            Vec3 direction=runs.get(index).subtract(runs.get(index-1));
            if(Math.abs(direction.x)<.01 && Math.abs(direction.z)>=16) axes|=1;
            if(Math.abs(direction.z)<.01 && Math.abs(direction.x)>=16) axes|=2;
        }
        sharpBuilding=axes==3;
    }
    private void centreOne() {
        if (cursor>=raw.size()) {
            centred=compress(centred); candidate=new ArrayList<>(); candidate.add(centred.getFirst());
            if(sharpBuilding) { curveIndex=1; phase=Phase.CURVE; return; }
            cursor=0; phase=Phase.SIMPLIFY; return;
        }
        Vec3 point=raw.get(cursor);
        if (cursor>0 && cursor+1<raw.size()) {
            Vec3 before=point.subtract(raw.get(cursor-1)), after=raw.get(cursor+1).subtract(point);
            if (before.horizontalDistance()>1e-6 && after.horizontalDistance()>1e-6
                    && before.normalize().dot(after.normalize())>(sharpBuilding?.999:-.5)) {
                // Centre the corner vertices too. Moving only the straight
                // vertices of a grid arc creates alternating inner/outer teeth.
                Vec3 tangent=sharpBuilding?after:raw.get(Math.min(raw.size()-1,cursor+4))
                        .subtract(raw.get(Math.max(0,cursor-4)));
                Vec3 normal=new Vec3(-tangent.z,0,tangent.x).normalize();
                double left=extent(point,normal),right=extent(point,normal.scale(-1));
                Vec3 middle=point.add(normal.scale((left-right)*.5));
                if (clearance(middle,building)>=clearance(point,building)-1e-7) point=middle;
            }
        }
        centred.add(point); cursor++;
    }
    private void simplifyOne() {
        if(cursor>=centred.size()-1) {
            centred=List.copyOf(candidate); candidate=new ArrayList<>(); candidate.add(centred.getFirst());
            curveIndex=1; phase=Phase.CURVE; return;
        }
        Vec3 from=centred.get(cursor);
        int next=Math.min(centred.size()-1,cursor+12);
        for(;next>cursor+1;next--) {
            Vec3 to=centred.get(next);
            double margin=Math.max(0,Math.min(MARGIN,Math.min(clearance(from,building),clearance(to,building)))-.04);
            if(roadSweep(from,to,building,margin)) break;
        }
        candidate.add(centred.get(next)); cursor=next;
    }
    private double extent(Vec3 point,Vec3 direction) {
        double low=0;
        for (double d=.25;d<=16;d+=.25) {
            if (!inside(point.add(direction.scale(d)),building)) {
                double high=d;
                for(int sample=0;sample<8;sample++) {
                    double middle=(low+high)*.5;
                    if(inside(point.add(direction.scale(middle)),building)) low=middle; else high=middle;
                }
                return (low+high)*.5;
            }
            low=d;
        }
        return low;
    }
    private void curveOne() {
        if (curveIndex>=centred.size()-1) {
            if (centred.size()>1) candidate.add(centred.getLast());
            startValidation(); return;
        }
        Vec3 p=centred.get(curveIndex-1),corner=centred.get(curveIndex),n=centred.get(curveIndex+1);
        Vec3 incoming=corner.subtract(p),outgoing=n.subtract(corner);
        double a=incoming.horizontalDistance(),b=outgoing.horizontalDistance();
        double radius=Math.min(Math.min(a,b)*.45,Math.max(1,clearance(corner,building)*2));
        List<Vec3> rounded=null;
        for (int attempt=0;attempt<4 && radius>.15;attempt++,radius*=.5) {
            Vec3 start=corner.subtract(incoming.normalize().scale(radius));
            Vec3 end=corner.add(outgoing.normalize().scale(radius));
            ArrayList<Vec3> curve=new ArrayList<>(); curve.add(start);
            for (int sample=1;sample<=8;sample++) {
                double t=sample/8.0;
                curve.add(start.scale((1-t)*(1-t)).add(corner.scale(2*t*(1-t))).add(end.scale(t*t)));
            }
            boolean safe=true; Vec3 previous=start;
            double minimum=Math.min(MARGIN,Math.min(clearance(previous,building),clearance(end,building)));
            for (Vec3 point:curve) {
                if (!roadSweep(previous,point,building,Math.max(.01,minimum-.025))) { safe=false; break; }
                previous=point;
            }
            if (safe) { rounded=curve; break; }
        }
        if (rounded==null) candidate.add(corner); else candidate.addAll(rounded);
        curveIndex++;
    }
    private void startValidation() {
        if (route.size()>=2 && candidate.size()>=2 && observedPosition!=null) {
            // Searching can outlive dozens of physical ticks. Keep the old
            // forward target and join the new tail ahead of it, instead of
            // replacing the live course with a line from an obsolete anchor.
            Projection oldProjection=project(observedPosition);
            double keep=Math.min(24,Math.max(12,observedVelocity.horizontalDistance()*20));
            Vec3 forward=along(oldProjection,keep);
            if (forward.distanceToSqr(oldProjection.point)>.01) {
                Projection near=project(candidate,forward);
                Projection current=project(candidate,oldProjection.point);
                if (near.remaining<.25 || near.remaining>current.remaining+.25) {
                    // A candidate ending behind the preserved prefix must not
                    // turn that prefix into a U-turn. Keep driving the old
                    // checked road while the next search starts at a fresh pose.
                    boolean sameGoal=reached && routeReached
                            && candidate.getLast().distanceToSqr(route.getLast())<1e-8;
                    deferCandidate(!sameGoal); return;
                }
                double offset=near.point.distanceTo(forward);
                double joinAhead=Math.min(near.remaining,Math.max(12,Math.min(36,offset*1.5)));
                Vec3 end=along(candidate,near,joinAhead);
                Projection endProjection=project(candidate,end);
                ArrayList<Vec3> joined=new ArrayList<>();
                joined.add(oldProjection.point);
                double distance=0; Vec3 previous=oldProjection.point;
                for (int index=oldProjection.segment+1;index<route.size();index++) {
                    Vec3 point=route.get(index); double segment=previous.distanceTo(point);
                    if (distance+segment>=keep) break;
                    if (joined.getLast().distanceToSqr(point)>1e-8) joined.add(point);
                    distance+=segment; previous=point;
                }
                if (joined.getLast().distanceToSqr(forward)>1e-8) joined.add(forward);
                Vec3 incoming=joined.getLast().subtract(joined.get(joined.size()-2)).normalize();
                if (end.distanceToSqr(forward)<.0625 || end.subtract(forward).dot(incoming)<-.25) {
                    deferCandidate(true); return;
                }
                Vec3 tangent=Vec3.ZERO;
                for (int index=endProjection.segment+1;index<candidate.size();index++) {
                    tangent=candidate.get(index).subtract(end);
                    if (tangent.horizontalDistance()>1e-6) break;
                }
                if (tangent.horizontalDistance()<1e-6)
                    tangent=end.subtract(along(candidate,near,Math.max(0,joinAhead-1)));
                if (tangent.horizontalDistance()<1e-6) tangent=end.subtract(forward);
                double handle=Math.min(12,forward.distanceTo(end)*.35);
                Vec3 a=forward.add(incoming.scale(handle));
                Vec3 b=end.subtract(tangent.normalize().scale(handle));
                for (int sample=1;sample<=12;sample++) {
                    double t=sample/12.0,u=1-t;
                    Vec3 point=forward.scale(u*u*u).add(a.scale(3*u*u*t))
                            .add(b.scale(3*u*t*t)).add(end.scale(t*t*t));
                    // The road is exactly one layer. Bernstein weights can
                    // otherwise round a constant Y into a tiny fictitious step.
                    joined.add(new Vec3(point.x,forward.y,point.z));
                }
                int patchEnd=joined.size();
                for (int index=endProjection.segment+1;index<candidate.size();index++)
                    if (joined.getLast().distanceToSqr(candidate.get(index))>1e-8) joined.add(candidate.get(index));
                candidate=joined;
                joinedCandidate=true;
                anchor=observedPosition;
                rawFallback=true; // Falling back to the obsolete raw prefix would undo this join.
                LinkedHashSet<Long> patch=new LinkedHashSet<>();
                for (int index=1;index<patchEnd;index++) {
                    Vec3 from=candidate.get(index-1),to=candidate.get(index);
                    int count=Math.max(1,(int)Math.ceil(from.distanceTo(to)));
                    for (int sample=0;sample<=count;sample++) {
                        Vec3 point=from.lerp(to,(double)sample/count);
                        int x=(int)Math.floor(point.x),z=(int)Math.floor(point.z);
                        for (int dx=-3;dx<=3;dx++) for (int dz=-3;dz<=3;dz++) patch.add(key(x+dx,z+dz));
                    }
                }
                joinCells.clear(); joinCells.addAll(patch);
                phase=Phase.JOIN;
                return;
            }
        }
        validateIndex=1; validateFrom=candidate.getFirst(); phase=Phase.VALIDATE;
    }
    private void deferCandidate(boolean replan) {
        // Do not reset the published route or its verified paddle continuation.
        // Retrying a stale prefix must not cause a new planning stop every tick.
        building=new HashMap<>(); candidate=new ArrayList<>(); joinCells.clear();
        joinedCandidate=false; retryFromObserved=replan;
        phase=Phase.IDLE; nextPlanTick=observedTick+20;
    }
    private void joinOne(Terrain terrain) {
        if (joinCells.isEmpty()) {
            validateIndex=1; validateFrom=candidate.getFirst(); phase=Phase.VALIDATE; return;
        }
        long coordinate=joinCells.removeFirst(); int x=(int)(coordinate>>32),z=(int)coordinate;
        Terrain.Cell actual=terrain.cell(x,z);
        cells.put(coordinate,actual);
        if (actual==Terrain.Cell.ICE) {
            Node prior=building.get(coordinate);
            if (prior==null || prior.gap) building.put(coordinate,new Node(x,z,false));
        } else {
            Node old=activeRoad.get(coordinate);
            if (actual==Terrain.Cell.AIR && old!=null && old.gap) building.put(coordinate,new Node(x,z,true));
            else building.remove(coordinate);
        }
        // Boundary distance is capped at 2.5 and queries a three-cell halo.
        // Re-observing the patch can remove support as well as add new road.
        for (int dx=-3;dx<=3;dx++) for (int dz=-3;dz<=3;dz++) {
            Node nearby=building.get(key(x+dx,z+dz));
            if (nearby!=null) nearby.clearance=Double.NaN;
        }
    }
    private void validateOne(Terrain terrain,int tick) {
        if (validateIndex>=candidate.size()) {
            publish(tick); return;
        }
        Vec3 end=candidate.get(validateIndex),delta=end.subtract(validateFrom);
        Vec3 next=delta.length()>2 ? validateFrom.add(delta.normalize().scale(2)):end;
        if (!terrain.traversable(validateFrom,next) || !roadSweep(validateFrom,next,building,0)) {
            // A loaded ice cell at the frontier can be too close to unknown
            // space for the complete hull. Keep its already checked approach.
            if (validateFrom.distanceToSqr(anchor)>=64 && validateIndex>=1) {
                ArrayList<Vec3> prefix=new ArrayList<>(candidate.subList(0,validateIndex));
                if(prefix.isEmpty() || prefix.getLast().distanceToSqr(validateFrom)>1e-8) prefix.add(validateFrom);
                candidate=prefix; reached=false; publish(tick); return;
            }
            if (!rawFallback) { candidate=new ArrayList<>(raw); rawFallback=true; startValidation(); return; }
            phase=Phase.IDLE; nextPlanTick=tick+20; return;
        }
        validateFrom=next;
        if (next.distanceToSqr(end)<1e-8) validateIndex++;
    }
    private void publish(int tick) {
        if (joinedCandidate && !reached && observedPosition!=null) {
            Projection now=project(candidate,observedPosition);
            Vec3 tail=candidate.getLast();
            Projection oldNow=project(observedPosition),oldTail=project(tail);
            if (now.point.distanceToSqr(tail)<1e-8 && now.point.distanceToSqr(observedPosition)>.25
                    || oldTail.remaining>oldNow.remaining+.5 && oldTail.point.distanceToSqr(tail)<4) {
                deferCandidate(true); return;
            }
        }
        double progress=candidate.getLast().distanceToSqr(anchor);
        if (candidate.size()<2 || progress<1e-8 || !reached && progress<1) {
            phase=Phase.IDLE; nextPlanTick=tick+20; return;
        }
        route=List.copyOf(candidate); activeRoad=building; building=new HashMap<>();
        BoatIceCruisePlanner.reset(this);
        double length=0;
        for(int index=1;index<route.size();index++) length+=route.get(index).distanceTo(route.get(index-1));
        straightRoute=length<1e-8 || route.getFirst().distanceTo(route.getLast())/length>.97;
        sharpRoute=sharpBuilding;
        // A virtual start on non-ice is a recovery prefix, not a cruise
        // corridor. Keep its full stopping forecast until a new ice start is
        // published; applying the drift beam here can relaunch the hull sideways.
        recoveryRoute=seedNode.recoveryPoint!=null;
        routeReached=reached; publishedAt=anchor; nextPlanTick=tick+20; phase=Phase.IDLE;
    }

    /** Pure guidance over the published curve, also used by each predictive boat state. */
    public Guidance preview(Vec3 position,Vec3 velocity) {
        if (route.isEmpty() || !finite(position) || !finite(velocity)) return new Guidance(null,false,phase!=Phase.IDLE,false,0);
        Projection projection=project(position);
        double distance=projection.remaining;
        double crossTrack=position.subtract(projection.point).horizontalDistance();
        double ahead=Math.max(Math.min(20,4+2*crossTrack),Math.min(12,4+velocity.horizontalDistance()*14));
        if(sharpRoute) ahead=Math.max(4,Math.min(12,4+velocity.horizontalDistance()*14));
        ahead=Math.min(ahead,pursuitLimit);
        Vec3 target=along(projection,ahead);
        // A pursuit chord must retain the curve's clearance too. Tight bends
        // may require a closer tangent target, without treating it as a stop.
        for(int attempt=0;attempt<5 && !corridor(position,target) && ahead>.4;attempt++) {
            ahead*=.5; target=along(projection,ahead);
        }
        double speed=sharpRoute?1.6:4,travel=0;
        speed=Math.min(speed,Math.max(.16,speed/(1+(sharpRoute?3:6)*crossTrack*crossTrack)));
        for (int index=projection.segment+1;index+1<route.size() && travel<120;index++) {
            Vec3 a=route.get(index).subtract(route.get(index-1)),b=route.get(index+1).subtract(route.get(index));
            travel+=index==projection.segment+1 ? projection.point.distanceTo(route.get(index)):a.length();
            double length=Math.min(a.length(),b.length());
            if(length<1e-6) continue;
            double angle=Math.acos(Math.max(-1,Math.min(1,a.normalize().dot(b.normalize()))));
            if(angle<.01) continue;
            double radius=length/(2*Math.sin(angle*.5));
            double bend=Math.max(.13,Math.sqrt(.028*radius));
            speed=Math.min(speed,Math.sqrt(bend*bend+.024*Math.max(0,travel-2)));
        }
        speed=Math.min(speed,safeSpeed(Math.max(0,distance-.12),routeReached?0:20));
        boolean endpoint=projection.segment==route.size()-2
                && projection.point.distanceToSqr(route.getLast())<1e-8;
        double dockingDistance=BoatSteering.stoppingDistance(velocity.horizontalDistance(),.989f)
                +velocity.horizontalDistance()*4+.5;
        Vec3 home=route.getLast().subtract(position).multiply(1,0,1);
        boolean finalLane=velocity.horizontalDistance()<.02
                || home.normalize().dot(velocity.multiply(1,0,1).normalize())>.985;
        finalLane=finalLane && roadSweep(position,route.getLast(),activeRoad,.7075);
        boolean terminal=routeReached && (distance<=ahead
                || finalLane && distance<=dockingDistance || endpoint);
        if(terminal) target=route.getLast();
        if (!terminal) {
            Vec3 forward=target.subtract(projection.point).multiply(1,0,1);
            if (forward.horizontalDistance()>1e-6) {
                forward=forward.normalize();
                Vec3 lateral=velocity.subtract(forward.scale(velocity.dot(forward))).multiply(1,0,1);
                Vec3 correction=lateral.scale(18);
                if (correction.length()>2) correction=correction.normalize().scale(2);
                for(int attempt=0;attempt<5;attempt++) {
                    Vec3 corrected=target.subtract(correction);
                    if(roadSweep(position,corrected,activeRoad,0)) { target=corrected; break; }
                    correction=correction.scale(.5);
                }
            }
        }
        return new Guidance(target,terminal,phase!=Phase.IDLE,false,speed);
    }
    private record Projection(int segment,Vec3 point,double remaining) { }
    private Projection project(Vec3 position) { return project(route,position); }
    private static Projection project(List<Vec3> curve,Vec3 position) {
        int best=0; Vec3 point=curve.getFirst(); double error=Double.POSITIVE_INFINITY;
        for (int index=0;index+1<curve.size();index++) {
            Vec3 a=curve.get(index),d=curve.get(index+1).subtract(a);
            double t=d.lengthSqr()<1e-9?0:Math.max(0,Math.min(1,position.subtract(a).dot(d)/d.lengthSqr()));
            Vec3 p=a.add(d.scale(t)); double e=p.distanceToSqr(position);
            if (e<error) { error=e; best=index; point=p; }
        }
        double remaining=point.distanceTo(curve.get(best+1));
        for(int i=best+1;i+1<curve.size();i++) remaining+=curve.get(i).distanceTo(curve.get(i+1));
        if(best==curve.size()-2 && point.distanceToSqr(curve.getLast())<1e-8)
            remaining+=position.distanceTo(point);
        return new Projection(best,point,remaining);
    }
    private Vec3 along(Projection projection,double distance) { return along(route,projection,distance); }
    private static Vec3 along(List<Vec3> curve,Projection projection,double distance) {
        Vec3 point=projection.point;
        for(int i=projection.segment+1;i<curve.size();i++) {
            Vec3 delta=curve.get(i).subtract(point); double length=delta.length();
            if (length>=distance && length>1e-9) return point.add(delta.scale(distance/length));
            distance-=length; point=curve.get(i);
        }
        return curve.getLast();
    }
    private double remaining(Vec3 position) { return route.size()<2?0:project(position).remaining; }
    public double remainingDistance(Vec3 position) { return remaining(position); }
    public double crossTrackDistance(Vec3 position) {
        return route.size()<2?Double.POSITIVE_INFINITY:position.distanceTo(project(position).point);
    }
    /** Signed cross-track displacement after retaining the current lateral velocity. */
    public double lateralLead(Vec3 position,Vec3 velocity,double ticks) {
        if (route.size()<2) return Double.POSITIVE_INFINITY;
        Projection projection=project(position);
        Vec3 tangent=along(projection,2).subtract(projection.point);
        if (tangent.horizontalDistance()<1e-7)
            tangent=route.get(projection.segment+1).subtract(route.get(projection.segment));
        double length=tangent.horizontalDistance();
        if (length<1e-7) return 0;
        Vec3 error=position.subtract(projection.point).add(velocity.multiply(ticks,0,ticks));
        return (error.x*tangent.z-error.z*tangent.x)/length;
    }
    public boolean canCruise(Vec3 position,Vec3 velocity) {
        if(!finite(position) || !finite(velocity)) return false;
        double speed=velocity.horizontalDistance();
        return isGentleRoute() && route.size()>=2 && finite(position) && finite(velocity) && corridor(position,position)
                && remaining(position)>BoatSteering.stoppingDistance(speed,.989f)+speed*32+8;
    }
    public boolean canFollow(Vec3 position,Vec3 velocity) {
        if (!routeReached) return canCruise(position,velocity);
        return isGentleRoute() && finite(position) && finite(velocity) && corridor(position,position)
                && remaining(position)>BoatSteering.stoppingDistance(velocity.horizontalDistance(),.989f)
                    +velocity.horizontalDistance()*4+.5;
    }

    /** Exact distance to the cached effective road boundary, conservatively capped. */
    public double clearance(Vec3 point) { return clearance(point,activeRoad); }
    private static double clearance(Vec3 point,Map<Long,Node> road) {
        if (!inside(point,road)) return 0;
        int x=(int)Math.floor(point.x),z=(int)Math.floor(point.z); double nearest=2.5;
        for(int dx=-3;dx<=3;dx++) for(int dz=-3;dz<=3;dz++) {
            int bx=x+dx,bz=z+dz;
            if (road.containsKey(key(bx,bz))) continue;
            double ax=Math.max(0,Math.max(bx-point.x,point.x-bx-1));
            double az=Math.max(0,Math.max(bz-point.z,point.z-bz-1));
            nearest=Math.min(nearest,Math.hypot(ax,az));
        }
        return nearest;
    }
    /** Prevent live/predicted shortcuts from cutting outside the published road's central corridor. */
    public boolean corridor(Vec3 from,Vec3 to) {
        if (route.isEmpty()) return false;
        Projection local=project(to);
        double planned=Math.min(MARGIN,clearance(local.point));
        double required=Math.max(0,Math.min(Math.min(planned-.04,straightRoute || sharpRoute?MARGIN-.04:.8075),frameMargin));
        return roadSweep(from,to,activeRoad,required);
    }
    private static boolean roadSweep(Vec3 from,Vec3 to,Map<Long,Node> road,double margin) {
        int count=Math.max(1,(int)Math.ceil(from.distanceTo(to)/.25));
        for(int i=0;i<=count;i++) {
            Vec3 p=from.lerp(to,i/(double)count);
            if (!inside(p,road) || !iceContact(p,road) || margin>0 && !hasMargin(p,road,margin)) return false;
        }
        return true;
    }
    private static boolean iceContact(Vec3 p,Map<Long,Node> road) {
        for(int x=(int)Math.floor(p.x-.6875);x<Math.ceil(p.x+.6875);x++)
            for(int z=(int)Math.floor(p.z-.6875);z<Math.ceil(p.z+.6875);z++) {
                Node at=road.get(key(x,z)); if(at!=null && !at.gap) return true;
            }
        return false;
    }
    private static boolean hasMargin(Vec3 p,Map<Long,Node> road,double margin) {
        Node at=road.get(key((int)Math.floor(p.x),(int)Math.floor(p.z)));
        if(at!=null && !Double.isNaN(at.clearance)
                && at.clearance-p.distanceTo(at.point(p.y))>=margin) return true;
        double squared=margin*margin;
        for(int x=(int)Math.floor(p.x-margin);x<=Math.floor(p.x+margin);x++)
            for(int z=(int)Math.floor(p.z-margin);z<=Math.floor(p.z+margin);z++) {
                if(road.containsKey(key(x,z))) continue;
                double dx=Math.max(0,Math.max(x-p.x,p.x-x-1));
                double dz=Math.max(0,Math.max(z-p.z,p.z-z-1));
                if(dx*dx+dz*dz+1e-9<squared) return false;
            }
        return true;
    }
    private static boolean inside(Vec3 point,Map<Long,Node> road) {
        return finite(point) && road.containsKey(key((int)Math.floor(point.x),(int)Math.floor(point.z)));
    }
    private static List<Vec3> compress(List<Vec3> source) {
        ArrayList<Vec3> result=new ArrayList<>();
        for(Vec3 point:source) {
            if(!result.isEmpty() && result.getLast().distanceToSqr(point)<1e-9) continue;
            while(result.size()>1) {
                Vec3 a=result.getLast().subtract(result.get(result.size()-2)),b=point.subtract(result.getLast());
                if(a.normalize().dot(b.normalize())<.999999) break;
                result.removeLast();
            }
            result.add(point);
        }
        return result;
    }
    private double estimate(Vec3 point) {
        if(goal instanceof GoalXZ g) return Math.hypot(point.x-g.getX()-.5,point.z-g.getZ()-.5);
        if(goal instanceof GoalBlock g) return Math.hypot(point.x-g.x-.5,point.z-g.z-.5);
        double value=goal.heuristic(BlockPos.containing(point));
        return Double.isFinite(value)?Math.max(0,value/4):1e9;
    }
    private static double safeSpeed(double distance,int headway) {
        double lo=0,hi=4;
        for(int i=0;i<12;i++) { double mid=(lo+hi)*.5; if(BoatSteering.stoppingDistance(mid,.989f)+mid*headway<=distance) lo=mid; else hi=mid; }
        return lo;
    }
    private static long key(int x,int z) { return (long)x<<32 ^ (z&0xffffffffL); }
    private static boolean finite(Vec3 p) { return p!=null && Double.isFinite(p.x) && Double.isFinite(p.y) && Double.isFinite(p.z); }
}
