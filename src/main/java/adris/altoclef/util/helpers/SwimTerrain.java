package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.HashMap;
import java.util.Map;
import static adris.altoclef.util.helpers.SwimRoutePlanner.Kind;

/** Loaded blocks only, with full body sweeps rather than point/eye ray navigation. */
public final class SwimTerrain implements SwimRoutePlanner.Terrain, ShoreCrossing.Terrain {
    private final AltoClef mod;
    private final double height;
    private final BlockPos origin;
    private final Map<BlockPos,Kind> cache=new HashMap<>();
    public SwimTerrain(AltoClef mod) { this.mod=mod; origin=mod.getPlayer().blockPosition(); height=mod.getPlayer().isSwimming() ? .6 : mod.getPlayer().getBbHeight(); }
    /** A bounded entry probe; finding water does not bypass the route's collision checks. */
    public boolean hasNearbyWater(BlockPos centre) {
        for(int r=0;r<=8;r++)for(int dx=-r;dx<=r;dx++)for(int dz=-r;dz<=r;dz++) {
            if(Math.max(Math.abs(dx),Math.abs(dz))!=r)continue;
            for(int dy=-3;dy<=3;dy++) {
                BlockPos p=centre.offset(dx,dy,dz);
                if(water(p) && clearBody(point(p)))return true;
            }
        }
        return false;
    }
    public boolean loaded(BlockPos p) { return p.getY()>=mod.level().getMinY() && p.getY()<mod.level().getMaxY()
            && mod.level().hasChunkAt(p); }
    public boolean water(BlockPos p) { return loaded(p) && mod.level().getFluidState(p).is(FluidTags.WATER); }
    private boolean dry(BlockPos p) { return loaded(p) && mod.level().getFluidState(p).isEmpty(); }
    private boolean breathableAt(Vec3 p) {
        Vec3 eye=p.add(0,mod.getPlayer().getEyeHeight(),0);
        BlockPos cell=BlockPos.containing(eye);
        if(!loaded(cell))return false;
        if(mod.level().getBlockState(cell).is(Blocks.BUBBLE_COLUMN))return true;
        var fluid=mod.level().getFluidState(cell);
        return fluid.isEmpty() || fluid.is(FluidTags.WATER) && eye.y>=cell.getY()+fluid.getHeight(mod.level(),cell);
    }
    @Override public Vec3 point(BlockPos p) {
        boolean surface=water(p) && dry(p.above());
        return new Vec3(p.getX()+.5,p.getY()+(surface ? .55 : .08),p.getZ()+.5);
    }
    @Override public Kind kind(BlockPos p) { return cache.computeIfAbsent(p.immutable(),this::classify); }
    private Kind classify(BlockPos p) {
        if (!loaded(p) || !loaded(p.above()) || !loaded(p.below())) return Kind.BLOCKED;
        Vec3 point=point(p);
        if (!clearBody(point)) return Kind.BLOCKED;
        var state=mod.level().getBlockState(p);
        if (state.is(Blocks.BUBBLE_COLUMN) && breathableAt(point)) return state.getValue(BubbleColumnBlock.DRAG_DOWN) ? Kind.DOWN : Kind.UP;
        if (water(p)) return dry(p.above()) && breathableAt(point) && clearBody(point.add(0,.35,0)) ? Kind.SURFACE : Kind.WATER;
        if (!dry(p)) return Kind.BLOCKED;
        boolean besideWater=water(p.below()) || water(p.north()) || water(p.south()) || water(p.east()) || water(p.west());
        if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) return breathableAt(point) ? Kind.AIR : Kind.WATER;
        boolean localEntry=Math.abs(p.getX()-origin.getX())<=8 && Math.abs(p.getZ()-origin.getZ())<=8
                && Math.abs(p.getY()-origin.getY())<=3;
        if (!besideWater && !localEntry) return Kind.BLOCKED;
        if (mod.level().getBlockState(p.below()).isFaceSturdy(mod.level(),p.below(),net.minecraft.core.Direction.UP)
                && clearBox(new AABB(point.x-.31,p.getY(),point.z-.31,point.x+.31,p.getY()+1.8,point.z+.31)))
            return breathableAt(point) ? Kind.SHORE : Kind.WATER;
        // An air cell must have water support; free space above a cliff is not a swimming node.
        return water(p.below()) ? (breathableAt(point) ? Kind.AIR : Kind.WATER) : Kind.BLOCKED;
    }
    public boolean clearBody(Vec3 p) { return clearBox(new AABB(p.x-.31,p.y+.01,p.z-.31,p.x+.31,p.y+height,p.z+.31)); }
    @Override public Vec3 waterSurface(int x,int z,double feetY) {
        int y=(int)Math.floor(feetY);
        // A standing player's feet can briefly be two cells below the top during sprint entry.
        int top = mod.getPlayer().isEyeInFluid(FluidTags.WATER) ? 2 : 1;
        for(int dy=top;dy>=-2;dy--) {
            BlockPos cell=new BlockPos(x,y+dy,z);
            if(!water(cell) || !dry(cell.above())) continue;
            if(dy==2 && !(water(cell.below()) && water(cell.below(2)))) continue;
            Vec3 point=point(cell);
            if(clearBody(point) && breathableAt(point)) return point;
        }
        return null;
    }
    @Override public boolean deep(Vec3 waterPoint) {
        BlockPos below=BlockPos.containing(waterPoint).below();
        return below.getY()>=origin.getY()-2 && water(below);
    }
    @Override public Vec3 landing(int x,int z,double feetY) {
        for(int y=(int)Math.floor(feetY+1.5);y>=Math.floor(feetY)-1;y--) {
            BlockPos cell=new BlockPos(x,y,z);
            if(!loaded(cell) || !dry(cell)) continue;
            var shape=mod.level().getBlockState(cell).getCollisionShape(mod.level(),cell);
            if(shape.isEmpty()) continue;
            double top=shape.max(net.minecraft.core.Direction.Axis.Y,.5,.5)+y;
            if(!Double.isFinite(top) || top<feetY-.65 || top>feetY+1.65) continue;
            Vec3 feet=new Vec3(x+.5,top+.005,z+.5);
            if(dry(BlockPos.containing(feet)) && clearBox(new AABB(feet.x-.31,feet.y,feet.z-.31,
                    feet.x+.31,feet.y+1.8,feet.z+.31))) return feet;
        }
        return null;
    }
    @Override public boolean swept(Vec3 start,Vec3 end) {
        int pieces=Math.max(1,(int)Math.ceil(start.distanceTo(end)/.25));
        for(int i=0;i<pieces;i++) {
            Vec3 a=start.lerp(end,(double)i/pieces), b=start.lerp(end,(double)(i+1)/pieces);
            if(!clearBox(new AABB(a.x-.31,a.y+.01,a.z-.31,a.x+.31,a.y+height,a.z+.31).expandTowards(b.subtract(a))))
                return false;
        }
        return true;
    }
    private boolean clearBox(AABB box) {
        if (!mod.level().getWorldBorder().isWithinBounds(box)) return false;
        for (BlockPos p:BlockPos.betweenClosed(BlockPos.containing(box.minX,box.minY,box.minZ),
                BlockPos.containing(box.maxX,box.maxY,box.maxZ))) {
            if (!loaded(p) || mod.level().getFluidState(p).is(FluidTags.LAVA)) return false;
            var s=mod.level().getBlockState(p);
            if (s.is(Blocks.CACTUS) || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE)
                    || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.WITHER_ROSE)) return false;
        }
        return mod.level().noCollision(mod.getPlayer(),box);
    }
    @Override public boolean clear(Vec3 a, Vec3 b) {
        double length=a.distanceTo(b); if(length>9) return false;
        int n=Math.max(1,(int)Math.ceil(length/.2));
        for(int i=0;i<=n;i++) {
            Vec3 p=a.lerp(b,(double)i/n);
            // Each short swept box contains the complete interpolated body.
            Vec3 next=a.lerp(b,(double)Math.min(n,i+1)/n);
            AABB box=new AABB(p.x-.31,p.y+.01,p.z-.31,p.x+.31,p.y+height,p.z+.31)
                    .expandTowards(next.subtract(p));
            if(!clearBox(box))return false;
            if(i>0 && kindAtMovement(p)==Kind.BLOCKED)return false;
        }
        return true;
    }
    private Kind kindAtMovement(Vec3 p) {
        BlockPos pos=BlockPos.containing(p);
        return water(pos) || water(pos.below()) || kind(pos)!=Kind.BLOCKED ? Kind.WATER : Kind.BLOCKED;
    }
}
