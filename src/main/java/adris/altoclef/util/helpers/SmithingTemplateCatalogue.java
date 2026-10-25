package adris.altoclef.util.helpers;

import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.ItemTarget;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Locale;

/** Vanilla 26.3 template acquisition metadata; never reads a world's hidden loot or generation seed. */
public final class SmithingTemplateCatalogue {
    public enum SourceKind { CHEST, ARCHAEOLOGY, ELDER_GUARDIAN, VAULT, OMINOUS_VAULT }

    public record Entry(String key, Item item, Dimension dimension, SourceKind source,
                        List<String> structures, List<String> lootTables, Item[] duplicationMaterials,
                        Block[] markers, String sourceDescription) {
        public Entry {
            structures = List.copyOf(structures); lootTables = List.copyOf(lootTables);
            duplicationMaterials = duplicationMaterials.clone(); markers = markers.clone();
        }
        @Override public Item[] duplicationMaterials() { return duplicationMaterials.clone(); }
        @Override public Block[] markers() { return markers.clone(); }
        public boolean upgrade() { return item == Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE; }
        public String itemId() { return BuiltInRegistries.ITEM.getKey(item).toString(); }
    }

    private static final List<String> BASTION_LOOT = List.of("chests/bastion_treasure", "chests/bastion_other",
            "chests/bastion_bridge", "chests/bastion_hoglin_stable");
    private static final Block[] BASTION = {Blocks.GILDED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.BLACKSTONE};
    private static final Block[] TRAIL = {Blocks.SUSPICIOUS_GRAVEL, Blocks.TERRACOTTA, Blocks.MUD_BRICKS};
    private static final List<Entry> ENTRIES = List.of(
            entry("netherite_upgrade", Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, Items.NETHERRACK,
                    Dimension.NETHER, SourceKind.CHEST, List.of("bastion_remnant"), BASTION_LOOT, BASTION,
                    "堡垒遗迹宝藏箱必出1枚；其他三类箱子各10%。"),
            chest("sentry", Items.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE, Items.COBBLESTONE,
                    "pillager_outpost", List.of("chests/pillager_outpost"),
                    new Block[]{Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_PLANKS}, "掠夺者前哨站箱子25%，命中时2枚。"),
            chest("vex", Items.VEX_ARMOR_TRIM_SMITHING_TEMPLATE, Items.COBBLESTONE,
                    "mansion", List.of("chests/woodland_mansion"),
                    new Block[]{Blocks.DARK_OAK_PLANKS, Blocks.BOOKSHELF}, "林地府邸箱子50%。"),
            chest("wild", Items.WILD_ARMOR_TRIM_SMITHING_TEMPLATE, Items.MOSSY_COBBLESTONE,
                    "jungle_pyramid", List.of("chests/jungle_temple"),
                    new Block[]{Blocks.MOSSY_COBBLESTONE, Blocks.CHISELED_STONE_BRICKS}, "丛林神庙箱子约33.33%，命中时2枚。"),
            entry("coast", Items.COAST_ARMOR_TRIM_SMITHING_TEMPLATE, Items.COBBLESTONE, Dimension.OVERWORLD,
                    SourceKind.CHEST, List.of("shipwreck", "shipwreck_beached"),
                    List.of("chests/shipwreck_supply", "chests/shipwreck_map", "chests/shipwreck_treasure"),
                    new Block[]{Blocks.CHEST, Blocks.SPRUCE_PLANKS, Blocks.OAK_PLANKS}, "沉船三类箱子各约16.67%，命中时2枚。"),
            chest("dune", Items.DUNE_ARMOR_TRIM_SMITHING_TEMPLATE, Items.SANDSTONE,
                    "desert_pyramid", List.of("chests/desert_pyramid"),
                    new Block[]{Blocks.CHISELED_SANDSTONE, Blocks.CUT_SANDSTONE}, "沙漠神殿箱子约14.29%，命中时2枚。"),
            archaeology("wayfinder", Items.WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE),
            archaeology("raiser", Items.RAISER_ARMOR_TRIM_SMITHING_TEMPLATE),
            archaeology("shaper", Items.SHAPER_ARMOR_TRIM_SMITHING_TEMPLATE),
            archaeology("host", Items.HOST_ARMOR_TRIM_SMITHING_TEMPLATE),
            chest("ward", Items.WARD_ARMOR_TRIM_SMITHING_TEMPLATE, Items.COBBLED_DEEPSLATE,
                    "ancient_city", List.of("chests/ancient_city"),
                    new Block[]{Blocks.SCULK, Blocks.SCULK_SHRIEKER}, "远古城市箱子5%。"),
            chest("silence", Items.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE, Items.COBBLED_DEEPSLATE,
                    "ancient_city", List.of("chests/ancient_city"),
                    new Block[]{Blocks.SCULK, Blocks.SCULK_SHRIEKER}, "远古城市箱子1.25%。"),
            entry("tide", Items.TIDE_ARMOR_TRIM_SMITHING_TEMPLATE, Items.PRISMARINE, Dimension.OVERWORLD,
                    SourceKind.ELDER_GUARDIAN, List.of("monument"), List.of("entities/elder_guardian"),
                    new Block[]{Blocks.PRISMARINE, Blocks.DARK_PRISMARINE, Blocks.SEA_LANTERN},
                    "远古守卫者掉落20%；该模板池不受抢夺影响。"),
            entry("snout", Items.SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE, Items.BLACKSTONE, Dimension.NETHER,
                    SourceKind.CHEST, List.of("bastion_remnant"), BASTION_LOOT, BASTION,
                    "堡垒遗迹各类箱子约8.33%。"),
            entry("rib", Items.RIB_ARMOR_TRIM_SMITHING_TEMPLATE, Items.NETHERRACK, Dimension.NETHER,
                    SourceKind.CHEST, List.of("fortress"), List.of("chests/nether_bridge"),
                    new Block[]{Blocks.NETHER_BRICKS, Blocks.NETHER_BRICK_FENCE}, "下界要塞箱子约6.67%。"),
            chest("eye", Items.EYE_ARMOR_TRIM_SMITHING_TEMPLATE, Items.END_STONE,
                    "stronghold", List.of("chests/stronghold_library", "chests/stronghold_corridor"),
                    new Block[]{Blocks.STONE_BRICKS, Blocks.BOOKSHELF, Blocks.END_PORTAL_FRAME},
                    "要塞图书馆箱子必出；走廊箱子10%。"),
            entry("spire", Items.SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE, Items.PURPUR_BLOCK, Dimension.END,
                    SourceKind.CHEST, List.of("end_city"), List.of("chests/end_city_treasure"),
                    new Block[]{Blocks.PURPUR_BLOCK, Blocks.PURPUR_PILLAR, Blocks.END_STONE_BRICKS}, "末地城箱子约6.67%。"),
            new Entry("bolt", Items.BOLT_ARMOR_TRIM_SMITHING_TEMPLATE, Dimension.OVERWORLD, SourceKind.VAULT,
                    List.of("trial_chambers"), List.of("chests/trial_chambers/reward", "chests/trial_chambers/reward_unique"),
                    new Item[]{Items.COPPER_BLOCK.weathering().unaffected(), Items.COPPER_BLOCK.waxed().unaffected()},
                    new Block[]{Blocks.VAULT, Blocks.TRIAL_SPAWNER, Blocks.CHISELED_TUFF},
                    "用试炼钥匙开启普通宝库，约6.25%；展示物不等于实际奖励。"),
            entry("flow", Items.FLOW_ARMOR_TRIM_SMITHING_TEMPLATE, Items.BREEZE_ROD, Dimension.OVERWORLD,
                    SourceKind.OMINOUS_VAULT, List.of("trial_chambers"),
                    List.of("chests/trial_chambers/reward_ominous", "chests/trial_chambers/reward_ominous_unique"),
                    new Block[]{Blocks.VAULT, Blocks.TRIAL_SPAWNER, Blocks.CHISELED_TUFF},
                    "用不祥试炼钥匙开启不祥宝库，22.5%；展示物不等于实际奖励。")
    );

