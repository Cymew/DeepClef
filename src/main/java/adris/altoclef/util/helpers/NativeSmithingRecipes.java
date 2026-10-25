package adris.altoclef.util.helpers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;
import net.minecraft.world.item.crafting.TransmuteRecipe;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Vanilla recipe metadata and native result assembly; the server's real menu result remains authoritative. */
public final class NativeSmithingRecipes {
    private static final String[] UPGRADE_NAMES = {"axe", "boots", "chestplate", "helmet", "hoe", "horse_armor",
            "leggings", "nautilus_armor", "pickaxe", "shovel", "spear", "sword"};
    private static final Map<Item, Item> UPGRADES = loadUpgrades();
    private static final Set<Item> TRIM_BASES = loadTag("trimmable_armor", new LinkedHashSet<>());
    private static final Set<Item> TRIM_MATERIALS = loadTag("trim_materials", new LinkedHashSet<>());
    private static final Set<Item> UPGRADE_MATERIALS = loadTag("netherite_tool_materials", new LinkedHashSet<>());

    private NativeSmithingRecipes() { }

    public static Map<Item, Item> upgrades() { return UPGRADES; }
    public static Item upgradeOutput(Item base) { return UPGRADES.get(base); }
    public static Set<Item> trimBases() { return TRIM_BASES; }
    public static Set<Item> trimMaterials() { return TRIM_MATERIALS; }
    public static boolean isTrimBase(Item base) {
        return base != null && (TRIM_BASES.contains(base) || base.builtInRegistryHolder().is(ItemTags.TRIMMABLE_ARMOR));
    }
    public static boolean isTrimBase(ItemStack base) {
        return base != null && !base.isEmpty() && isTrimBase(base.getItem());
    }
    public static boolean isTrimTemplate(Item template) {
        var entry = SmithingTemplateCatalogue.byItem(template);
        return entry != null && !entry.upgrade();
    }
    public static Identifier trimPattern(Item template) {
        var entry = SmithingTemplateCatalogue.byItem(template);
        return entry == null || entry.upgrade() ? null : Identifier.withDefaultNamespace(entry.key());
    }

    public static ItemStack preview(ItemStack base, Item template, ItemStack addition, HolderLookup.Provider registries) {
        if (base == null || addition == null || base.isEmpty() || addition.isEmpty() || template == null)
            return ItemStack.EMPTY;
        if (template == Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE) {
            Item result = upgradeOutput(base.getItem());
            if (result == null || !UPGRADE_MATERIALS.contains(addition.getItem())) return ItemStack.EMPTY;
            return TransmuteRecipe.createWithOriginalComponents(new ItemStackTemplate(result), base);
        }
        Identifier pattern = trimPattern(template);
        if (pattern == null || !isTrimBase(base) || registries == null) return ItemStack.EMPTY;
        var holder = registries.lookupOrThrow(Registries.TRIM_PATTERN).get(ResourceKey.create(Registries.TRIM_PATTERN, pattern));
        return holder.isEmpty() ? ItemStack.EMPTY : SmithingTrimRecipe.applyTrim(base, addition, holder.get());
    }

    private static Map<Item, Item> loadUpgrades() {
        Map<Item, Item> result = new LinkedHashMap<>();
        for (String suffix : UPGRADE_NAMES) {
            JsonObject recipe = read("recipe/netherite_" + suffix + "_smithing.json");
            if (!recipe.get("type").getAsString().equals("minecraft:smithing_transform")
                    || !recipe.get("template").getAsString().equals("minecraft:netherite_upgrade_smithing_template")
                    || !recipe.get("addition").getAsString().equals("#minecraft:netherite_tool_materials"))
                throw new IllegalStateException("Unsupported native smithing upgrade: " + suffix);
            result.put(item(recipe.get("base").getAsString()), item(recipe.getAsJsonObject("result").get("id").getAsString()));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Set<Item> loadTag(String path, Set<String> visited) {
        if (!visited.add(path)) return Set.of();
        Set<Item> result = new LinkedHashSet<>();
        for (JsonElement element : read("tags/item/" + path + ".json").getAsJsonArray("values")) {
            String id = element.isJsonPrimitive() ? element.getAsString() : element.getAsJsonObject().get("id").getAsString();
            if (id.startsWith("#minecraft:")) result.addAll(loadTag(id.substring(11), visited));
            else if (id.startsWith("minecraft:")) result.add(item(id));
        }
        return Collections.unmodifiableSet(result);
    }

    private static Item item(String id) {
        Item result = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
        if (result == null || result == Items.AIR) throw new IllegalStateException("Unknown native smithing item: " + id);
        return result;
    }

    private static JsonObject read(String relative) {
        String resource = "/data/minecraft/" + relative;
        try (var stream = NativeSmithingRecipes.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Missing native smithing data: " + resource);
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read native smithing data: " + resource, error);
        }
    }
}
