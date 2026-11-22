package adris.altoclef.util;

import adris.altoclef.AltoClef;
import adris.altoclef.mixins.MenuScreenAccess;
import adris.altoclef.mixins.TooltipTextAccess;
import adris.altoclef.mixins.WidgetTooltipAccess;
import com.fasterxml.jackson.databind.JsonNode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/** Client-thread operations on a currently displayed survival-style menu. */
final class MenuActions {
    static final List<String> ACTIONS = List.of("open_inventory", "menu", "inspect", "slot_options", "slot_click", "button_click", "menu_view", "menu_click", "menu_scroll", "close_menu", "close_screen", "recipes", "recipe_place");
    private static final int PAGE_SIZE = 54;
    private final AltoClef mod;
    private final RuntimeRecipes recipes = new RuntimeRecipes();
    private AbstractContainerScreen<?> awaitingPlacement;
    private long placementSentAt;
    private final Map<String, Receipt> receipts = new HashMap<>();
    private AbstractContainerScreen<?> screen;
    private Object player;
    private AbstractContainerMenu menu;
    private Snapshot view;
    private record Frame(String id, String menuRef, MenuPanel panel, java.awt.image.BufferedImage image) {}
    private Frame frame;
    void forgetFrame() { frame = null; }
    private int stateId;
    private String prefix;
    private int page;
    private final Map<String, Slot> slots = new HashMap<>();
    private int observedCutStart = -1;
    private record CutOption(int index, net.minecraft.world.item.crafting.SelectableRecipe<?> recipe) {}
    private final Map<String, CutOption> cuts = new HashMap<>();
    private record Control(AbstractButton widget, String label) {}
    private final Map<String, Control> buttons = new HashMap<>();
    private record Receipt(Request request, String text) {}

    MenuActions(AltoClef mod) { this.mod = mod; }

    record Request(String action, String id, String ref, String click, int page, String query, String by, String recipe, int x, int y, int scrollY) {
        static Request parse(JsonNode json) {
            String action = json.path("action").asText();
            var allowed = switch (action) {
                case "recipes" -> Set.of("action", "query", "by", "page");
                case "recipe_place" -> Set.of("action", "action_id", "menu_ref", "recipe_ref");
                case "menu_view" -> Set.of("action", "menu_ref");
                case "menu_scroll" -> json.has("frame_ref")
                        ? Set.of("action", "action_id", "frame_ref", "x", "y", "direction")
                        : Set.of("action", "action_id", "menu_ref", "direction");
                case "menu_click" -> Set.of("action", "action_id", "frame_ref", "x", "y");
                case "menu" -> Set.of("action", "page");
                case "slot_options" -> Set.of("action", "slot_ref", "page");
                case "inspect" -> Set.of("action", "item_ref", "slot_ref");
                case "slot_click" -> Set.of("action", "action_id", "slot_ref", "click", "menu_ref");
                case "button_click" -> Set.of("action", "action_id", "button_ref");
                case "close_screen" -> Set.of("action", "action_id", "screen_ref");
                case "close_menu" -> Set.of("action", "action_id", "menu_ref");
                case "open_inventory" -> Set.of("action", "action_id");
                default -> throw new IllegalArgumentException("未知菜单操作");
            };
            json.fields().forEachRemaining(e -> {
                if (!allowed.contains(e.getKey()) || !(Set.of("page", "x", "y").contains(e.getKey()) ? e.getValue().isIntegralNumber()
                        && e.getValue().canConvertToInt() : e.getValue().isTextual()))
                    throw new IllegalArgumentException("菜单参数无效 " + e.getKey());
            });
            int page = json.path("page").asInt(1);
            if (page < 1) throw new IllegalArgumentException("page 从 1 开始");
            String id = json.path("action_id").asText("");
            if (allowed.contains("action_id") && (id.isBlank() || id.length() > 80))
                throw new IllegalArgumentException("需要 1..80 字符的 action_id");
            String ref = "";
            for (String key : List.of("item_ref", "slot_ref", "button_ref", "menu_ref", "frame_ref", "screen_ref")) {
                if (!json.has(key)) continue;
                if (action.equals("slot_click") && key.equals("menu_ref")) {
                    String window = json.path(key).asText();
                    if (window.isBlank() || !ref.startsWith(window + "-s"))
                        throw new IllegalArgumentException("menu_ref 与 slot_ref 不匹配");
                    continue;
                }
                if (!ref.isEmpty() || json.path(key).asText().isBlank())
                    throw new IllegalArgumentException("需要且只接受一个有效引用");
                ref = json.path(key).asText();
            }
            if (!Set.of("menu", "open_inventory", "recipes").contains(action) && ref.isEmpty())
                throw new IllegalArgumentException("缺少观察返回的引用");
            String click = json.path("click").asText();
            if (action.equals("slot_click") && !Set.of("left", "right", "quick_move").contains(click))
                throw new IllegalArgumentException("click 可用 left/right/quick_move");
            String query = json.path("query").asText("").strip();
            String by = json.path("by").asText("output");
            String recipe = json.path("recipe_ref").asText("");
            if (action.equals("recipes") && (!Set.of("output", "ingredient", "craftable", "manual").contains(by)
                    || (query.isEmpty() && !by.equals("craftable")) || query.length() > 100))
                throw new IllegalArgumentException("recipes 请指定 query（ID 或名称）及 by=output/ingredient/manual；by=craftable 可省略 query");
            if (action.equals("recipe_place") && recipe.isBlank()) throw new IllegalArgumentException("缺少 recipe_ref");
            int x = json.path("x").asInt(-1), y = json.path("y").asInt(-1);
            if ((action.equals("menu_click") || action.equals("menu_scroll") && json.has("frame_ref")) && (x < 0 || y < 0))
                throw new IllegalArgumentException("需要局部图片内的整数像素坐标 x 和 y");
            int scrollY = 0;
            if (action.equals("menu_scroll")) {
                scrollY = switch (json.path("direction").asText()) {
                    case "up" -> 1;
                    case "down" -> -1;
                    default -> throw new IllegalArgumentException("滚轮方向需要 up 或 down");
                };
            }
            return new Request(action, id, ref, click, page, query, by, recipe, x, y, scrollY);
        }
    }

