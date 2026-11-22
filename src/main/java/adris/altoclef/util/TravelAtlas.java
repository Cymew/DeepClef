package adris.altoclef.util;

import adris.altoclef.AltoClef;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import java.util.*;

final class TravelAtlas {
    private Object world;
    private long tile=Long.MIN_VALUE;
    private Map<Long,Integer> visits=Map.of();
    String local="";
    private String previous="";
    private static long key(int x,int z){return ((long)x<<32) | (z&0xffffffffL);}
    void reset(){world=null;tile=Long.MIN_VALUE;previous="";visits=Map.of();local="";}
    boolean newTile(AltoClef mod) {
        var p=mod.getPlayer().blockPosition();
        return world!=mod.level() || tile!=key(p.getX()>>4,p.getZ()>>4);
    }
    int cost(BlockPos origin,BlockPos dest){
        double dx=dest.getX()-origin.getX(), dz=dest.getZ()-origin.getZ(),len=Math.hypot(dx,dz);
        if(len<1)return Integer.MAX_VALUE;
        int cost=0;
        for(int r=16;r<=48;r+=16){
            int x=(int)Math.floor(origin.getX()+r*dx/len), z=(int)Math.floor(origin.getZ()+r*dz/len);
            cost+=visits.getOrDefault(key(x>>4,z>>4),0);
        }
        return cost;
    }
    void observe(AltoClef mod,List<BlockHitResult> hits){
        if(mod.memory().scope()==null)return;
        if(world!=mod.level()){reset();world=mod.level();}
        var pos=mod.getPlayer().blockPosition();
        int x=pos.getX()>>4,z=pos.getZ()>>4;
        var dimension=mod.level().dimension();
        var pins=new TreeMap<String,String>();
        var spawn=mod.level().getRespawnData();
        if(spawn.dimension()==dimension)pins.put("世界出生点",spawn.pos().toShortString());
        BlockPos terracotta=null;
        boolean sandstone=false;
        BlockPos lowerStone=null;
        boolean rockCeiling=false;
        var eye=mod.getPlayer().getEyePosition();
        for(var hit:hits){
            var at=hit.getBlockPos();
            String id=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mod.level().getBlockState(at).getBlock()).getPath();
            String label=switch(id){
                case "bell" -> "村庄线索（钟）";
                case "trial_spawner" -> "试炼密室线索";
                case "reinforced_deepslate" -> "远古城市线索";
                default -> "";
            };
            if(!label.isEmpty()) pins.putIfAbsent(label,at.toShortString());
            if(id.equals("stone") || id.equals("deepslate")) {
                if(hit.getDirection()==net.minecraft.core.Direction.DOWN && at.getY()>eye.y) rockCeiling=true;
                if(hit.getDirection()==net.minecraft.core.Direction.UP && at.getY()<eye.y-2) lowerStone=at;
            }
            if(id.equals("chiseled_sandstone"))sandstone=true;
            if(id.equals("orange_terracotta") || id.equals("blue_terracotta"))terracotta=at;
        }
        if(rockCeiling && lowerStone!=null && !mod.level().canSeeSky(lowerStone.above()))
            pins.put("地下岩腔线索（疑似矿洞）",lowerStone.toShortString());
        if(sandstone && terracotta!=null)pins.put("疑似沙漠神殿（雕纹砂岩和彩陶）",terracotta.toShortString());
        local="所见地标 "+pins+"；当前区域既往到访 "+visits.getOrDefault(key(x,z),0);
        String seen=SceneObservation.nearSummary(mod,hits)+" 地标 "+pins;
        boolean entered=tile!=key(x,z);
        if(!entered && previous.equals(seen))return;
        previous=seen;tile=key(x,z);
        var current=world;
        mod.memory().query(db->{String dim=dimension.identifier().toString();
            db.observeMap(dim,x,z,pos.toShortString(),seen,entered,pins);
            return db.nearbyVisits(dim,x,z);
        },Map.<Long,Integer>of(),counts->{if(world==current && tile==key(x,z))visits=counts;});
    }
}
