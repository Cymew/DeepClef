package adris.altoclef.util;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

/** Inventory milestones are consumed in order. Spending a completed intermediate does not reopen it. */
final class PlayerProject {
    static final String LOGS = "logs", PLANKS = "planks", TABLE = "minecraft:crafting_table", STICKS = "minecraft:stick";
    record Milestone(String item, int count) {}
    private final ArrayDeque<Milestone> remaining = new ArrayDeque<>();
    private String name = "", last = "尚无", blockedItem = "";
    private int stalls;
    private Integer blockedSupplies;
    private boolean initialized;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    record Saved(int version, boolean initialized, String name, List<Milestone> remaining,
                 String last, String blockedItem, int stalls, List<String> journey, Integer blockedSupplies) {}
    final PlayerJourney journey = new PlayerJourney();
    private Saved saved;
    private java.nio.file.Path file;
    private boolean bound;

    void bind(java.nio.file.Path directory) throws java.io.IOException {
        if (bound || directory == null || !java.nio.file.Files.isDirectory(directory)) return;
        bound = true;
        var path = directory.resolve("player_project.json");
        if (java.nio.file.Files.exists(path)) {
            var state = JSON.readValue(path.toFile(), Saved.class);
            if (state == null || state.version != 1 || state.remaining == null || state.remaining.size() > 4
                    || state.name == null || state.last == null || state.blockedItem == null
                    || state.remaining.stream().anyMatch(step -> step == null || step.item == null
                        || step.count < 1 || step.count > 64)) throw new java.io.IOException("主线记录无效");
            initialized = state.initialized;
            name = state.name;
            remaining.clear();
            remaining.addAll(state.remaining);
            last = state.last;
            blockedItem = state.blockedItem;
            stalls = state.stalls;
            blockedSupplies = state.blockedSupplies;
            journey.restore(state.journey);
            saved = state;
        }
        file = path;
    }

