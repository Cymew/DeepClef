package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Incremental, bounded 26-neighbour search. Unknown cells are never traversable. */
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
    private final boolean airOnly, surface, xz;
    private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
    private final Map<BlockPos, Double> costs = new HashMap<>();
    private final Map<BlockPos, BlockPos> parents = new HashMap<>();
    private final Set<BlockPos> closed = new HashSet<>();
    private BlockPos best;
    private double bestH;
    private boolean done, reached;
    private Node expanding;
    private int neighbour;
    private List<Vec3> result = List.of();
    public static final int MAX_NODES = 8192, RADIUS = 48;

    public SwimRoutePlanner(Terrain terrain, BlockPos start, BlockPos target, boolean airOnly, boolean surface, boolean xz) {
        this.terrain = terrain; this.start = start.immutable(); this.target = target.immutable();
        this.airOnly = airOnly; this.surface = surface; this.xz = xz;
        best = this.start; bestH = heuristic(start);
        costs.put(this.start, 0.0); open.add(new Node(this.start, 0, bestH));
    }
    private double heuristic(BlockPos p) {
        return airOnly ? 0 : Math.sqrt(Math.pow((double)p.getX()-target.getX(), 2)
                + Math.pow((double)p.getZ()-target.getZ(), 2) + (xz ? 0 : Math.pow((double)p.getY()-target.getY(), 2)));
    }
    public void step(int expansions, long deadline) {
        for (int count=0; !done && count<expansions*27 && System.nanoTime()<deadline; count++) {
            Node n = expanding == null ? open.poll() : expanding;
            if (n == null || closed.size() >= MAX_NODES) { finish(best, false); break; }
            if (expanding == null && !closed.add(n.p)) continue;
            Kind kind = terrain.kind(n.p);
            if (kind == Kind.BLOCKED && !n.p.equals(start)) continue;
            double h = heuristic(n.p);
            if (h < bestH && kind != Kind.SHORE) { best=n.p; bestH=h; }
            if (airOnly ? kind.breath() : h < .6 || xz && kind == Kind.SHORE && h < heuristic(start)-2) {
                finish(n.p, true); break;
            }
            if (!airOnly && (Math.abs(n.p.getX()-start.getX()) >= RADIUS-2
                    || Math.abs(n.p.getZ()-start.getZ()) >= RADIUS-2) && bestH < heuristic(start)-8) {
                finish(n.p, false); break;
            }
            // Shore nodes are exits, not a replacement for the land pathfinder.
            if (kind == Kind.SHORE && !n.p.equals(start)) continue;
            if (expanding == null) { expanding=n; neighbour=0; }
            {
                int next=neighbour++;
                int dx=next/9-1,dy=(next/3)%3-1,dz=next%3-1;
                if(neighbour>=27)expanding=null;
                if (dx==0 && dy==0 && dz==0) continue;
                BlockPos p = n.p.offset(dx,dy,dz);
                if (Math.abs(p.getX()-start.getX())>RADIUS || Math.abs(p.getZ()-start.getZ())>RADIUS
                        || Math.abs(p.getY()-start.getY())>RADIUS || closed.contains(p)) continue;
                Kind k=terrain.kind(p);
                if (k==Kind.BLOCKED || surface && k==Kind.WATER) continue;
                Vec3 a=terrain.point(n.p), b=terrain.point(p);
                if (!terrain.clear(a,b)) continue;
                double g=n.g+a.distanceTo(b)+(k==Kind.DOWN ? .7 : 0);
                if (g>=costs.getOrDefault(p,Double.POSITIVE_INFINITY)) continue;
                if (costs.size()>=MAX_NODES*2) { finish(best,false); return; }
                costs.put(p,g); parents.put(p,n.p);
                open.add(new Node(p,g,g+1.2*heuristic(p)));
            }
        }
    }
    private void finish(BlockPos end, boolean reached) {
        done=true; this.reached=reached;
        if (airOnly && !reached) return;
        ArrayList<Vec3> points=new ArrayList<>();
        for (BlockPos p=end; p!=null; p=parents.get(p)) points.add(terrain.point(p));
        Collections.reverse(points); result=List.copyOf(points);
    }
    public boolean done() { return done; }
    public boolean reached() { return reached; }
    public List<Vec3> path() { return result; }
    public int visited() { return closed.size(); }
    public static double length(Vec3 from, List<Vec3> route) {
        double length=0; for (Vec3 point:route) { length+=from.distanceTo(point); from=point; } return length;
    }
    /** Safe time is deliberately independent of probabilistic Respiration bonuses. */
    public static boolean needsAir(int air, int maximum, double escapeDistance, double speed) {
        double ticks=escapeDistance / Math.max(.055,Math.min(.15,speed));
        return air < Math.min(maximum-8, Math.max(100, ticks+65));
    }
    public static float turn(float current, float target, float limit) {
        float delta=(target-current)%360; if(delta>180)delta-=360; if(delta< -180)delta+=360;
        return current+Math.max(-limit,Math.min(limit,delta));
    }
}
