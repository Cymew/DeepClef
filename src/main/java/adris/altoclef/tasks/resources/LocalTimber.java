package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.HomeArea;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

/** Counts new inventory only. The origin stays fixed while the player moves. */
public final class LocalTimber extends Task {
    private final Block log;
    private final Item item;
    private final int quantity, radius, distance;
    private final Set<BlockPos> verified = new HashSet<>(), failed = new HashSet<>();
    private BlockPos origin, target;
    private int initial, targetTick, idle;
    private boolean done;
    private String receipt = "采伐中断，需按实际库存核对";
    public String receipt() { return receipt; }
    private Object level;
    public LocalTimber(Item item, int quantity, int radius, int distance) {
        this.item = item; this.log = Block.byItem(item); this.quantity = quantity; this.radius = radius; this.distance = distance;
    }
    public static boolean rawLog(Item item) {
        String key = BuiltInRegistries.ITEM.getKey(item).getPath();
        return key.endsWith("_log") && !key.startsWith("stripped_");
    }
    // Require a rooted column and a naturally generated canopy of the same species.
    // Remember its verified trunk so removing the lowest block does not invalidate the rest.
    static boolean natural(AltoClef mod, BlockPos pos, Set<BlockPos> verified) {
        if (!mod.level().hasChunkAt(pos)) return false;
        var state = mod.level().getBlockState(pos);
        if (!state.is(BlockTags.LOGS)) return true;
        if (!rawLog(state.getBlock().asItem())) return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().endsWith("_stem");
        if (verified.contains(pos)) return true;
        var block = state.getBlock();
        BlockPos base = pos, top = pos;
        while (pos.getY()-base.getY()<40 && mod.level().getBlockState(base.below()).is(block)) base=base.below();
        if (!mod.level().getBlockState(base.below()).is(BlockTags.DIRT)) return false;
        while (top.getY()-base.getY()<40 && mod.level().getBlockState(top.above()).is(block)) top=top.above();
        if (top.getY()-base.getY()<2) return false;
        String leaves = BuiltInRegistries.BLOCK.getKey(block).getPath().replace("_log","_leaves");
        for (var p : BlockPos.betweenClosed(top.offset(-2,-1,-2),top.offset(2,3,2))) {
            if (!mod.level().hasChunkAt(p)) continue;
            var leaf = mod.level().getBlockState(p);
            if (leaf.hasProperty(BlockStateProperties.PERSISTENT) && !leaf.getValue(BlockStateProperties.PERSISTENT)
                    && BuiltInRegistries.BLOCK.getKey(leaf.getBlock()).getPath().equals(leaves)) {
                for (int y=base.getY(); y<=top.getY(); y++) verified.add(new BlockPos(pos.getX(),y,pos.getZ()));
                return true;
            }
        }
        return false;
    }
    private boolean allowed(AltoClef mod, BlockPos pos) {
        var home = HomeArea.current();
        return (distance == 0 || home != null && home.atLeast(pos,distance)) && within(origin,pos,radius) && !HomeArea.protectedAt(pos) && !mod.getModSettings().isPositionExplicitlyProtected(pos);
    }
    public static boolean within(BlockPos origin, BlockPos pos, int radius) {
        double x = (double)pos.getX()-origin.getX(), z = (double)pos.getZ()-origin.getZ();
        return x*x+z*z <= radius*radius;
    }
    @Override protected void onStart(AltoClef mod) {
        level=mod.level(); origin=mod.getPlayer().blockPosition(); initial=mod.getPlayer().getInventory().countItem(item);
        mod.getBlockTracker().trackBlock(log);
        mod.getBehaviour().push();
        mod.getBehaviour().avoidBlockBreaking(pos -> !pos.equals(target) || !allowed(mod,pos));
    }
    @Override protected Task onTick(AltoClef mod) {
        int gain=mod.getPlayer().getInventory().countItem(item)-initial;
        if (level!=mod.level() || gain>=quantity) return end(mod,gain);
        int tick=mod.getPlayer().tickCount;
        if (target!=null && !mod.level().getBlockState(target).is(log)) target=null;
        if (target!=null && tick-targetTick>240) { failed.add(target); target=null; }
        if (target!=null && allowed(mod,target)) return new DestroyBlockTask(target);
        target=null;
        var drop=mod.level().getEntitiesOfClass(ItemEntity.class,new AABB(mod.getPlayer().blockPosition()).inflate(16),
                e -> e.isAlive() && e.getItem().is(item) && allowed(mod,e.blockPosition())
                        && !failed.contains(e.blockPosition())).stream().min(Comparator.comparingDouble(mod.getPlayer()::distanceToSqr));
        if (drop.isPresent()) {
            if (++idle>240) { failed.add(drop.get().blockPosition()); idle=0; }
            else return new GetToBlockTask(drop.get().blockPosition());
        }
        var found=mod.getBlockTracker().getNearestTracking(pos -> allowed(mod,pos) && !failed.contains(pos)
                && mod.level().hasChunkAt(pos) && mod.level().getBlockState(pos).is(log)
                && natural(mod,pos,verified),log);
        if (found.isPresent()) {
            target=found.get(); targetTick=tick; idle=0;
            return new DestroyBlockTask(target);
        }
        return ++idle>100 ? end(mod,gain) : null;
    }
    private Task end(AltoClef mod,int gain) {
        done=true;
        receipt="本区采伐新增 "+gain+"/"+quantity+(gain>=quantity?"，已达标":"，尚未达标，请选择另一处符合要求的林地");
        mod.log(receipt);
        return null;
    }
    @Override public boolean isFinished(AltoClef mod) { return done; }
    @Override protected void onStop(AltoClef mod, Task interrupt) {
        mod.getBehaviour().pop();
        mod.getBlockTracker().stopTracking(log);
    }
    @Override protected boolean isEqual(Task other) { return this==other; }
    @Override protected String toDebugString() { return "本区采伐 "+item+" × "+quantity; }
}
