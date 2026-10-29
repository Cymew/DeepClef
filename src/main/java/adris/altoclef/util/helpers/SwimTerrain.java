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
public final class SwimTerrain implements SwimRoutePlanner.Terrain {
    private final AltoClef mod;
    private final double height;
    private final Map<BlockPos,Kind> cache=new HashMap<>();
    public SwimTerrain(AltoClef mod) { this.mod=mod; height=mod.getPlayer().isSwimming() ? .6 : mod.getPlayer().getBbHeight(); }
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
        if (!besideWater) return Kind.BLOCKED;
        if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) return breathableAt(point) ? Kind.AIR : Kind.WATER;
        if (mod.level().getBlockState(p.below()).isFaceSturdy(mod.level(),p.below(),net.minecraft.core.Direction.UP)
                && clearBox(new AABB(point.x-.31,p.getY(),point.z-.31,point.x+.31,p.getY()+1.8,point.z+.31)))
            return Kind.SHORE;
        // An air cell must have water support; free space above a cliff is not a swimming node.
        return water(p.below()) ? (breathableAt(point) ? Kind.AIR : Kind.WATER) : Kind.BLOCKED;
    }
    public boolean clearBody(Vec3 p) { return clearBox(new AABB(p.x-.31,p.y+.01,p.z-.31,p.x+.31,p.y+height,p.z+.31)); }
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