    void save() throws java.io.IOException {
        if (file == null) return;
        var state = new Saved(1, initialized, name, List.copyOf(remaining), last, blockedItem, stalls, journey.history(), blockedSupplies);
        if (state.equals(saved)) return;
        var temp = java.nio.file.Files.createTempFile(file.getParent(), "player-project-", ".tmp");
        try {
            JSON.writeValue(temp.toFile(), state);
            java.nio.file.Files.move(temp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            saved = state;
        } finally { java.nio.file.Files.deleteIfExists(temp); }
    }


    void initialize(Map<String,Integer> stock, boolean hasTool) {
        if (initialized) return;
        initialized = true;
        if (!hasTool) start("备好随身工作台和木棍", List.of(new Milestone(TABLE, 1), new Milestone(STICKS, 4)), stock);
    }

    boolean start(String title, List<Milestone> steps, Map<String,Integer> stock) {
        if (!remaining.isEmpty() || title.isBlank() || steps.isEmpty() || steps.size() > 4) return false;
        if (steps.stream().allMatch(s -> count(stock, s.item) >= s.count)) return false;
        var next = steps.stream().filter(s -> count(stock, s.item) < s.count).findFirst().orElseThrow();
        if (next.item.equals(blockedItem) && java.util.Objects.equals(blockedSupplies, stock.hashCode())) return false;
        if (steps.size() == 1 && next.item.equals(LOGS) && count(stock, LOGS) >= 4) return false;
        name = title;
        remaining.addAll(steps);
        stalls = 0;
        refresh(stock);
        return true;
    }

    static int count(Map<String,Integer> stock, String key) { return stock.getOrDefault(key, 0); }

    void syncPreparation(PlayerJourney.Chapter chapter, Milestone desired, Map<String,Integer> stock) {
        if (chapter == null) return;
        String title = "为" + chapter.title + "做准备";
        // Replace only the planner's own single-step order when inventory or dimension changed.
        if (name.equals(title) && remaining.size() == 1 && !remaining.peek().equals(desired)) {
            remaining.clear();
            stalls = 0;
        }
        if (desired != null) start(title, List.of(desired), stock);
    }

    void chapterChanged(PlayerJourney.Chapter previous, PlayerJourney.Chapter current) {
        if (previous != null && previous != current && name.equals("为" + previous.title + "做准备")) {
            remaining.clear();
            stalls = 0;
            blockedItem = "";
            last = "原版进度或现场已确认：" + previous.title;
        }
    }

    void refresh(Map<String,Integer> stock) {
        while (!remaining.isEmpty() && count(stock, remaining.peek().item) >= remaining.peek().count) {
            last = "已达成 " + remaining.remove();
            stalls = 0;
            if (remaining.isEmpty()) last = "已完成：" + name;
        }
    }

    String nextProduct(Map<String,Integer> stock) {
        if (remaining.isEmpty()) return "";
        String wanted = remaining.peek().item;
        int boards = count(stock, PLANKS);
        if (wanted.equals("minecraft:wooden_pickaxe") || wanted.equals("minecraft:stone_pickaxe")) {
            if (count(stock, STICKS) < 2 && boards >= 2) return STICKS;
            if (count(stock, STICKS) < 2 || wanted.equals("minecraft:wooden_pickaxe") && boards < 3)
                return count(stock, LOGS) > 0 ? PLANKS : LOGS;
            if (wanted.equals("minecraft:stone_pickaxe") && count(stock, "minecraft:cobblestone") < 3)
                return "minecraft:cobblestone";
        }
        if (wanted.equals("minecraft:iron_ingot") && count(stock, "minecraft:raw_iron") < 1)
            return "minecraft:raw_iron";
        if (TABLE.equals(wanted) && boards < 4 || STICKS.equals(wanted) && boards < 2)
            return count(stock, LOGS) > 0 ? PLANKS : LOGS;
        if (PLANKS.equals(wanted) && count(stock, LOGS) == 0) return LOGS;
        return wanted;
    }

    // AltoClef get accepts the desired final inventory total, not the missing quantity.
    Milestone order(Map<String,Integer> stock, boolean furnaceVisible) {
        var goal = remaining.peek();
        if (goal == null || goal.item.equals("explore")) return null;
        int crafts = goal.count - count(stock, goal.item);
        if (crafts <= 0) return null;
        if (goal.item.equals("minecraft:stone_pickaxe") || goal.item.equals("minecraft:wooden_pickaxe")) {
            if (count(stock, STICKS) < 2 * crafts) return new Milestone(STICKS, 2 * crafts);
            String head = goal.item.equals("minecraft:stone_pickaxe") ? "minecraft:cobblestone" : PLANKS;
            if (count(stock, head) < 3 * crafts) return new Milestone(head, 3 * crafts);
        }
        if (goal.item.equals("minecraft:iron_ingot")) {
            if (count(stock, "minecraft:raw_iron") < crafts) return new Milestone("minecraft:raw_iron", crafts);
            if (!furnaceVisible && count(stock, "minecraft:furnace") == 0 && count(stock,"minecraft:cobblestone") < 8)
                return new Milestone("minecraft:cobblestone", 8);
        }
        if (goal.item.equals("minecraft:furnace") && count(stock, "minecraft:cobblestone") < 8 * crafts)
            return new Milestone("minecraft:cobblestone", 8 * crafts);
        return goal;
    }

    boolean needsExactLog(Map<String,Integer> stock, java.util.function.Predicate<String> logItem) {
        var step = remaining.peek();
        return step != null && logItem.test(step.item) && count(stock, step.item) < step.count;
    }

    boolean allowMoreLogs(Map<String,Integer> stock) {
        int limit = 4;
        if (!remaining.isEmpty() && LOGS.equals(remaining.peek().item)) limit = remaining.peek().count;
        return count(stock, LOGS) < limit;
    }

    boolean acceptExcursion() {
        var step = remaining.peek();
        if (step == null || !step.item.equals("explore")) return false;
        remaining.remove();
        if (step.count > 1) remaining.addFirst(new Milestone("explore", step.count - 1));
        last = remaining.isEmpty() ? "已完成：" + name : "已完成一段探索";
        stalls = 0;
        blockedItem = "";
        return true;
    }

    void afterAction(Map<String,Integer> before, Map<String,Integer> after) {
        var step = remaining.peek();
        if (step == null) return;
        var batch = order(before, false);
        String intermediate = nextProduct(before);
        refresh(after);
        if (step != remaining.peek()) { blockedItem = ""; return; }
        boolean furnacePrep = step.item.equals("minecraft:iron_ingot") && count(before, "minecraft:raw_iron") > 0
                && ((count(before, "minecraft:cobblestone") < 8 && count(after, "minecraft:cobblestone") > count(before, "minecraft:cobblestone"))
                || count(after, "minecraft:furnace") > count(before, "minecraft:furnace"));
        if (furnacePrep || batch != null && count(after, batch.item) > count(before, batch.item)
                || count(after, step.item) > count(before, step.item) || count(after, intermediate) > count(before, intermediate)) {
            stalls = 0;
        } else if (++stalls >= 3) {
            last = "暂缓：" + name + "，连续三次未推进 " + step.item;
            blockedItem = step.item;
            blockedSupplies = after.hashCode();
            remaining.clear();
        }
    }

    String describe(Map<String,Integer> stock) {
        var step = remaining.peek();
        return step == null ? "待选新项目。上次：" + last : name + "；当前 "
                + (step.item.equals("explore") ? "探索尚余 " + step.count + " 段" :
                step.item + " " + count(stock, step.item) + "/" + step.count + "；本步需要 " + nextProduct(stock))
                + "；后续 " + remaining.stream().skip(1).toList();
    }
}
