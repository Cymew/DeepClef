package adris.altoclef.util;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.entity.KillEntityTask;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.BowCombat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/** Only the actual, visible encounter can become a target for this part of the journey. */
final class JourneyEncounter extends SurvivalAction {
    private final Entity target;
    private boolean usingBow, recoveredFrame;
    private JourneyEncounter(String label, Entity target) {
        super(label);
        this.target = target;
    }

    static JourneyEncounter nearby(AltoClef mod, PlayerJourney.Chapter stage) {
        if (stage == null || stage == PlayerJourney.Chapter.IRON || stage == PlayerJourney.Chapter.NETHER) return null;
        var p = mod.getPlayer();
        if (p.getHealth() < 12 || p.getFoodData().getFoodLevel() < 8 || p.getAbilities().instabuild) return null;
        double range = Math.min(48, SceneObservation.range(net.minecraft.client.Minecraft.getInstance()));
        var entities = new java.util.ArrayList<>(mod.level().getEntitiesOfClass(Entity.class, p.getBoundingBox().inflate(range), e -> e.isAlive()
                && label(stage, e) != null && e.distanceToSqr(p) <= range * range));
        entities.sort(java.util.Comparator.comparingDouble(p::distanceToSqr));
        for (var e : entities) {
            if (e instanceof EndCrystal && (p.distanceToSqr(e) < 144 || !BowCombat.canUse(mod))) continue;
            if (VisibleEntityTargets.visiblePoint(mod, e, false) != null) return new JourneyEncounter(label(stage, e), e);
        }
        return null;
    }

    private static String label(PlayerJourney.Chapter stage, Entity e) {
        if (stage == PlayerJourney.Chapter.END && e instanceof Blaze) return "迎战目击的烈焰人以获取烈焰棒";
        if (stage == PlayerJourney.Chapter.DRAGON && e.level().dimension() == Level.END) {
            if (e instanceof EndCrystal) return "远射目击的末地水晶";
            if (e instanceof EnderDragon) return "迎战目击的末影龙";
        }
        if (stage == PlayerJourney.Chapter.WINGS && e.level().dimension() == Level.END && e instanceof ItemFrame frame
                && frame.getItem().is(Items.ELYTRA)) return "取下目击的展示框鞘翅";
        if (stage == PlayerJourney.Chapter.BEACON && e instanceof WitherBoss) return "迎战目击的凋灵以获取下界之星";
        return null;
    }

    @Override protected Task onTick(AltoClef mod) {
        var p = mod.getPlayer();
        if (recoveredFrame || !target.isAlive() || target.isRemoved()) return finish("交互已结束，以背包和原版进度核对结果");
        if (target.level() != mod.level() || p.getHealth() < 10 || VisibleEntityTargets.visiblePoint(mod, target, false) == null)
            return finish("目标离开视野或状态不足，重新评估");
        if (target instanceof ItemFrame frame) {
            if (!frame.getItem().is(Items.ELYTRA)) return finish("展示框内容已变化");
            if (target.distanceTo(p) > p.entityInteractionRange()) return new GetWithinRangeOfBlockTask(target.blockPosition(), 2, false);
            mod.getController().attack(p, target);
            recoveredFrame = true;
            return null;
        }
        if (target instanceof EndCrystal) {
            if (target.distanceToSqr(p) < 144 || !BowCombat.canUse(mod)) return finish("缺少安全射距或弓箭");
            usingBow = true;
            BowCombat.tick(mod, target);
            return null;
        }
        return new KillEntityTask(target);
    }

    @Override protected void onStop(AltoClef mod, Task interrupt) {
        if (usingBow) BowCombat.reset(mod);
    }
}