    private SmithingTemplateCatalogue() { }
    public static List<Entry> entries() { return ENTRIES; }
    public static Entry byItem(Item item) { return ENTRIES.stream().filter(entry -> entry.item() == item).findFirst().orElse(null); }
    public static Entry get(Item item) { return byItem(item); }
    public static boolean isTemplate(Item item) { return byItem(item) != null; }
    public static Entry find(String name) {
        if (name == null) return null;
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("minecraft:")) normalized = normalized.substring(10);
        for (Entry entry : ENTRIES) {
            String path = BuiltInRegistries.ITEM.getKey(entry.item()).getPath();
            if (normalized.equals(entry.key()) || normalized.equals(path)) return entry;
        }
        return null;
    }

    /** One seed + seven diamonds + matching material makes two, a net gain of exactly one seed. */
    public static CraftingRecipe duplicationRecipe(Entry entry) {
        if (entry == null) return null;
        ItemTarget diamond = new ItemTarget(Items.DIAMOND);
        return CraftingRecipe.newShapedRecipe("duplicate_" + entry.key(), new ItemTarget[]{
                diamond, new ItemTarget(entry.item()), diamond,
                diamond, new ItemTarget(entry.duplicationMaterials()), diamond,
                diamond, diamond, diamond}, 2);
    }

    private static Entry entry(String key, Item item, Item duplicate, Dimension dimension, SourceKind source,
                               List<String> structures, List<String> loot, Block[] markers, String description) {
        return new Entry(key, item, dimension, source, structures, loot, new Item[]{duplicate}, markers, description);
    }
    private static Entry chest(String key, Item item, Item duplicate, String structure, List<String> loot,
                               Block[] markers, String description) {
        return entry(key, item, duplicate, Dimension.OVERWORLD, SourceKind.CHEST,
                List.of(structure), loot, markers, description);
    }
    private static Entry archaeology(String key, Item item) {
        return entry(key, item, Items.TERRACOTTA, Dimension.OVERWORLD, SourceKind.ARCHAEOLOGY,
                List.of("trail_ruins"), List.of("archaeology/trail_ruins_rare"), TRAIL,
                "刷扫古迹废墟的可疑沙砾；稀有战利品表内约8.33%，不能挖掉可疑方块。");
    }
}
