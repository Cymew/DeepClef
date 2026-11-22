package adris.altoclef.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;

import java.io.IOException;
import java.io.Reader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Bundled standard crafting JSON is a manual hint; only the server knows the active recipe. */
final class BundledRecipeHints {
    record Hint(String source, String path, Identifier result, JsonObject json) {}
    private static CompletableFuture<List<Hint>> index;

    static synchronized CompletableFuture<List<Hint>> index(PackResources vanilla) {
        if (index == null || index.isCompletedExceptionally()) index = CompletableFuture.supplyAsync(() -> {
            var hints = new ArrayList<Hint>();
            readVanilla(vanilla, hints);
            for (var mod : FabricLoader.getInstance().getAllMods()) {
                var id = mod.getMetadata().getId();
                if (id.equals("minecraft")) continue;
                for (var root : mod.getRootPaths()) readMod(root, id, hints);
            }
            return List.copyOf(hints);
        });
        return index;
    }

    static void readVanilla(PackResources pack, List<Hint> hints) {
        pack.listResources(PackType.SERVER_DATA, "minecraft", "recipe", (id, file) -> {
            if (!id.getPath().endsWith(".json")) return;
            try (var reader = new InputStreamReader(file.get(), StandardCharsets.UTF_8)) {
                add(reader, "minecraft", id.toString(), hints);
            } catch (IOException | RuntimeException ignored) {
                // One non-standard or unreadable file does not invalidate the rest of the index.
            }
        });
    }

    static void readMod(Path root, String mod, List<Hint> hints) {
        Path data = root.resolve("data");
        if (!Files.isDirectory(data)) return;
        try (var namespaces = Files.list(data)) {
            for (Path namespace : namespaces.toList()) {
                Path recipes = namespace.resolve("recipe");
                if (!Files.isDirectory(recipes)) continue;
                try (var files = Files.walk(recipes)) {
                    files.filter(p -> p.getFileName().toString().endsWith(".json") && Files.isRegularFile(p)).forEach(p -> {
                        try (var reader = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                            add(reader, mod, namespace.getFileName() + ":recipe/" + recipes.relativize(p).toString().replace('\\', '/'), hints);
                        } catch (IOException | RuntimeException ignored) { }
                    });
                }
            }
        } catch (IOException | java.io.UncheckedIOException ignored) { }
    }

    private static void add(Reader reader, String source, String path, List<Hint> hints) {
        var json = JsonParser.parseReader(reader).getAsJsonObject();
        if (!json.has("type") || !json.has("result")) return;
        String type = json.get("type").getAsString();
        if (!Set.of("minecraft:crafting_shaped", "minecraft:crafting_shapeless", "crafting_shaped", "crafting_shapeless").contains(type)) return;
        var result = json.get("result");
        if (result.isJsonObject()) result = result.getAsJsonObject().get("id");
        if (result == null || !result.isJsonPrimitive()) return;
        var id = Identifier.tryParse(result.getAsString());
        if (id != null) hints.add(new Hint(source, path, id, json));
    }

    static String lookup(List<Hint> hints, String query, int page, HolderLookup.Provider registries, ContextMap context) {
        String term = query.strip().toLowerCase(Locale.ROOT);
        var exactId = Identifier.tryParse(term);
        boolean exactItem = exactId != null && BuiltInRegistries.ITEM.containsKey(exactId);
        var ranks = new HashMap<Identifier, Integer>();
        for (var hint : hints) ranks.computeIfAbsent(hint.result, id -> {
            if (exactItem) return id.equals(exactId) ? 0 : 2;
            String key = id.toString();
            if (key.equals(term) || id.getPath().equals(term)) return 0;
            var item = BuiltInRegistries.ITEM.getOptional(id);
            if (item.isEmpty()) return 2;
            String name = item.get().getDefaultInstance().getHoverName().getString().toLowerCase(Locale.ROOT);
            if (name.equals(term)) return 0;
            return key.contains(term) || name.contains(term) ? 1 : 2;
        });
        var ordered = hints.stream().filter(h -> ranks.get(h.result) < 2)
                .sorted(Comparator.comparingInt((Hint h) -> ranks.get(h.result)).thenComparing(Hint::source).thenComparing(Hint::path));
        var codec = registries.createSerializationContext(JsonOps.INSTANCE);
        var out = new StringBuilder();
        long skip = (page - 1L) * 4;
        long found = 0;
        for (var it = ordered.iterator(); it.hasNext();) {
            var hint = it.next();
            var parsed = Recipe.DIRECT_CODEC.parse(codec, hint.json).result();
            if (parsed.isEmpty()) continue;
            for (var display : parsed.get().display()) {
                if (!(display instanceof ShapedCraftingRecipeDisplay || display instanceof ShapelessCraftingRecipeDisplay)) continue;
                if (found++ < skip) continue;
                if (found - skip > 4) return out.append("\n还有候选，可用 by=manual 翻下一页").toString();
                if (out.isEmpty()) out.append("本机配方资料 · 手摆候选（当前世界可能覆盖）。使用 menu 和 slot_click，按实际输出取物；这些候选没有 recipe_ref。");
                out.append("\n来源=").append(hint.source).append(" ").append(hint.path)
                        .append(" 预期产物=").append(RuntimeRecipes.options(display.result(), context))
                        .append("\n").append(RuntimeRecipes.layout(display, context));
            }
        }
        if (!out.isEmpty()) return out.toString();
        if (found > 0) return "手摆候选页码超出范围";
        return "本机资料没有可解析的标准布局。可按可靠的原版知识、以前验证的经验或用户给的布局手摆；陌生配方缺少线索时说明还缺什么。";
    }
}
