package adris.altoclef.util;

import adris.altoclef.AltoClef;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.*;
import java.util.function.BiPredicate;

/** Player-eye observations. Exact nearby surfaces and a short-lived, coarse description of the horizon. */
public final class SceneObservation {
    public static final int NEAR=16;
    private static final String[] DIRECTIONS={"南","西南","西","西北","北","东北","东","东南"};
    private static final double[] COLUMNS={0,-.45,.45,-.88,.88};
    private static final double[] ROWS={-.08,.10,-.025,.28,-.23,.025,-.006,.48,-.50};
    private static final float[] HORIZON_PITCH = {3, -6, 1, -15};
    private static final int LIFETIME=120;
    private record Sample(int tick,int sector,String form, BlockPos hit,double distance) {}
    private final Deque<Sample> samples=new ArrayDeque<>();
    private int cursor;
    private Object world;
    private Vec3 origin;
    private int previousRange;
    private int lastTick;

    public void reset() {
        samples.clear();
        world = null;
        origin = null;
        cursor = 0;
    }

    static int sector(Vec3 direction) {
        return Math.floorMod((int)Math.round(Math.toDegrees(Math.atan2(-direction.x,direction.z))/45),8);
    }

    public static double range(Minecraft mc) {
        var fog=mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.fogData;
        int blocks=mc.options.getEffectiveRenderDistance()*16;
        return fog.environmentalEnd>0 ? Math.min(blocks,fog.environmentalEnd) : blocks;
    }

    static double verticalTangent(Minecraft mc) {
        float fov=mc.gameRenderer.mainCamera().getFov();
        if(fov<=0) fov=mc.options.fov().get();
        return Math.tan(Math.toRadians(fov)*.5);
    }

    static Vec3 viewRay(Vec3 forward,float yaw,double x,double y) {
        Vec3 right=Vec3.directionFromRotation(0,yaw+90);
        Vec3 up=right.cross(forward).normalize();
        return forward.add(right.scale(x)).add(up.scale(y)).normalize();
    }

    static boolean inView(Minecraft mc,LocalPlayer player,Vec3 delta) {
        Vec3 forward=player.getViewVector(1),right=Vec3.directionFromRotation(0,player.getYRot()+90);
        double depth=delta.dot(forward);
        double halfHeight=depth*verticalTangent(mc);
        return depth>0 && Math.abs(delta.dot(right.cross(forward)))<=halfHeight
                && Math.abs(delta.dot(right))<=halfHeight*aspect(mc);
    }

    static double aspect(Minecraft mc) {
        return (double)mc.getWindow().getWidth()/Math.max(1,mc.getWindow().getHeight());
    }

    /** Clip at the first missing chunk. A loaded chunk beyond a gap cannot contribute an observation. */
    static double loadedLength(Vec3 from, Vec3 dir,double length,BiPredicate<Integer,Integer> loaded) {
        int x=(int)Math.floor(from.x/16), z=(int)Math.floor(from.z/16);
        int sx=dir.x>0 ? 1 : dir.x<0 ? -1 : 0;
        int sz=dir.z>0 ? 1 : dir.z<0 ? -1 : 0;
        double stepX=sx==0?Double.POSITIVE_INFINITY:16/Math.abs(dir.x);
        double stepZ=sz==0?Double.POSITIVE_INFINITY:16/Math.abs(dir.z);
        double tx=sx==0?Double.POSITIVE_INFINITY:((x+(sx>0?1:0))*16-from.x)/dir.x;
        double tz=sz==0?Double.POSITIVE_INFINITY:((z+(sz>0?1:0))*16-from.z)/dir.z;
        double travelled=0;
        while(travelled<length) {
            if(!loaded.test(x,z)) return Math.max(0,travelled-.002);
            travelled=Math.min(tx,tz);
            if(tx<=travelled) { x+=sx; tx+=stepX; }
            if(tz<=travelled) { z+=sz; tz+=stepZ; }
        }
        return length;
    }

