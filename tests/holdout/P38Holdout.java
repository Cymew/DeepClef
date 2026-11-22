package adris.altoclef.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.Advancement;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.recipebook.ServerPlaceRecipe;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.item.crafting.display.*;
import xaero.map.WorldMap;
import xaero.map.controls.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;

public final class P38Holdout {
    private static final String BASELINE = "fc6a4b1ebda8264fadaab1cf499fcbb07a94c22ae589d317c49064f87f44b310";

    public static void main(String[] args) throws Exception {
        var baseline = Path.of(args[0]);
        check(hash(baseline).equals(BASELINE), "Frozen DeepClef hash");
        check(Files.isSameFile(origin(RuntimeBindings.class), baseline) && Files.isSameFile(origin(RuntimeRecipes.class), baseline),
                "Both production paths loaded from the frozen JAR");
        check(Files.isSameFile(origin(ControlsHandler.class), Path.of(args[1])), "Real Xaero handler loaded");
        System.out.println("DeepClef " + BASELINE + " / Xaero " + hash(Path.of(args[1])) + " / trial pack " + hash(Path.of(args[3])));
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var registries = VanillaRegistries.createWorldLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(pending -> pending.apply());
        recipe(Path.of(args[3]), registries);
        binding(Path.of(args[2]));
    }

