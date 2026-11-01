package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Bounded any-angle search: direct corridor first, coarse open-water edges and fine obstacle edges. */
public final class SwimRoutePlanner {
    public enum Kind { BLOCKED, WATER, SURFACE, AIR, UP, DOWN, SHORE;
        public boolean breath() { return this == SURFACE || this == AIR || this == UP || this == DOWN || this == SHORE; }
    }
    public interface Terrain {
        Kind kind(BlockPos p);
        Vec3 point(BlockPos p);
        boolean clear(Vec3 from, Vec3 to);
    }
    private record Node(BlockPos p, double g, double f) { }
    private final Terrain terrain;
    private final BlockPos start, target;
    private final boolean airOnly, surface, xz, surfaceRefuge;
    private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
    private final Map<BlockPos, Double> costs = new HashMap<>();
    private final Map<BlockPos, BlockPos> parents = new HashMap<>();
    private final Set<BlockPos> closed = new HashSet<>();
    private BlockPos best;
    private double bestH;
    private boolean done, reached;
    private Node expanding;
    private int neighbour;
    private BlockPos direct;
    private boolean directDone;
    private int edgeChecks;
    private List<Vec3> result = List.of();
    public static final int MAX_NODES = 8192, RADIUS = 48;

    public SwimRoutePlanner(Terrain terrain, BlockPos start, BlockPos target, boolean airOnly, boolean surface, boolean xz) {
        this(terrain,start,target,airOnly,surface,xz,false);
    }
    public SwimRoutePlanner(Terrain terrain, BlockPos start, BlockPos target, boolean airOnly, boolean surface, boolean xz, boolean surfaceRefuge) {
        this.terrain = terrain; this.start = start.immutable(); this.target = target.immutable();
        this.airOnly = airOnly; this.surface = surface; this.xz = xz; this.surfaceRefuge=surfaceRefuge;
        best = this.start; bestH = heuristic(start);
        direct=this.start; directDone=airOnly;
        costs.put(this.start, 0.0); open.add(new Node(this.start, 0, bestH));
    }
    private double heuristic(BlockPos p) {
        return airOnly ? (surfaceRefuge ? -.65*(p.getY()-start.getY())
                + .55*(Math.hypot(p.getX()-target.getX(),p.getZ()-target.getZ())
                - Math.hypot(start.getX()-target.getX(),start.getZ()-target.getZ())) : 0) : Math.sqrt(Math.pow((double)p.getX()-target.getX(), 2)
                + Math.pow((double)p.getZ()-target.getZ(), 2) + (xz ? 0 : Math.pow((double)p.getY()-target.getY(), 2)));
    }
    public void step(int expansions, long deadline) {
        for (int count=0; !done && count<expansions*27 && System.nanoTime()<deadline; count++) {
            if(!directDone) {
                BlockPos next=towardGoal(direct,6);
                if(next.equals(direct) || !inRange(next) || !usable(next)
                        || !edgeClear(terrain.point(direct),terrain.point(next))) {directDone=true;continue;}
                double g=costs.get(direct)+terrain.point(direct).distanceTo(terrain.point(next));
                costs.put(next,g);parents.put(next,direct);
                open.add(new Node(next,g,g+1.2*heuristic(next)));
                direct=next;
                if(heuristic(next)<bestH){best=next;bestH=heuristic(next);}
                if(heuristic(next)<.6){finish(next,true);break;}
                if(frontier(next)){finish(next,false);break;}
                continue;
            }
            Node n = expanding == null ? open.poll() : expanding;
            if (n == null || closed.size() >= MAX_NODES) { finish(best, false); break; }
            if (expanding == null && !closed.add(n.p)) continue;
            Kind kind = terrain.kind(n.p);
            if (kind == Kind.BLOCKED && !n.p.equals(start)) {expanding=null;continue;}
            double h = heuristic(n.p);
            if (h < bestH && kind != Kind.SHORE) { best=n.p; bestH=h; }
            if (airOnly ? (surfaceRefuge ? kind==Kind.SURFACE : kind.breath()) : h < .6) {
                finish(n.p, true); break;
            }
            if (!airOnly && frontier(n.p) && bestH < heuristic(start)-8) {
                finish(n.p, false); break;
            }
            if (expanding == null) { expanding=n; neighbour=0; }
            {
                int next=neighbour++;
                if(neighbour>=(airOnly?27:55))expanding=null;
                BlockPos p;
                if(!airOnly && next==0)p=towardGoal(n.p,6);
                else {
                    int index=airOnly?next:(next-1)%27;
                    int stride=!airOnly&&next<=27?3:1;
                    int dx=index/9-1,dy=(index/3)%3-1,dz=index%3-1;
                    if(dx==0&&dy==0&&dz==0)continue;
                    p=n.p.offset(dx*stride,dy*stride,dz*stride);
                }
                if (p.equals(n.p) || !inRange(p) || closed.contains(p)) continue;
                Kind k=terrain.kind(p);
                if (k==Kind.BLOCKED || surface && k==Kind.WATER) continue;
                Vec3 a=terrain.point(n.p), b=terrain.point(p);
                if (!edgeClear(a,b)) continue;
                double g=n.g+a.distanceTo(b)+(k==Kind.DOWN ? .7 : 0);
                BlockPos parent=n.p, ancestor=parents.get(n.p);
                // Local Theta* shortcut; bounded visibility avoids stair-step zigzags in open water.
                if(!airOnly && ancestor!=null && terrain.point(ancestor).distanceTo(b)<=8) {
                    double shorter=costs.get(ancestor)+terrain.point(ancestor).distanceTo(b)+(k==Kind.DOWN?.7:0);
                    if(shorter<g-1e-6 && edgeClear(terrain.point(ancestor),b)) {g=shorter;parent=ancestor;}
                }
                if (g>=costs.getOrDefault(p,Double.POSITIVE_INFINITY)) continue;
                if (costs.size()>=MAX_NODES*2) { finish(best,false); return; }
                costs.put(p,g); parents.put(p,parent);
                open.add(new Node(p,g,g+1.2*heuristic(p)));
            }
        }
    }
    private BlockPos towardGoal(BlockPos from,double step) {
        Vec3 delta=new Vec3(target.getX()-from.getX(),xz?0:target.getY()-from.getY(),target.getZ()-from.getZ());
        if(delta.length()<=step)return new BlockPos(target.getX(),xz?from.getY():target.getY(),target.getZ());
        Vec3 d=delta.normalize().scale(step);
        return from.offset((int)Math.round(d.x),(int)Math.round(d.y),(int)Math.round(d.z));
    }
    private boolean inRange(BlockPos p) {
        return Math.abs(p.getX()-start.getX())<=RADIUS && Math.abs(p.getZ()-start.getZ())<=RADIUS && Math.abs(p.getY()-start.getY())<=RADIUS;
    }
    private boolean frontier(BlockPos p) {
        return Math.max(Math.max(Math.abs(p.getX()-start.getX()),Math.abs(p.getZ()-start.getZ())),Math.abs(p.getY()-start.getY()))>=RADIUS-2;
    }
    private boolean usable(BlockPos p) {Kind k=terrain.kind(p);return k!=Kind.BLOCKED&&(!surface||k!=Kind.WATER);}
    private boolean edgeClear(Vec3 a,Vec3 b) {
        edgeChecks++;
        if(a.distanceTo(b)>8.5 || !terrain.clear(a,b))return false;
        if(surface) {
            int samples=Math.max(1,(int)Math.ceil(a.distanceTo(b)*2));
            for(int i=1;i<=samples;i++)if(!usable(BlockPos.containing(a.lerp(b,(double)i/samples))))return false;
        }
        return true;
    }
    private void finish(BlockPos end, boolean reached) {
        done=true; this.reached=reached;
        if (airOnly && !reached) return;
        ArrayList<Vec3> points=new ArrayList<>();
        for (BlockPos p=end; p!=null; p=parents.get(p)) points.add(terrain.point(p));
        Collections.reverse(points);
        // Resampling uses already checked straight edges, with no unbounded world-query smoothing pass.
        ArrayList<Vec3> dense=new ArrayList<>();
        for(int i=0;i<points.size();i++) {
            if(i==0){dense.add(points.get(i));continue;}
            Vec3 a=points.get(i-1),b=points.get(i);
            int steps=Math.max(1,(int)Math.ceil(a.distanceTo(b)/1.25));
            for(int j=1;j<=steps;j++)dense.add(a.lerp(b,(double)j/steps));
        }
        result=List.copyOf(dense);
    }
    public boolean done() { return done; }
    public boolean reached() { return reached; }
    public List<Vec3> path() { return result; }
    public int visited() { return closed.size(); }
    public int edgeChecks() {return edgeChecks;}
    public static double length(Vec3 from, List<Vec3> route) {
        double length=0; for (Vec3 point:route) { length+=from.distanceTo(point); from=point; } return length;
    }
    /** Safe time is deliberately independent of probabilistic Respiration bonuses. */
    public static boolean needsAir(int air, int maximum, double escapeDistance, double speed) {
        // Unknown is not a fictitious twenty-block return trip. Keep searching while travelling.
        if(!Double.isFinite(escapeDistance))return air<=Math.min(90,maximum*.3);
        double ticks=Math.max(0,escapeDistance) / Math.max(.06,Math.min(.18,speed));
        return air<=Math.min(maximum-20,Math.max(60,ticks+30));
    }
    public static float turn(float current, float target, float limit) {
        float delta=(target-current)%360; if(delta>180)delta-=360; if(delta< -180)delta+=360;
        return current+Math.max(-limit,Math.min(limit,delta));
    }
}