    static BlockHitResult cast(AltoClef mod, Vec3 direction,double limit) {
        var player=mod.getPlayer();
        return firstHit(mod.level(), player.getEyePosition(), direction, limit, mod.level().getChunkSource()::hasChunk,
                player.isUnderWater(), CollisionContext.of(player));
    }

    static BlockHitResult firstHit(BlockGetter level, Vec3 eye, Vec3 direction, double limit,
                                   BiPredicate<Integer,Integer> loaded, boolean submerged, CollisionContext body) {
        double length = loadedLength(eye, direction, limit, loaded);
        return level.clip(new ClipContext(eye, eye.add(direction.scale(length)), ClipContext.Block.OUTLINE,
                submerged ? ClipContext.Fluid.NONE : ClipContext.Fluid.ANY, body));
    }

    static boolean dropLineVisible(BlockGetter level, Vec3 eye, Vec3 point,
                                   BiPredicate<Integer,Integer> loaded, CollisionContext body) {
        var delta = point.subtract(eye);
        double distance = delta.length();
        if (distance < .001) return true;
        if (loadedLength(eye, delta.scale(1 / distance), distance, loaded) < distance) return false;
        // Grass has a selection outline but does not hide the floating item rendered behind it.
        return unobstructedTo(level.clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, body)), point);
    }

    static boolean lit(AltoClef mod,BlockPos pos) {
        return mod.getPlayer().hasEffect(MobEffects.NIGHT_VISION) || mod.level().getMaxLocalRawBrightness(pos)>1;
    }

    /** Require a ray to actually hit the requested block, not a neighbouring face. */
    public static boolean visibleBlock(AltoClef mod,BlockPos pos) {
        return surfaceHit(mod, pos, true) != null;
    }

    static BlockHitResult surfaceHit(AltoClef mod, BlockPos pos, boolean requireView) {
        var mc = Minecraft.getInstance();
        var player = mod.getPlayer();
        double limit = Math.min(NEAR, range(mc));
        Vec3 eye = player.getEyePosition();
        return surfaceHit(pos, eye, limit, d -> !requireView || inView(mc, player, d),
                (dir, length) -> cast(mod, dir, length), p -> lit(mod, p));
    }

    // Sample the centre, face centres and inset corners. Each candidate still needs a real first hit.
    static BlockHitResult surfaceHit(BlockPos pos, Vec3 eye, double limit, java.util.function.Predicate<Vec3> inView,
            java.util.function.BiFunction<Vec3, Double, BlockHitResult> ray, java.util.function.Predicate<BlockPos> lit) {
        Vec3 centre = Vec3.atCenterOf(pos);
        if (eye.distanceTo(centre) > limit + .87) return null;
        for (Vec3 offset : SURFACE_POINTS) {
            Vec3 delta = centre.add(offset).subtract(eye);
            if (!inView.test(delta) || delta.lengthSqr() < 1e-8) continue;
            var hit = ray.apply(delta.normalize(), Math.min(limit, delta.length() + .03));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)
                    && lit.test(pos.relative(hit.getDirection()))) return hit;
        }
        return null;
    }

    private static final List<Vec3> SURFACE_POINTS = surfacePoints();
    private static List<Vec3> surfacePoints() {
        List<Vec3> points = new ArrayList<>();
        points.add(Vec3.ZERO);
        for (var face : Direction.values()) points.add(new Vec3(face.getStepX(), face.getStepY(), face.getStepZ()).scale(.499));
        for (double x : new double[]{-.36,.36}) for (double y : new double[]{-.36,.36})
            for (double z : new double[]{-.36,.36}) points.add(new Vec3(x,y,z));
        return List.copyOf(points);
    }

    public static boolean visibleEntityPoint(AltoClef mod,Vec3 point) {
        return visibleEntityPoint(mod, point, true);
    }

    static boolean visibleEntityPoint(AltoClef mod, Vec3 point, boolean requireView) {
        var player=mod.getPlayer();
        Vec3 delta=point.subtract(player.getEyePosition());
        if (delta.length()>Math.min(NEAR,range(Minecraft.getInstance())) || (requireView && !inView(Minecraft.getInstance(),player,delta))) return false;
        var hit = cast(mod, delta.normalize(), delta.length());
        return unobstructedTo(hit, point) && lit(mod,BlockPos.containing(point));
    }

    static boolean unobstructedTo(BlockHitResult hit, Vec3 point) {
        return hit.getType()==HitResult.Type.MISS && hit.getLocation().distanceToSqr(point)<1e-8;
    }

    public static BlockHitResult nearLook(AltoClef mod) {
        var hit = cast(mod,mod.getPlayer().getViewVector(1),Math.min(NEAR,range(Minecraft.getInstance())));
        return hit.getType()==HitResult.Type.BLOCK && lit(mod,hit.getBlockPos().relative(hit.getDirection())) ? hit : null;
    }

    /** Fixed eye, centre-first rings across the current screen; only an actual first hit can be a door. */
    public static final int DOOR_SCAN_SAMPLES = 49*49;
    public static BlockPos visibleDoor(AltoClef mod, int start, int budget) {
        var mc = Minecraft.getInstance();
        var player = mod.getPlayer();
        double vertical = verticalTangent(mc);
        double horizontal = vertical*aspect(mc);
        double limit = Math.min(NEAR,range(mc));
        for (int i=start; i<Math.min(DOOR_SCAN_SAMPLES,start+budget); i++) {
            BlockPos sample = screenSample(i);
            Vec3 dir = viewRay(player.getViewVector(1),player.getYRot(),sample.getX()*horizontal/24,sample.getY()*vertical/24);
            var hit = cast(mod,dir,limit);
            if (hit.getType()==HitResult.Type.BLOCK && lit(mod,hit.getBlockPos().relative(hit.getDirection()))
                    && mod.level().getBlockState(hit.getBlockPos()).getBlock() instanceof net.minecraft.world.level.block.DoorBlock)
                return hit.getBlockPos().immutable();
        }
        return null;
    }

    static BlockPos screenSample(int index) {
        if (index==0) return BlockPos.ZERO;
        int r=(int)Math.ceil((Math.sqrt(index+1)-1)/2);
        int offset=index-(2*r-1)*(2*r-1);
        int along=offset%(2*r)-r;
        return switch (offset/(2*r)) {
            case 0 -> new BlockPos(along,-r,0);
            case 1 -> new BlockPos(r,along,0);
            case 2 -> new BlockPos(-along,r,0);
            default -> new BlockPos(-r,-along,0);
        };
    }

    // Fixed world-space headings keep coverage independent of the player's yaw, pitch and FOV.
    static Vec3 compassRay(int heading, int divisions, float pitch) {
        return Vec3.directionFromRotation(pitch, heading * 360f / divisions);
    }

    // Historical silhouettes only supply ray directions. The next query must hit a live matching surface.
    List<BlockPos> treeBearings() {
        return samples.stream().filter(s -> s.form.equals("树冠")).map(Sample::hit).distinct().toList();
    }

    static String bearing(Vec3 delta) { return DIRECTIONS[sector(delta)] + "方"; }

    private static final int[] NEAR_PITCH = {-48, -18, 0, 10, 18, 30, 48, 70};

    static List<BlockHitResult> surroundings(AltoClef mod) {
        var mc = Minecraft.getInstance();
        double limit = Math.min(NEAR, range(mc));
        var hits=surroundings(mod.getPlayer().getEyePosition(), dir -> cast(mod, dir, limit), face -> lit(mod, face));
        if(mod.playerRoutine()!=null && mod.memory()!=null) mod.playerRoutine().atlas.observe(mod,hits);
        return hits;
    }

    static List<BlockHitResult> surroundings(Vec3 eye, java.util.function.Function<Vec3, BlockHitResult> cast,
                                              java.util.function.Predicate<BlockPos> lit) {
        var hits = new LinkedHashMap<BlockPos, BlockHitResult>();
        for (int heading = 0; heading < 32; heading++) {
            for (int pitch : NEAR_PITCH) {
                var hit = cast.apply(compassRay(heading, 32, pitch));
                if (hit.getType() == HitResult.Type.BLOCK && lit.test(hit.getBlockPos().relative(hit.getDirection())))
                    hits.merge(hit.getBlockPos(), hit, (a,b) -> a.getLocation().distanceToSqr(eye)
                            <= b.getLocation().distanceToSqr(eye) ? a : b);
            }
        }
        return List.copyOf(hits.values());
    }

    static String nearSummary(AltoClef mod, List<BlockHitResult> hits) {
        var eye = mod.getPlayer().getEyePosition();
        Map<Integer, Set<String>> groups = new TreeMap<>();
        for (var hit : hits) {
            var labels = groups.computeIfAbsent(sector(hit.getLocation().subtract(eye)), ignored -> new LinkedHashSet<>());
            if (labels.size() < 4) labels.add(mod.level().getBlockState(hit.getBlockPos()).getBlock().getName().getString());
        }
        return groups.entrySet().stream().map(e -> DIRECTIONS[e.getKey()] + "方有" + String.join("、", e.getValue()))
                .collect(java.util.stream.Collectors.joining("；"));
    }

    public String describe(AltoClef mod) {
        var level = mod.level();
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return summary();
        var precipitation = level.isRaining() ? level.getPrecipitationAt(mod.getPlayer().blockPosition())
                : net.minecraft.world.level.biome.Biome.Precipitation.NONE;
        return overworldSky(level.getOverworldClockTime(),level.isRaining(),level.isThundering(),precipitation)
                + "\n" + summary();
    }

    static String overworldSky(long ticks, boolean rain, boolean thunder, net.minecraft.world.level.biome.Biome.Precipitation precipitation) {
        int day = Math.floorMod(ticks,24000);
        int minutes = (360 + day*60/1000) % 1440;
        String light = day < 1000 ? "刚日出" : day < 5500 ? "上午" : day < 7000 ? "中午"
                : day < 11500 ? "下午" : day < 13000 ? "要天黑了" : day < 23000 ? "夜间" : "天快亮了";
        String weather = rain ? switch (precipitation) {
            case SNOW -> "降雪";
            case RAIN -> "降雨";
            case NONE -> "本地无降水";
        } : "晴";
        if (thunder) weather += "，雷暴";
        return String.format(java.util.Locale.ROOT,"主世界 %02d:%02d（%s），%s。",minutes/60,minutes%60,light,weather);
    }

    /** Spread the screen-space samples over ticks. Never rotate the camera to collect data. */
    public void tick(AltoClef mod) {
        Minecraft mc=Minecraft.getInstance();
        var player=mod.getPlayer();
        if(player==null || mc.isPaused()) return;
        int limit=(int)range(mc);
        Vec3 eye=player.getEyePosition();
        beginFrame(mod.level(), eye, player.tickCount, limit);
        if(limit<=NEAR || player.isSpectator() || mc.getCameraEntity()!=player) return;
        int slot=cursor;
        boolean around = mod.worldMode() == adris.altoclef.mode.WorldMode.PLAYER;
        cursor=(cursor+1)%(around ? 96 : ROWS.length*COLUMNS.length);
        double vertical=verticalTangent(mc);
        Vec3 dir = around ? compassRay((slot % 8) * 3 + (slot / 8) % 3, 24, HORIZON_PITCH[slot / 24])
                : viewRay(player.getViewVector(1),player.getYRot(),COLUMNS[slot%COLUMNS.length]*vertical*aspect(mc),
                    ROWS[slot/COLUMNS.length]*vertical);
        var hit=cast(mod,dir,limit);
        if(hit.getType()!=HitResult.Type.BLOCK) return;
        BlockPos p=hit.getBlockPos();
        double distance=eye.distanceTo(hit.getLocation());
        if(distance<=NEAR || distance>limit || !lit(mod,p.relative(hit.getDirection()))
                || (!around && !mc.levelRenderer.isSectionCompiledAndVisible(p,(long)(mc.options.chunkSectionFadeInTime().get()*1000)))) return;
        var state=mod.level().getBlockState(p);
        String form;
        if(state.getFluidState().is(FluidTags.WATER) && state.getCollisionShape(mod.level(),p).isEmpty()) form="水面";
        else if (player.isUnderWater() && hit.getDirection()==Direction.UP && p.getY()<eye.y
                && (state.is(BlockTags.SAND) || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(Blocks.GRAVEL))) form="水底轮廓";
        else if(state.is(BlockTags.LEAVES)) form="树冠";
        else if(p.getY()>eye.y+12 && (state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.DIRT)
                || state.is(BlockTags.SAND) || state.is(BlockTags.SNOW))) form="山地轮廓";
        else if (Math.abs(eye.y-p.getY()) < 9 && (state.is(Blocks.GRASS_BLOCK) && hit.getDirection()==Direction.UP
                || state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS))) form="草地";
        else return;
        record(player.tickCount, dir, form, p, distance);
    }

    void beginFrame(Object level, Vec3 eye, int tick, int limit) {
        if (level != world || tick < lastTick) reset();
        if (origin == null || eye.distanceToSqr(origin) > 16 || limit < previousRange) {
            // Start fresh evidence without restarting the ray sequence on every few steps.
            samples.clear();
            origin = eye;
        }
        world = level;
        lastTick = tick;
        previousRange = limit;
        while (!samples.isEmpty() && tick - samples.getFirst().tick > LIFETIME) samples.removeFirst();
    }

    void record(int tick, Vec3 direction, String form, BlockPos block, double distance) {
        if (distance > NEAR && distance <= previousRange) {
            samples.addLast(new Sample(tick, sector(direction), form, block.immutable(), distance));
        }
    }

    private static final class Patch {
        final BlockPos first;
        final int height;
        boolean spread,level=true;
        double horizontalSpan;
        double nearest=Double.POSITIVE_INFINITY;
        Patch(BlockPos p) {first=p;height=p.getY();}
        void observe(BlockPos p,double distance) {
            horizontalSpan=Math.max(horizontalSpan, Math.hypot(first.getX()-p.getX(), first.getZ()-p.getZ()));
            spread|=horizontalSpan>=4;
            level&=Math.abs(p.getY()-height)<=3;
            nearest=Math.min(nearest,distance);
        }
    }

    public String summary() {
        Map<Integer,Map<String,Patch>> sectors=new TreeMap<>();
        for(var sample:samples) {
            var forms=sectors.computeIfAbsent(sample.sector,k->new HashMap<>());
            forms.computeIfAbsent(sample.form,k->new Patch(sample.hit)).observe(sample.hit,sample.distance);
        }
        List<String> descriptions=new ArrayList<>();
        for(var sector:sectors.entrySet()) {
            var forms=sector.getValue().entrySet().stream().filter(e->e.getValue().spread)
                    .sorted(Comparator.<Map.Entry<String,Patch>>comparingDouble(e->e.getValue().nearest)
                            .thenComparing(Map.Entry::getKey)).toList();
            if(forms.isEmpty()) continue;
            List<String> features=new ArrayList<>();
            double last=0;
            for(var e:forms) {
                String label=e.getKey();
                if(label.equals("草地") && e.getValue().level) label="较平坦的草地";
                if(label.equals("水面") && e.getValue().horizontalSpan>=8
                        && forms.stream().anyMatch(other -> !other.getKey().equals("水面")
                        && other.getValue().nearest>e.getValue().nearest+8)) label="水面，远侧可见陆地";
                else if(label.equals("树冠")) label="成片树冠";
                features.add((last>0 && e.getValue().nearest>last+12?"更远处有":"")+label);
                last=e.getValue().nearest;
            }
            descriptions.add(DIRECTIONS[sector.getKey()]+"方："+String.join("、",features));
        }
        return descriptions.isEmpty()?"远景：尚无足够可见轮廓。":"近几秒目击的远景："+String.join("；",descriptions);
    }
}
