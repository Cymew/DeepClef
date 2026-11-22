package adris.altoclef.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.*;

import java.util.*;

/** Resolves the displays that this connection has actually received. */
final class RuntimeRecipes {
    private static final int PAGE_SIZE = 4;
    private final Map<String, RecipeDisplayEntry> refs = new HashMap<>();

    void query(String query, String by, int page, java.util.function.BooleanSupplier active,
               java.util.function.Consumer<String> result) {
        var mc = Minecraft.getInstance();
        var contents = new StackedItemContents();
        mc.player.getInventory().fillStackedContents(contents);
        if (mc.player.containerMenu instanceof AbstractCraftingMenu grid) grid.fillCraftSlotsStackedContents(contents);
        if (!by.equals("manual")) {
            String known = query(mc.player.getRecipeBook().getCollections(), SlotDisplayContext.fromLevel(mc.level),
                    contents, query, by, page);
            if (!by.equals("output") || !refs.isEmpty()) {
                result.accept(known + (by.equals("output") ? "\n若匹配项都不是目标，可用 by=manual 查本机手摆资料。" : ""));
                return;
            }
        } else refs.clear();
        // Only immutable bundled JSON is indexed off-thread. Resolve live registries/tags on the client.
        var level = mc.level;
        var player = mc.player;
        BundledRecipeHints.index(mc.getVanillaPackResources().fullResources()).whenCompleteAsync((hints, error) -> {
            if (!active.getAsBoolean()) return;
            if (mc.level != level || mc.player != player) { result.accept("查询中断：游戏世界已变化"); return; }
            try {
                String text = error == null ? BundledRecipeHints.lookup(hints, query, page, level.registryAccess(),
                        SlotDisplayContext.fromLevel(level)) : "本机配方资料暂时无法读取，可按已知布局手摆。";
                result.accept((by.equals("manual") ? "" : "已同步配方书没有命中。") + text);
            } catch (RuntimeException ex) {
                result.accept("本机资料暂时无法解析，可按已知布局手摆并核对输出。");
            }
        }, mc);
    }

    String query(List<RecipeCollection> book, ContextMap context, StackedItemContents contents, String query, String by, int page) {
        refs.clear();
        String term = query.strip().toLowerCase(Locale.ROOT);
        var requestedId = net.minecraft.resources.Identifier.tryParse(term);
        boolean exactItem = by.equals("output") && requestedId != null && BuiltInRegistries.ITEM.containsKey(requestedId);
        var matches = new ArrayList<RecipeDisplayEntry>();
        // Repeated ingredients/tags across many recipes only need one resolution per query.
        var matching = new HashMap<SlotDisplay, Boolean>();
        for (var collection : book) {
            for (var entry : collection.getRecipes()) {
                if (by.equals("craftable") && !entry.canCraft(contents)) continue;
                if (term.isEmpty() && by.equals("craftable")) { matches.add(entry); continue; }
                var displays = by.equals("ingredient") ? inputs(entry.display()) : List.of(entry.display().result());
                if (displays.stream().anyMatch(d -> matching.computeIfAbsent(d, key -> stacks(key, context).stream()
                        .anyMatch(stack -> exactItem ? BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(requestedId) : matches(stack, term))))) matches.add(entry);
            }
        }
        if (matches.isEmpty()) return "当前客户端已知配方中没有匹配项；未解锁、未同步或专用制作界面都可能造成此结果。有布局线索时可用可见网格少量试摆。";
        matches.sort(Comparator.comparingInt(e -> e.id().index()));
        int pages = (matches.size() - 1) / PAGE_SIZE + 1;
        if (page < 1 || page > pages) throw new IllegalArgumentException("配方页码范围 1.." + pages);
        String prefix = UUID.randomUUID().toString().substring(0, 8);
        var out = new StringBuilder("已同步配方 ").append(matches.size()).append(" 项，页 ").append(page).append('/').append(pages);
        for (var entry : matches.subList((page - 1) * PAGE_SIZE, Math.min(page * PAGE_SIZE, matches.size()))) {
            String ref = prefix + "-r" + entry.id().index();
            refs.put(ref, entry);
            out.append("\n").append(ref).append(" 产出=").append(options(entry.display().result(), context))
                    .append(" 工作站=").append(options(entry.display().craftingStation(), context))
                    .append(" 材料=").append(entry.craftingRequirements().isEmpty() ? "可用性未知" : entry.canCraft(contents) ? "可凑齐" : "不足")
                    .append("\n").append(layout(entry.display(), context));
        }
        return out.toString();
    }

