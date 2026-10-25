package adris.altoclef.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * For crafting table/inventory recipe book crafting, we need to figure out the
 * vanilla recipe display id given an altoclef recipe.
 * <p>
 * In 26.3 the client-side recipe registry no longer exposes the full recipe
 * list as RecipeHolders; recipes are mirrored as RecipeDisplayEntry objects in
 * the player's ClientRecipeBook collections. We match entries by their
 * craftingRequirements ingredients against the altoclef recipe slots and hand
 * back the RecipeDisplayId needed by
 * MultiPlayerGameMode#handlePlaceRecipe(int, RecipeDisplayId, boolean).
 */
public class JankCraftingRecipeMapping {

    public static Optional<RecipeDisplayId> getMinecraftMappedRecipe(CraftingRecipe recipe, Item output) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.level == null) {
            return Optional.empty();
        }
        return getMinecraftMappedRecipe(recipe, output, client.player.getRecipeBook().getCollections(),
                SlotDisplayContext.fromLevel(client.level));
    }

    /** The same lookup over actual synced entries, usable without a live client. */
    public static Optional<RecipeDisplayId> getMinecraftMappedRecipe(CraftingRecipe recipe, Item output,
            Iterable<RecipeCollection> collections, ContextMap context) {
        for (RecipeCollection collection : collections) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                Optional<List<Ingredient>> requirements = entry.craftingRequirements();
                if (requirements.isEmpty() || !matchesIngredients(recipe, requirements.get())) {
                    continue;
                }
                // Ingredients alone can match another output, such as planks
                // used by both sticks and pressure plates.
                try {
                    for (var stack : entry.display().result().resolveForStacks(context)) {
                        if (stack.getItem() == output) {
                            return Optional.of(entry.id());
                        }
                    }
                } catch (Exception e) {
                    // Context-dependent display we cannot resolve; skip it.
                }
            }
        }
        return Optional.empty();
    }

    /** Counts repeated ingredients and permits alternative ingredients in any order. */
    public static boolean matchesIngredients(CraftingRecipe recipe, List<Ingredient> requirements) {
        List<ItemTarget> wanted = Arrays.stream(recipe.getSlots())
                .filter(target -> target != null && !target.isEmpty()).toList();
        if (requirements.size() != wanted.size()) return false;
        boolean[][] matches = new boolean[wanted.size()][requirements.size()];
        for (int target = 0; target < wanted.size(); target++) {
            ItemTarget accepted = wanted.get(target);
            for (int ingredient = 0; ingredient < requirements.size(); ingredient++)
                matches[target][ingredient] = requirements.get(ingredient).items().anyMatch(item -> accepted.matches(item.value()));
        }
        int[] assigned = new int[requirements.size()];
        Arrays.fill(assigned, -1);
        for (int target = 0; target < wanted.size(); target++)
            if (!assign(target, matches, assigned, new boolean[requirements.size()])) return false;
        return true;
    }

    private static boolean assign(int target, boolean[][] matches, int[] assigned, boolean[] visited) {
        for (int ingredient = 0; ingredient < assigned.length; ingredient++) {
            if (!matches[target][ingredient] || visited[ingredient]) continue;
            visited[ingredient] = true;
            if (assigned[ingredient] < 0 || assign(assigned[ingredient], matches, assigned, visited)) {
                assigned[ingredient] = target;
                return true;
            }
        }
        return false;
    }
}
