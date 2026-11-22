package adris.altoclef.util;

import adris.altoclef.AltoClef;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import java.util.*;

/** Historical milestones plus visible, server-synced advancement hints for the current chapter. */
final class PlayerJourney {
    enum Chapter {
        IRON("获得铁", "story/smelt_iron", "从木镐、圆石和石镐推进到原铁与熔炼"),
        NETHER("进入下界", "story/enter_the_nether", "备好铁镐、铁剑和桶，利用已有门或用水桶浇筑下界门"),
        END("进入末地", "story/enter_the_end", "到下界要塞取烈焰棒，补足末影珍珠，返回主世界后用末影之眼定位要塞并开启末地门"),
        DRAGON("击败末影龙", "end/kill_dragon", "准备弓箭、方块和食物，处理末地水晶和末影龙"),
        WINGS("获得鞘翅", "end/elytra", "寻找末地折跃门、末地城与末地船"),
        BEACON("获得信标", "nether/create_beacon", "准备应对凋灵，取得下界之星、黑曜石与玻璃并制作信标");
        final String title, advancement, guide;
        Chapter(String title, String advancement, String guide) {
            this.title = title; this.advancement = advancement; this.guide = guide;
        }
    }

    private final Set<String> earned = new LinkedHashSet<>();
    static final int TARGET_EYES = 14;
    private boolean inNether;
    List<String> history() { return List.copyOf(earned); }
    void restore(List<String> past) { if (past != null) earned.addAll(past); }
    void mark(String key) { earned.add(key); }
    boolean has(String key) { return earned.contains(key); }
    Chapter next() {
        for (var stage : Chapter.values()) if (!has(stage.name())) return stage;
        return null;
    }

    void observe(Set<String> done, Map<String,Integer> inventory, boolean nether, boolean end, boolean wearingWings) {
        inNether = nether;
        if (inventory.getOrDefault("minecraft:ender_eye", 0) >= TARGET_EYES) mark("route:eyes_ready");
        if (nether || end || inventory.containsKey("minecraft:iron_pickaxe")) mark(Chapter.IRON.name());
        if (end) mark(Chapter.NETHER.name());
        for (var stage : Chapter.values()) if (done.contains("minecraft:" + stage.advancement)) mark(stage.name());
        if (inventory.getOrDefault("minecraft:iron_ingot", 0) > 0) mark(Chapter.IRON.name());
        if (nether) mark(Chapter.NETHER.name());
        if (end) mark(Chapter.END.name());
        if (wearingWings || inventory.containsKey("minecraft:elytra")) mark(Chapter.WINGS.name());
        if (inventory.containsKey("minecraft:beacon")) mark(Chapter.BEACON.name());
    }

    void observe(AltoClef mod, Map<String,Integer> stock) {
        var p = mod.getPlayer();
        var advancements = p.connection.getAdvancements();
        Set<String> done = new HashSet<>();
        advancements.progress().forEach((holder, progress) -> {
            if (progress.isDone() && !holder.id().getPath().startsWith("recipes/")) done.add(holder.id().toString());
        });
        observe(done, stock, mod.level().dimension() == Level.NETHER, mod.level().dimension() == Level.END,
                p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(Items.ELYTRA));
    }

    String describe() {
        var stage = next();
        if (stage == null) return "六项生存里程碑已达成，可自选建设或探索项目";
        return stage.title + "（" + stage.guide + "）";
    }

    PlayerProject.Milestone preparation(Map<String,Integer> stock) {
        var stage = next();
        if (stage == Chapter.IRON) {
            if (stock.containsKey("minecraft:raw_iron")) return new PlayerProject.Milestone("minecraft:iron_ingot", 1);
            if (!stock.containsKey("minecraft:stone_pickaxe") && !stock.containsKey("minecraft:iron_pickaxe")
                    && !stock.containsKey("minecraft:diamond_pickaxe") && !stock.containsKey("minecraft:netherite_pickaxe")) {
                if (!stock.containsKey("minecraft:wooden_pickaxe") && stock.getOrDefault("minecraft:cobblestone",0) < 3)
                    return new PlayerProject.Milestone("minecraft:wooden_pickaxe", 1);
                return new PlayerProject.Milestone("minecraft:stone_pickaxe", 1);
            }
            return new PlayerProject.Milestone("minecraft:iron_ingot", 1);
        }
        if (stage == Chapter.NETHER) {
            for (String tool : List.of("pickaxe", "sword"))
                if (List.of("iron_", "diamond_", "netherite_").stream().noneMatch(tier -> stock.containsKey("minecraft:" + tier + tool)))
                    return new PlayerProject.Milestone("minecraft:iron_" + tool, 1);
            if (!stock.containsKey("minecraft:bucket") && !stock.containsKey("minecraft:water_bucket"))
                return new PlayerProject.Milestone("minecraft:bucket", 1);
            if (!stock.containsKey("minecraft:flint_and_steel")) return new PlayerProject.Milestone("minecraft:flint_and_steel", 1);
        }
        if (stage == Chapter.END) {
            int eyes = stock.getOrDefault("minecraft:ender_eye", 0);
            if (has("route:eyes_ready") && eyes > 0) return null;
            int missing = TARGET_EYES - eyes;
            int rods = (missing - stock.getOrDefault("minecraft:blaze_powder", 0) + 1) / 2;
            if (stock.getOrDefault("minecraft:blaze_rod", 0) < rods)
                return new PlayerProject.Milestone("minecraft:blaze_rod", rods);
            if (stock.getOrDefault("minecraft:ender_pearl", 0) < missing)
                return new PlayerProject.Milestone("minecraft:ender_pearl", missing);
            if (missing > 0 && !inNether)
                return new PlayerProject.Milestone("minecraft:ender_eye", TARGET_EYES);
        }
        if (stage == Chapter.BEACON && stock.containsKey("minecraft:nether_star"))
            return new PlayerProject.Milestone("minecraft:beacon", 1);
        return null;
    }
}