    private static boolean matches(ItemStack stack, String term) {
        return !stack.isEmpty() && (BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().contains(term)
                || stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(term));
    }

    static List<SlotDisplay> inputs(RecipeDisplay d) {
        if (d instanceof ShapedCraftingRecipeDisplay s) return s.ingredients();
        if (d instanceof ShapelessCraftingRecipeDisplay s) return s.ingredients();
        if (d instanceof FurnaceRecipeDisplay s) return List.of(s.ingredient());
        if (d instanceof SmithingRecipeDisplay s) return List.of(s.template(), s.base(), s.addition());
        if (d instanceof StonecutterRecipeDisplay s) return List.of(s.input());
        return List.of();
    }

    private static List<ItemStack> stacks(SlotDisplay display, ContextMap context) {
        try { return display.resolveForStacks(context); }
        catch (RuntimeException unresolvedDisplay) { return List.of(); }
    }

    static String options(SlotDisplay display, ContextMap context) {
        if (display instanceof SlotDisplay.Empty) return "空格";
        var stacks = stacks(display, context);
        if (stacks.isEmpty()) return "客户端无法解析";
        var choices = stacks.stream().filter(s -> !s.isEmpty()).limit(4).map(MenuActions::summary).toList();
        String description = String.join(" 或 ", choices) + (stacks.size() > 4 ? " 等" + stacks.size() + "项" : "");
        if (display instanceof SlotDisplay.WithRemainder with) description += "（返还 " + options(with.remainder(), context) + "）";
        return description;
    }

    static String layout(RecipeDisplay display, ContextMap context) {
        var inputs = inputs(display);
        String form;
        int width;
        if (display instanceof ShapedCraftingRecipeDisplay s) {
            form = "有序 " + s.width() + "×" + s.height() + "，按行";
            width = Math.max(1, s.width());
        } else {
            form = display instanceof ShapelessCraftingRecipeDisplay ? "无序，每项占一格" : "专用工作站（仅查询）";
            width = Math.max(1, inputs.size());
        }
        var out = new StringBuilder(form);
        if (fits(display, 2, 2)) out.append("（背包 2×2 或工作台）");
        else if (fits(display, 3, 3)) out.append("（需要 3×3 工作台）");
        var legend = new LinkedHashMap<SlotDisplay, String>();
        for (int i = 0; i < Math.min(16, inputs.size()); i++) {
            SlotDisplay cell = inputs.get(i);
            out.append(i % width == 0 ? "\n  " : " | ").append(cell instanceof SlotDisplay.Empty ? "空格"
                    : legend.computeIfAbsent(cell, key -> String.valueOf((char)('A' + legend.size()))));
        }
        if (inputs.size() > 16) out.append(" …布局省略，共 ").append(inputs.size()).append(" 格");
        legend.forEach((cell, symbol) -> out.append("\n  ").append(symbol).append(" = ").append(options(cell, context)));
        if (display instanceof FurnaceRecipeDisplay f) out.append("；燃料=").append(options(f.fuel(), context));
        return out.toString();
    }

    RecipeDisplayEntry resolve(String ref, List<RecipeCollection> currentBook) {
        var entry = refs.get(ref);
        if (entry == null || currentBook.stream().noneMatch(c -> c.getRecipes().stream().anyMatch(e -> e == entry)))
            throw new IllegalArgumentException("配方引用过期或已被替换，请重新 recipes");
        return entry;
    }

    static boolean fits(RecipeDisplay recipe, int width, int height) {
        if (recipe instanceof ShapedCraftingRecipeDisplay shaped) return shaped.width() <= width && shaped.height() <= height;
        return recipe instanceof ShapelessCraftingRecipeDisplay shapeless && shapeless.ingredients().size() <= width * height;
    }

    static String slotLabel(AbstractCraftingMenu grid, Slot slot) {
        if (slot == grid.getResultSlot()) return " 合成输出预览";
        int index = grid.getInputGridSlots().indexOf(slot);
        return index < 0 ? "" : " 合成材料[行" + (1 + index / grid.getGridWidth()) + "列" + (1 + index % grid.getGridWidth()) + "]";
    }
}