    private static Path origin(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static String hash(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static JsonElement read(ZipFile zip, String entry) throws Exception {
        try (var reader = new InputStreamReader(zip.getInputStream(zip.getEntry(entry)), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        }
    }

    private static void recipe(Path pack, HolderLookup.Provider registries) throws Exception {
        var location = new PackLocationInfo("p38_trial", Component.literal("P3.8"), PackSource.DEFAULT, Optional.empty());
        try (var metadata = new FilePackResources.FileResourcesSupplier(pack).openMetadata(location)) {
            var header = metadata.getMetadataSection(PackMetadataSection.SERVER_TYPE);
            check(header != null && header.supportedFormats().isValueInRange(SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA)),
                    "Game accepts the ZIP metadata for this data pack version");
        }
        try (var zip = new ZipFile(pack.toFile())) {
            var ops = registries.createSerializationContext(JsonOps.INSTANCE);
            var recipe = (ShapelessRecipe) Recipe.DIRECT_CODEC.parse(ops, read(zip, "data/p38_trial/recipe/petal_candle.json")).getOrThrow();
            var unlock = Advancement.CODEC.parse(ops, read(zip, "data/p38_trial/advancement/unlock_petal_candle.json")).getOrThrow();
            check(unlock.rewards().recipes().stream().anyMatch(id -> id.identifier().toString().equals("p38_trial:petal_candle")), "Advancement awards the packaged recipe");
            var entry = new RecipeDisplayEntry(new RecipeDisplayId(13), recipe.display().getFirst(), OptionalInt.empty(),
                    recipe.recipeBookCategory(), Optional.of(recipe.placementInfo().ingredients()));
            var book = List.of(new RecipeCollection(List.of(entry)));
            var tools = new RuntimeRecipes();
            var bag = new StackedItemContents();
            bag.accountStack(new ItemStack(Items.PAPER));
            check(tools.query(book, ContextMap.EMPTY, bag, "花纸蜡烛", "output", 1).contains("不足"), "Custom name lookup detects missing ingredient");
            bag.accountStack(new ItemStack(Items.DANDELION));
            String result = tools.query(book, ContextMap.EMPTY, bag, "花纸蜡烛", "output", 1);
            check(result.contains("可凑齐") && result.contains("×2") && result.contains("minecraft:paper") && result.contains("minecraft:dandelion"), "Frozen recipe query returns both ingredients and yield");
            String ref = result.lines().skip(1).findFirst().orElseThrow().split(" ")[0];
            check(tools.resolve(ref, book) == entry, "Returned recipe reference resolves in the current book");
            var grid = placeFromInventory(recipe);
            var output = recipe.assemble(grid);
            check(recipe.matches(grid, null) && BuiltInRegistries.ITEM.getKey(output.getItem()).toString().equals("minecraft:yellow_candle") && output.getCount() == 2
                    && output.getHoverName().getString().equals("花纸蜡烛"), "Native matching and result preserve named output");
            check(!recipe.matches(CraftingInput.of(1, 1, List.of(new ItemStack(Items.PAPER))), null), "Paper alone fails native matching");
            boolean stale = false;
            try { tools.resolve(ref, List.of()); } catch (IllegalArgumentException expected) { stale = true; }
            check(stale, "Old recipe reference rejected with a different save's empty book");
        }
    }

    private static CraftingInput placeFromInventory(ShapelessRecipe recipe) {
        var bag = new Inventory(null, new EntityEquipment());
        bag.setItem(0, new ItemStack(Items.PAPER, 2));
        bag.setItem(1, new ItemStack(Items.DANDELION, 2));
        var storage = new SimpleContainer(4);
        var slots = new ArrayList<Slot>();
        for (int i = 0; i < 4; i++) slots.add(new Slot(storage, i, i % 2 * 18, i / 2 * 18));
        var access = new ServerPlaceRecipe.CraftingMenuAccess<ShapelessRecipe>() {
            public void clearCraftingContent() { storage.clearContent(); }
            public void fillCraftSlotsStackedContents(StackedItemContents counts) {
                slots.forEach(slot -> counts.accountSimpleStack(slot.getItem()));
            }
            public boolean recipeMatches(RecipeHolder<ShapelessRecipe> holder) { return holder.value().matches(input(), null); }
            CraftingInput input() { return CraftingInput.of(2, 2, slots.stream().map(Slot::getItem).toList()); }
        };
        var holder = new RecipeHolder<>(ResourceKey.create(Registries.RECIPE, Identifier.parse("p38_trial:petal_candle")), recipe);
        ServerPlaceRecipe.placeRecipe(access, 2, 2, slots, slots, bag, holder, false, false);
        check(access.recipeMatches(holder) && bag.getItem(0).getCount() == 1 && bag.getItem(1).getCount() == 1,
                "Server placement moves exactly one of each ingredient into the 2x2 grid");
        return access.input();
    }

    private static void binding(Path options) throws Exception {
        var registered = new ArrayList<KeyMapping>();
        WorldMap.controlsRegister = new ControlsRegister();
        WorldMap.controlsRegister.register(registered::add, category -> {});
        var target = ControlsRegister.keyOpenMap;
        var settings = new HashMap<String, String>();
        for (String line : Files.readAllLines(options)) {
            if (line.startsWith("key_") && line.contains(":")) {
                int separator = line.indexOf(':');
                settings.put(line.substring(4, separator), line.substring(separator + 1));
            }
        }
        // The isolated client has the mod's real bindings plus other actions sharing its configured key.
        String physical = Objects.requireNonNull(settings.get(target.getName()), "Map binding absent from options");
        for (var mapping : registered) {
            if (settings.containsKey(mapping.getName())) mapping.setKey(InputConstants.getKey(settings.get(mapping.getName())));
        }
        var collisions = settings.entrySet().stream().filter(e -> e.getValue().equals(physical)
                && registered.stream().noneMatch(k -> k.getName().equals(e.getKey()))).toList();
        for (var other : collisions) {
            var mapping = new KeyMapping(other.getKey(), InputConstants.UNKNOWN.getValue(), KeyMapping.Category.MISC);
            mapping.setKey(InputConstants.getKey(other.getValue()));
            registered.add(mapping);
        }
        var mc = allocate(Minecraft.class);
        var gui = allocate(Gui.class);
        var config = allocate(Options.class);
        set(mc, "gui", gui);
        set(mc, "options", config);
        set(config, "keyMappings", registered.toArray(KeyMapping[]::new));
        var instance = Minecraft.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, mc);
        KeyMapping.resetMapping();
        String listing = RuntimeBindings.list(target.getName());
        check(listing.contains(target.getName()) && listing.contains(physical), "Frozen tool discovers the registered map action");
        System.out.println("Configured map key: " + physical + " / other bindings: " + collisions.stream().map(Map.Entry::getKey).toList());
        if (RuntimeBindings.conflict(target, config.keyMappings) || target.isUnbound()) {
            boolean rejected = false;
            try { RuntimeBindings.resolve(target.getName()); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected && !target.consumeClick(), "Conflicting or unbound action rejected without a click");
        }
        // Only this test JVM is remapped. The player's options file is read-only.
        String trialKey = java.util.stream.IntStream.rangeClosed(1, 25).mapToObj(i -> "key.keyboard.f" + i)
                .filter(candidate -> !settings.containsValue(candidate)).findFirst().orElseThrow();
        target.setKey(InputConstants.getKey(trialKey));
        KeyMapping.resetMapping();
        var key = RuntimeBindings.resolve(target.getName());
        RuntimeBindings.press(key);
        check(key == target && key.isDown(), "Resolved remapped binding receives a press");
        var handler = new ControlsHandler(null);
        handler.onKeyInput(mc);
        RuntimeBindings.release(key);
        handler.onKeyInput(mc);
        var field = ControlsHandler.class.getDeclaredField("keyEvents");
        field.setAccessible(true);
        var events = (List<?>)field.get(handler);
        check(events.size() == 1 && events.getFirst() instanceof KeyEvent event && event.getKb() == target
                && event.isKeyDown() && !key.isDown() && !key.consumeClick(), "Xaero queued exactly one map-open key event; key released");
    }

    // The native input consumer needs a client with no screen open. No world, GPU or server is started.
    private static <T> T allocate(Class<T> type) throws Exception {
        var unsafe = Class.forName("sun.misc.Unsafe");
        var f = unsafe.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(f.get(null), type));
    }

    private static void set(Object instance, String name, Object value) throws Exception {
        var field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        System.out.println("PASS " + label);
    }
}