    private static String clean(String s, int limit) {
        s = s.replaceAll("[\\p{Cc}\\p{Cf}]", " ").strip();
        return s.length() > limit ? s.substring(0, limit) + "…" : s;
    }

    static String summary(ItemStack stack) {
        if (stack.isEmpty()) return "空";
        return BuiltInRegistries.ITEM.getKey(stack.getItem()) + " ×" + stack.getCount()
                + " " + clean(stack.getHoverName().getString(), 64) + " #" + Integer.toHexString(ItemStack.hashItemAndComponents(stack));
    }

    static String detail(ItemStack stack) {
        if (stack.isEmpty()) return "空栈";
        var ids = stack.getComponents().keySet().stream()
                .map(type -> String.valueOf(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type))).sorted().toList();
        var out = new StringBuilder(summary(stack)).append(" 堆叠上限=").append(stack.getMaxStackSize());
        if (stack.isDamageableItem()) out.append(" 剩余耐久=").append(stack.getMaxDamage() - stack.getDamageValue()).append("/").append(stack.getMaxDamage());
        out.append("\n组件(").append(ids.size()).append(")=").append(String.join(", ", ids.stream().limit(40).toList()));
        if (ids.size() > 40) out.append("…");
        out.append("\n游戏提示文本：");
        Screen.getTooltipFromItem(Minecraft.getInstance(), stack).stream().limit(16)
                .forEach(line -> out.append("\n").append(clean(line.getString(), 180)));
        return out.toString();
    }

    record Snapshot(Map<Integer, ItemStack> stacks, ItemStack cursor) {
        static Snapshot take(List<Slot> visible, ItemStack cursor) {
            var stacks = new LinkedHashMap<Integer, ItemStack>();
            for (Slot s : visible) stacks.put(s.index, s.getItem().copy());
            return new Snapshot(stacks, cursor.copy());
        }
        boolean matches(Snapshot next) {
            return stacks.keySet().equals(next.stacks.keySet()) && ItemStack.matches(cursor, next.cursor)
                    && stacks.entrySet().stream().allMatch(e -> ItemStack.matches(e.getValue(), next.stacks.get(e.getKey())));
        }
        boolean validFor(Snapshot live, String action, int observedState, int currentState) {
            // Vanilla may acknowledge a predicted click without changing the observable stacks.
            return matches(live) && (observedState == currentState || action.equals("slot_click") || action.equals("inspect") || action.equals("slot_options"));
        }
        String delta(Snapshot next) {
            var changes = new ArrayList<String>();
            if (!ItemStack.matches(cursor, next.cursor)) changes.add("光标 " + summary(cursor) + " → " + summary(next.cursor));
            for (var e : next.stacks.entrySet()) {
                var old = stacks.get(e.getKey());
                if (old != null && !ItemStack.matches(old, e.getValue()))
                    changes.add("槽" + e.getKey() + " " + summary(old) + " → " + summary(e.getValue()));
            }
            if (!stacks.keySet().equals(next.stacks.keySet())) changes.add("可见槽位布局变化");
            if (changes.isEmpty()) return "未观察到变化";
            return String.join("；", changes.stream().limit(16).toList()) + (changes.size() > 16 ? "…共 " + changes.size() + " 处" : "");
        }
    }

    boolean ownsScreen() {
        return screen != null && player == mod.getPlayer() && Minecraft.getInstance().gui.screen() == screen
                && mod.getPlayer().containerMenu == menu;
    }
    NativeSkillGoal.Delivery deliveryView(NativeSkillGoal goal, String ref) {
        var now = current();
        if (!ownsScreen() || prefix == null || !prefix.equals(ref)
                || !(now.getMenu() instanceof net.minecraft.world.inventory.ChestMenu chest)
                || !chest.getCarried().isEmpty())
            throw new IllegalArgumentException("请在指定的箱式容器内用 menu 取得当前 menu_ref，并放好光标物品");
        var storage = chest.getContainer();
        long exposed = visible(now).stream().filter(slot -> slot.container == storage).count();
        if (exposed != storage.getContainerSize())
            throw new IllegalArgumentException("目标容器的储物槽尚未完整显示");
        return new NativeSkillGoal.Delivery(chest, goal.count(storage), goal.count(mod.getPlayer().getInventory()));
    }

    boolean used(String id) { return receipts.containsKey(id); }

    private AbstractContainerScreen<?> current() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.overlay() != null || !(mc.gui.screen() instanceof AbstractContainerScreen<?> container)
                || container.getMenu() != mc.player.containerMenu || container instanceof CreativeModeInventoryScreen)
            throw new IllegalArgumentException("当前没有可操作的生存背包或容器窗口：" + WorldInputScreen.describe());
        return container;
    }

    private static List<Slot> visible(AbstractContainerScreen<?> screen) {
        MenuScreenAccess access = (MenuScreenAccess) screen;
        var result = new ArrayList<Slot>();
        for (Slot slot : screen.getMenu().slots) {
            if (!slot.isActive()) continue;
            int x = access.deepclefLeft() + slot.x + 8, y = access.deepclefTop() + slot.y + 8;
            if (x >= 0 && y >= 0 && x < screen.width && y < screen.height && screen.getChildAt(x, y).isEmpty()
                    && access.deepclefSlotAt(x, y) == slot) result.add(slot);
        }
        return result;
    }

    private static boolean readable(AbstractContainerScreen<?> screen, AbstractButton b) {
        int x = b.getX() + b.getWidth() / 2, y = b.getY() + b.getHeight() / 2;
        return b.visible && b.active && x >= 0 && x < screen.width && y >= 0 && y < screen.height
                && screen.getChildAt(x, y).orElse(null) == b;
    }

    private static String buttonLabel(AbstractButton button) {
        var tip = ((WidgetTooltipAccess) button).deepclefTooltipHolder().get();
        return buttonLabel(button.getMessage().getString(), tip == null ? "" : ((TooltipTextAccess) tip).deepclefTooltipText().getString());
    }

    static String buttonLabel(String label, String help) {
        label = label.strip();
        help = help.strip();
        if (help.isEmpty() || label.equals(help)) return label;
        return label.isEmpty() ? help : label + " · " + help;
    }

    static int insertable(Slot slot, ItemStack source) {
        if (source.isEmpty() || !slot.isActive() || !slot.mayPlace(source)) return 0;
        var existing = slot.getItem();
        if (!existing.isEmpty() && !ItemStack.isSameItemSameComponents(source, existing)) return 0;
        int limit = Math.min(source.getMaxStackSize(), slot.getMaxStackSize(source));
        return Math.max(0, Math.min(source.getCount(), limit - existing.getCount()));
    }

    private String slotOptions(Slot origin, String ref, int desiredPage) {
        if (origin == null && !ref.equals(prefix + "-cursor"))
            throw new IllegalArgumentException("请使用当前 menu 的材料 slot_ref 或光标引用");
        if (origin != null && !origin.mayPickup(mod.getPlayer()))
            throw new IllegalArgumentException("这个来源槽当前不可取出");
        ItemStack source = origin == null ? menu.getCarried() : origin.getItem();
        if (source.isEmpty()) throw new IllegalArgumentException("来源槽或光标为空，请选择实际材料");
        var matches = new ArrayList<String>();
        int shown = 12;
        int count = 0;
        for (Slot target : visible(screen)) {
            if (target == origin || target.container == mod.getPlayer().getInventory()) continue;
            int amount = insertable(target, source);
            if (amount == 0) continue;
            int targetPage = count++ / shown + 1;
            if (targetPage == desiredPage) {
                String targetRef = prefix + "-s" + target.index;
                slots.put(targetRef, target);
                matches.add(targetRef + " (" + target.x + "," + target.y + ") 可放 " + amount);
            }
        }
        if (count == 0) return "当前可见窗口槽中，没有可直接放入这叠物品的槽位。";
        int pages = (count + shown - 1) / shown;
        if (desiredPage > pages) throw new IllegalArgumentException("page 超出候选页数 " + pages);
        return "对 " + summary(source) + "，客户端允许直接放入的窗口槽（每个数量独立计算，配方以产出为准） 页 "
                + desiredPage + "/" + pages + "\n" + String.join("\n", matches);
    }

    private String observe(int desiredPage, boolean afterInput) {
        var now = current();
        var visible = visible(now);
        int pages = Math.max(1, (visible.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (afterInput) desiredPage = Math.min(desiredPage, pages);
        if (desiredPage > pages) throw new IllegalArgumentException("page 超出当前页数 " + pages);
        var next = Snapshot.take(visible, now.getMenu().getCarried());
        String delta = ownsScreen() && view != null ? view.delta(next) : "";
        screen = now;
        player = mod.getPlayer();
        menu = screen.getMenu();
        view = next;
        stateId = menu.getStateId();
        forgetFrame();
        prefix = UUID.randomUUID().toString().substring(0, 8);
        page = desiredPage;
        slots.clear();
        buttons.clear();
        cuts.clear();
        observedCutStart = -1;
        var out = new StringBuilder("窗口 ").append(clean(screen.getTitle().getString(), 80)).append(" menu_ref=").append(prefix)
                .append(" 页 ").append(page).append("/").append(pages).append("\n光标 ").append(prefix).append("-cursor ").append(summary(view.cursor));
        if (menu instanceof AbstractCraftingMenu grid) {
            out.append("\n合成网格 ").append(grid.getGridWidth()).append("×").append(grid.getGridHeight());
            if (awaitingPlacement == now) {
                if (!grid.getResultSlot().getItem().isEmpty()) {
                    out.append("；当前已有输出预览，取前核对物品和数量");
                    awaitingPlacement = null;
                } else if (placementPending()) out.append("；已请求摆料，正在等待服务器输出");
                else out.append("；尚未观察到输出，查看网格和材料后再决定");
            }
        }
        if (!delta.isEmpty()) out.append("\n观测变化：").append(delta);
        for (var s : visible.stream().skip((long)(page - 1) * PAGE_SIZE).limit(PAGE_SIZE).toList()) {
            String ref = prefix + "-s" + s.index;
            slots.put(ref, s);
            out.append("\n").append(ref);
            if (menu instanceof AbstractCraftingMenu grid) out.append(RuntimeRecipes.slotLabel(grid, s));
            out.append(s.container == mod.getPlayer().getInventory() ? " 玩家" : " 窗口")
                    .append(" (").append(s.x).append(",").append(s.y).append(") ").append(summary(view.stacks.get(s.index)));
        }
        if (screen instanceof net.minecraft.client.gui.screens.inventory.StonecutterScreen
                && menu instanceof net.minecraft.world.inventory.StonecutterMenu cutter && cutter.hasInputItem()) {
            int start = ((adris.altoclef.mixins.StonecutterScreenAccess) screen).deepclefStartIndex();
            observedCutStart = start;
            var choices = cutter.getVisibleRecipes().entries();
            if (choices.size() > 12) {
                out.append("\n切石配方 ").append(start + 1).append("–").append(Math.min(start + 12, choices.size()))
                        .append("/").append(choices.size()).append("；menu_scroll(menu_ref,direction) 每次滚一行。");
            }
            var context = net.minecraft.world.item.crafting.display.SlotDisplayContext.fromLevel(mod.level());
            for (int i = start; i < choices.size() && i < start + 12; i++) {
                var option = choices.get(i).recipe();
                String ref = prefix + "-cut" + i;
                cuts.put(ref, new CutOption(i, option));
                out.append("\n切石配方 ").append(ref).append(" ").append(RuntimeRecipes.options(option.optionDisplay(), context));
                if (cutter.getSelectedRecipeIndex() == i) out.append("（已选中）");
            }
        }
        for (var child : screen.children()) {
            if (child instanceof AbstractButton b && readable(screen, b) && buttons.size() < 32) {
                String label = buttonLabel(b);
                if (label.isBlank()) continue;
                String ref = prefix + "-b" + buttons.size();
                buttons.put(ref, new Control(b, label));
                out.append("\n按钮 ").append(ref).append(" ").append(clean(label, 160));
            }
        }
        return out.toString();
    }

    void queryRecipes(JsonNode json, java.util.function.BooleanSupplier active, java.util.function.Consumer<String> result) {
        Request r = Request.parse(json);
        if (mod.getPlayer() == null || mod.level() == null) throw new IllegalArgumentException("已离开游戏世界");
        recipes.query(r.query, r.by, r.page, active, result);
    }

    private void requireActionAvailable(String id, AtomicActions atomic) {
        if (receipts.size() >= 256 || atomic.used(id)) throw new IllegalArgumentException("动作记录已满或 ID 已占用");
        if (mod.getUserTaskChain().isRunningIdleTask()) mod.cancelUserTask();
        var task = mod.getUserTaskChain().getCurrentTask();
        if (task != null && !task.isFinished(mod)) throw new IllegalArgumentException("先等待或停止正在执行的任务");
    }

    private void requireVisualMenu(String observedRef) {
        current();
        if (!ownsScreen() || prefix == null || !prefix.equals(observedRef) || menu.getStateId() != stateId
                || !view.matches(Snapshot.take(visible(screen), menu.getCarried())))
            throw new IllegalArgumentException("菜单已变化，请用 menu 读取当前窗口");
    }

    void visual(JsonNode json, AtomicActions atomic, java.util.function.BooleanSupplier active,
                java.util.function.Consumer<String> attach, java.util.function.Consumer<String> result) {
        Request r = Request.parse(json);
        boolean input = !r.action.equals("menu_view");
        Frame captured = frame;
        String observedRef = r.ref;
        if (input) {
            Receipt prior = receipts.get(r.id);
            if (prior != null) {
                if (!prior.request.equals(r)) throw new IllegalArgumentException("action_id 已用于不同参数");
                result.accept(prior.text);
                return;
            }
            if (captured == null || !captured.id.equals(r.ref)) throw new IllegalArgumentException("局部图已过期，请重新观察");
            captured.panel.checkPoint(r.x, r.y);
            observedRef = captured.menuRef;
        }
        if (!input && ownsScreen() && observedRef.equals(prefix)
                && (menu.getStateId() != stateId || !view.matches(Snapshot.take(visible(screen), menu.getCarried())))) {
            // This is a fresh observation. Include server slot corrections received since the last receipt.
            observe(page, true);
            observedRef = prefix;
        }
        requireVisualMenu(observedRef);
        var layout = MenuPanel.of(screen);
        if (input && !captured.panel.equals(layout)) throw new IllegalArgumentException("面板布局已变化，请重新观察");
        final String sourceRef = observedRef;
        var mc = Minecraft.getInstance();
        var claimed = new java.util.concurrent.atomic.AtomicBoolean();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        Runnable timeout = () -> result.accept("未及时取得新的游戏画面，本次未发送面板输入。");
        java.util.concurrent.CompletableFuture.delayedExecutor(8, java.util.concurrent.TimeUnit.SECONDS).execute(() -> mc.execute(() -> {
            if (active.getAsBoolean() && claimed.compareAndSet(false, true)) timeout.run();
        }));
        net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), pixels -> mc.execute(() -> {
            String reply;
            try (pixels) {
                if (!active.getAsBoolean() || !claimed.compareAndSet(false, true)) return;
                if (System.nanoTime() - deadline >= 0) { timeout.run(); return; }
                requireVisualMenu(sourceRef);
                if (!layout.equals(MenuPanel.of(screen))) throw new IllegalArgumentException("窗口缩放或面板位置已变化");
                if (input) {
                    if (frame != captured || !layout.sameTarget(captured.image, pixels, r.x, r.y)) {
                        forgetFrame();
                        throw new IllegalArgumentException("目标附近的画面已变化，本次未发送面板输入");
                    }
                    if (r.action.equals("menu_scroll")) {
                        requireActionAvailable(r.id, atomic);
                        reply = scrollPanel(r, layout.guiX(r.x), layout.guiY(r.y));
                    } else {
                        reply = clickPanel(r, layout, atomic);
                    }
                } else {
                    var image = layout.crop(pixels);
                    var bytes = new java.io.ByteArrayOutputStream();
                    javax.imageio.ImageIO.write(image, "png", bytes);
                    String id = UUID.randomUUID().toString();
                    frame = new Frame(id, sourceRef, layout, image);
                    attach.accept("data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray()));
                    reply = "已附当前容器局部图 frame_ref=" + id + " menu_ref=" + sourceRef + " 尺寸 " + layout.width() + "×" + layout.height()
                            + "。左上角为 (0,0)，坐标取自这张图；标准槽位与文字按钮直接用其引用。";
                }
            } catch (Exception ex) {
                reply = "菜单视觉操作失败：" + ex.getMessage();
            }
            result.accept(reply);
        }));
    }

    private String scrollPanel(Request r, double x, double y) {
        var target = screen;
        var access = (MenuScreenAccess) target;
        if (access.deepclefSlotAt(x, y) != null || !menu.getCarried().isEmpty() || placementPending())
            throw new IllegalArgumentException("请在列表区域滚动，先放好光标物品并等待摆料同步");
        return submitWithReceipt(r, () -> {
            // Vanilla routes bundle wheel input via the physical mouse's last rendered slot.
            var previous = access.deepclefHoveredSlot();
            try {
                access.deepclefSetHoveredSlot(null);
                boolean handled = target.mouseScrolled(x, y, 0, r.scrollY);
                return handled ? "窗口已接收一次滚轮；按新列表或画面判断是否翻动。"
                        : "已发送一次滚轮，窗口未报告处理。";
            } finally {
                access.deepclefSetHoveredSlot(previous);
            }
        });
    }

    private String clickPanel(Request r, MenuPanel layout, AtomicActions atomic) {
        if (!menu.getCarried().isEmpty() || placementPending())
            throw new IllegalArgumentException("请先放好光标物品并等待当前摆料完成");
        double x = layout.guiX(r.x), y = layout.guiY(r.y);
        if (((MenuScreenAccess) screen).deepclefSlotAt(x, y) != null)
            throw new IllegalArgumentException("目标是标准槽位，请使用 slot_click");
        if (screen.getChildAt(x, y).orElse(null) instanceof AbstractButton b && buttons.values().stream().anyMatch(c -> c.widget == b))
            throw new IllegalArgumentException("目标是已列出的按钮，请使用 button_click");
        requireActionAvailable(r.id, atomic);
        return submit(r, () -> {
            var target = screen;
            var event = new MouseButtonEvent(x, y, new MouseButtonInfo(1, 0));
            target.mouseClicked(event, false);
            if (ownsScreen() && Minecraft.getInstance().gui.overlay() == null) target.mouseReleased(event);
        });
    }

    String execute(JsonNode json, AtomicActions atomic) {
        Request r = Request.parse(json);
        if (mod.getPlayer() == null || mod.level() == null) throw new IllegalArgumentException("已离开游戏世界");
        if (r.action.equals("menu")) return observe(r.page, false);
        if (r.action.equals("inspect") && json.has("item_ref")) return atomic.inspect(r.ref);
        Receipt prior = receipts.get(r.id);
        if (prior != null) {
            if (!prior.request.equals(r)) throw new IllegalArgumentException("action_id 已用于不同参数");
            return prior.text;
        }
        if (!r.id.isEmpty()) requireActionAvailable(r.id, atomic);
        if (r.action.equals("close_screen")) {
            var target = WorldInputScreen.resolveClose(r.ref);
            return submit(r, target::onClose);
        }
        if (r.action.equals("open_inventory")) {
            var mc = Minecraft.getInstance();
            if (mc.gui.screen() != null || mc.gui.overlay() != null || mc.player.containerMenu != mc.player.inventoryMenu
                    || mc.player.hasInfiniteMaterials() || mc.player.isSpectator())
                throw new IllegalArgumentException("当前不能打开生存背包：" + WorldInputScreen.describe() + "；只有生存容器窗口可用 menu");
            return submit(r, () -> mc.gui.setScreen(new InventoryScreen(mc.player)));
        }
        current();
        if (!ownsScreen() || view == null || prefix == null || !r.ref.startsWith(prefix))
            throw new IllegalArgumentException("窗口或引用已过期，本次未操作。当前引用：\n" + observe(page > 0 ? page : 1, true));
        Snapshot live = Snapshot.take(visible(screen), menu.getCarried());
        if (!view.validFor(live, r.action, stateId, menu.getStateId()))
            throw new IllegalArgumentException("窗口已更新，本次未操作。请接着使用以下新引用：\n" + observe(page, true));
        if (!r.id.isEmpty() && !r.action.equals("close_menu") && placementPending())
            throw new IllegalArgumentException("摆料仍在同步，请等当前网格状态更新");
        Slot slot = slots.get(r.ref);
        return switch (r.action) {
            case "menu_scroll" -> {
                if (!r.ref.equals(prefix) || observedCutStart < 0
                        || !(screen instanceof net.minecraft.client.gui.screens.inventory.StonecutterScreen)
                        || !(menu instanceof net.minecraft.world.inventory.StonecutterMenu cutter))
                    throw new IllegalArgumentException("当前列表需要用 menu_view 定位，再按 frame_ref 和图内坐标滚动");
                int start = ((adris.altoclef.mixins.StonecutterScreenAccess) screen).deepclefStartIndex();
                if (start != observedCutStart) throw new IllegalArgumentException("配方列表已滚动，请重新读取 menu");
                if (r.scrollY > 0 ? start == 0 : start + 12 >= cutter.getNumberOfVisibleRecipes())
                    throw new IllegalArgumentException("此方向已到切石配方列表边缘");
                var layout = MenuPanel.of(screen);
                yield scrollPanel(r, layout.guiX(60), layout.guiY(22));
            }
            case "slot_options" -> slotOptions(slot, r.ref, r.page);
            case "inspect" -> {
                if (r.ref.equals(prefix + "-cursor")) yield detail(live.cursor);
                if (slot == null) throw new IllegalArgumentException("需要当前页的 slot_ref");
                yield detail(slot.getItem());
            }
            case "close_menu" -> {
                if (!r.ref.equals(prefix) || !live.cursor.isEmpty()) throw new IllegalArgumentException("请核对 menu_ref 并先放好光标物品");
                yield submit(r, () -> screen.onClose());
            }
            case "recipe_place" -> {
                if (!r.ref.equals(prefix) || !(menu instanceof AbstractCraftingMenu grid))
                    throw new IllegalArgumentException("需要当前 2×2 背包或工作台的 menu_ref");
                var p = mod.getPlayer();
                var recipe = recipes.resolve(r.recipe, p.getRecipeBook().getCollections());
                if (!RuntimeRecipes.fits(recipe.display(), grid.getGridWidth(), grid.getGridHeight()))
                    throw new IllegalArgumentException("配方与当前网格不匹配，请查看布局和工作站");
                if (!live.cursor.isEmpty() || grid.getResultSlot().hasItem() || grid.getInputGridSlots().stream().anyMatch(Slot::hasItem))
                    throw new IllegalArgumentException("光标和合成格须为空，请先收好现有物品");
                if (!live.stacks.containsKey(grid.getResultSlot().index)
                        || grid.getInputGridSlots().stream().anyMatch(s -> !live.stacks.containsKey(s.index)))
                    throw new IllegalArgumentException("当前合成格未完整显示，请先展开网格");
                var contents = new StackedItemContents();
                p.getInventory().fillStackedContents(contents);
                if (recipe.craftingRequirements().isPresent() && !recipe.canCraft(contents))
                    throw new IllegalArgumentException("当前材料不足以摆放一份，请按 recipes 的材料替代项补齐");
                yield submit(r, () -> {
                    awaitingPlacement = screen;
                    placementSentAt = System.nanoTime();
                    mod.getController().handlePlaceRecipe(menu.containerId, recipe.id(), false);
                });
            }
            case "button_click" -> {
                var cut = cuts.get(r.ref);
                if (cut != null && menu instanceof net.minecraft.world.inventory.StonecutterMenu cutter) {
                    var entries = cutter.getVisibleRecipes().entries();
                    int start = ((adris.altoclef.mixins.StonecutterScreenAccess) screen).deepclefStartIndex();
                    if (cut.index < start || cut.index >= start + 12 || cut.index >= entries.size()
                            || entries.get(cut.index).recipe() != cut.recipe || !cutter.hasInputItem())
                        throw new IllegalArgumentException("切石配方已变化，请重新读取菜单");
                    yield submit(r, () -> {
                        if (cutter.clickMenuButton(mod.getPlayer(), cut.index))
                            mod.getController().handleInventoryButtonClick(cutter.containerId, cut.index);
                    });
                }
                Control control = buttons.get(r.ref);
                if (control == null || !readable(screen, control.widget) || !control.label.equals(buttonLabel(control.widget)))
                    throw new IllegalArgumentException("按钮已变化，请用 menu 更新引用");
                yield submit(r, () -> {
                    var b = control.widget;
                    var event = new MouseButtonEvent(b.getX() + b.getWidth() / 2.0, b.getY() + b.getHeight() / 2.0, new MouseButtonInfo(1, 0));
                    if (b.mouseClicked(event, false) && ownsScreen() && screen.children().contains(b)) b.mouseReleased(event);
                });
            }
            case "slot_click" -> {
                if (slot == null || !menu.getSlot(slot.index).equals(slot)) throw new IllegalArgumentException("需要当前页的槽位");
                if (r.click.equals("quick_move") && !live.cursor.isEmpty()) throw new IllegalArgumentException("快捷移动前先放好光标物品");
                if (!mod.getSlotHandler().canDoSlotAction()) throw new IllegalArgumentException("点击间隔过短，本次未发送");
                yield submit(r, () -> {
                    mod.getSlotHandler().registerSlotAction();
                    ((MenuScreenAccess) screen).deepclefClick(slot, slot.index, r.click.equals("right") ? 1 : 0,
                            r.click.equals("quick_move") ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP);
                });
            }
            default -> throw new IllegalArgumentException("未知菜单操作");
        };
    }

    private boolean placementPending() {
        if (awaitingPlacement == null) return false;
        if (awaitingPlacement == Minecraft.getInstance().gui.screen() && menu instanceof AbstractCraftingMenu grid
                && !grid.getResultSlot().hasItem() && System.nanoTime() - placementSentAt < 5_000_000_000L) return true;
        awaitingPlacement = null;
        return false;
    }

    private String submit(Request r, Runnable input) {
        return submitWithReceipt(r, () -> { input.run(); return ""; });
    }

    private String submitWithReceipt(Request r, java.util.function.Supplier<String> input) {
        // Reserve before invoking mod callbacks. An exception can occur after a packet was sent.
        receipts.put(r.id, new Receipt(r, "submitted: 已尝试输入；结果未确认，请用 menu 查看当前状态"));
        forgetFrame();
        prefix = null;
        String inputNote;
        try {
            inputNote = input.get();
        } catch (RuntimeException ex) {
            adris.altoclef.Debug.logWarning("菜单输入异常 " + r.action + ": " + ex);
            return receipts.get(r.id).text;
        }
        String result;
        if (r.action.equals("open_inventory") && Minecraft.getInstance().gui.screen() instanceof InventoryScreen)
            result = "observed: 已打开背包\n" + observe(1, true);
        else if (r.action.equals("close_screen"))
            result = "submitted: 已调用当前界面的关闭操作。" + WorldInputScreen.describe();
        else if (r.action.equals("recipe_place"))
            result = "submitted: 已请求原版配方书摆放一份。用 menu 读取同步后的网格和输出预览；取出后才计入已制作。";
        else if (!ownsScreen()) result = "observed: 当前界面已切换，请按需重新观察";
        else if (!inputNote.isEmpty()) result = "submitted: " + inputNote + "\n" + observe(page, true);
        else {
            result = "submitted: 以下为当前客户端状态，输出可能稍后同步。输出未变时先读 menu，勿重复点击。\n" + observe(page, true);
        }
        receipts.put(r.id, new Receipt(r, result));
        return result;
    }
}
