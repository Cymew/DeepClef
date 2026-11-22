package adris.altoclef.util;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.slots.PlayerSlot;
import baritone.api.utils.input.Input;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import java.util.*;

/** Per-agent observed references and bounded, single-owner game actions. */
public final class AtomicActions {
    private static final Set<String> ACTIONS = Set.of("observe", "equip", "aim", "use_item", "use_target", "attack", "keys", "press", "hold", "release", "wear");
    public record Request(String action, String id, String item, String target, InteractionHand hand, int ticks, boolean sneak, String binding, String armor) {
        public static Request parse(JsonNode json) {
            if (!json.isObject()) throw new IllegalArgumentException("参数必须是对象");
            Set<String> allowed = Set.of("action", "action_id", "item_ref", "target_ref", "hand", "hold_ticks", "sneak", "binding", "armor_slot");
            json.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw new IllegalArgumentException("未知参数 " + key); });
            for (String key : List.of("action", "action_id", "item_ref", "target_ref", "hand", "binding", "armor_slot"))
                if (json.has(key) && !json.get(key).isTextual()) throw new IllegalArgumentException(key + " 必须为字符串");
            String action = json.path("action").asText();
            String hand = json.path("hand").asText("main");
            if (!ACTIONS.contains(action) || !Set.of("main", "off").contains(hand)) throw new IllegalArgumentException("未知动作或手");
            if (json.has("hold_ticks") && (!json.get("hold_ticks").isIntegralNumber() || !json.get("hold_ticks").canConvertToInt()))
                throw new IllegalArgumentException("hold_ticks 必须为整数");
            int ticks = json.path("hold_ticks").asInt(1);
            int limit = Set.of("press", "hold").contains(action) ? 200 : 40;
            if (ticks < 1 || ticks > limit) throw new IllegalArgumentException("hold_ticks 范围 1.." + limit);
            if (json.has("sneak") && !json.get("sneak").isBoolean()) throw new IllegalArgumentException("sneak 必须为布尔值");
            if (action.equals("observe") && json.size() != 1) throw new IllegalArgumentException("observe 不接受操作参数");
            if (json.has("item_ref") && !Set.of("equip", "wear").contains(action)) throw new IllegalArgumentException("item_ref 仅用于 equip 或 wear");
            if (action.equals("equip") && (json.has("target_ref") || json.has("sneak"))) throw new IllegalArgumentException("equip 不接受目标或潜行参数");
            String id = json.path("action_id").asText();
            String item = json.path("item_ref").asText();
            String target = json.path("target_ref").asText();
            if (!Set.of("observe","keys").contains(action) && (id.isBlank() || id.length() > 80)) throw new IllegalArgumentException("动作需要 1..80 字符的 action_id");
            if (Set.of("wear","equip").contains(action) && item.isBlank()) throw new IllegalArgumentException("装备需要 item_ref");
            if (Set.of("aim", "use_target", "attack").contains(action) && target.isBlank()) throw new IllegalArgumentException("需要 target_ref");
            if (action.equals("attack") && !hand.equals("main")) throw new IllegalArgumentException("原版攻击使用主手");
            if (!Set.of("hold","press","use_item").contains(action) && ticks != 1) throw new IllegalArgumentException("此动作不支持持续保持");
            String binding = json.path("binding").asText("");
            String armor = json.path("armor_slot").asText("");
            if (action.equals("wear") && !Set.of("head","chest","legs","feet").contains(armor))
                throw new IllegalArgumentException("wear 需要 armor_slot=head/chest/legs/feet");
            if (!action.equals("wear") && json.has("armor_slot")) throw new IllegalArgumentException("armor_slot 仅用于 wear");
            boolean keyAction = Set.of("keys","press","hold","release").contains(action);
            if ((action.equals("press") || action.equals("hold")) && binding.isBlank())
                throw new IllegalArgumentException("需要绑定 ID");
            if (json.has("binding") && !Set.of("keys","press","hold").contains(action)) throw new IllegalArgumentException("无效 binding 参数");
            if ((keyAction || action.equals("wear")) && (json.has("target_ref") || json.has("hand") || json.has("sneak")))
                throw new IllegalArgumentException("此动作不接受目标、操作手或潜行修饰");
            if (action.equals("keys") && json.has("action_id")) throw new IllegalArgumentException("keys 不接受 action_id");
            if (!Set.of("press","hold","use_item").contains(action) && json.has("hold_ticks")) throw new IllegalArgumentException("此动作不接受 hold_ticks");
            if (action.equals("hold") && !json.has("hold_ticks")) throw new IllegalArgumentException("hold 需要 hold_ticks");
            return new Request(action, id, item, target, hand.equals("main") ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND, ticks, json.path("sneak").asBoolean(), binding, armor);
        }
    }

    private record ItemRef(int slot, ItemStack stack) {}
    private record Target(Entity entity, BlockHitResult block, BlockState state) {}
    private final AltoClef mod;
    private final VisibleEntityTargets entities = new VisibleEntityTargets();
    String visibleEntities(int radius, String query, boolean livingOnly) { return entities.list(mod, radius, query, livingOnly); }

    private final VisibleInteractions landmarks = new VisibleInteractions();

    String visibleInteractions(String keyword) { return landmarks.observe(mod, keyword); }
    private final Map<String, ItemRef> items = new LinkedHashMap<>();
    private final Map<String, Target> targets = new LinkedHashMap<>();
    private final Map<String, ActionTask> actions = new LinkedHashMap<>();
    private final Map<String, WorldActionSequence> sequences = new LinkedHashMap<>();
    private ActionTask activeHold;
    private boolean protectSheep;
    void protectSheep(boolean requested) { protectSheep = requested; }

    boolean holding() {
        if (activeHold != null && mod.getUserTaskChain().getCurrentTask() != activeHold)
            activeHold.endKey("interrupted: 任务已被替换");
        return activeHold != null && !activeHold.completed();
    }

    String release(String id) {
        ActionTask task = actions.get(id);
        if (task == null || !task.request.action().equals("hold"))
            throw new IllegalArgumentException("请提供本会话 hold 的原 action_id");
        if (!task.completed()) task.endKey("主动释放");
        return task.result();
    }

    void stopHold() {
        if (holding()) activeHold.endKey("控制结束");
    }

    private Object world, connection;
    private int observedAt;

    public AtomicActions(AltoClef mod) { this.mod = mod; }

    public static ObjectNode schema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.putArray("required").add("action");
        ObjectNode p = schema.putObject("properties");
        var choices = p.putObject("action").put("type", "string").putArray("enum");
        ACTIONS.stream().sorted().forEach(choices::add);
        MenuActions.ACTIONS.forEach(choices::add);
        for (String ref : List.of("slot_ref", "button_ref", "menu_ref", "recipe_ref", "frame_ref", "screen_ref", "query")) p.putObject(ref).put("type", "string");
        p.putObject("direction").put("type", "string").putArray("enum").add("up").add("down");
        p.putObject("by").put("type", "string").putArray("enum").add("output").add("ingredient").add("craftable").add("manual");
        p.putObject("click").put("type", "string").putArray("enum").add("left").add("right").add("quick_move");
        for (String axis : List.of("x", "y")) p.putObject(axis).put("type", "integer").put("minimum", 0);
        p.putObject("page").put("type", "integer").put("minimum", 1);
        for (String key : List.of("action_id", "item_ref", "target_ref"))
            p.putObject(key).put("type", "string");
        p.putObject("binding").put("type", "string");
        p.putObject("armor_slot").put("type", "string").putArray("enum").add("head").add("chest").add("legs").add("feet");
        p.putObject("hand").put("type", "string").putArray("enum").add("main").add("off");
        p.putObject("hold_ticks").put("type", "integer").put("minimum", 1).put("maximum", 200);
        p.putObject("sneak").put("type", "boolean");
        var step = schema.deepCopy();
        var stepFields = (ObjectNode)step.get("properties");
        stepFields.retain("action", "item_ref", "target_ref", "hand", "hold_ticks", "sneak", "binding", "armor_slot");
        var stepChoices = ((ObjectNode)stepFields.get("action")).putArray("enum");
        WorldActionSequence.ACTIONS.forEach(stepChoices::add);
        choices.add("sequence");
        p.putObject("steps").put("type", "array").put("minItems", 2).put("maxItems", WorldActionSequence.LIMIT).set("items", step);
        return schema;
    }

    boolean used(String id) { return actions.containsKey(id) || sequences.containsKey(id); }

    WorldActionSequence sequence(WorldActionSequence.Plan plan) {
        var prior = sequences.get(plan.id());
        if (prior != null) {
            if (!prior.plan.equals(plan)) throw new IllegalArgumentException("序列 ID 已用于不同参数");
            return prior;
        }
        checkNewId(plan.id());
        if (mod.getPlayer() == null || mod.level() == null) throw new IllegalArgumentException("请在游戏世界内执行");
        var task = new WorldActionSequence(mod, this, plan);
        sequences.put(plan.id(), task);
        return task;
    }

    private void checkNewId(String id) {
        if (used(id) || actions.size() + sequences.size() >= 256)
            throw new IllegalArgumentException("动作 ID 已占用或当前会话记录已满");
    }

    String inspect(String ref) {
        ItemRef item = items.get(ref);
        var p = mod.getPlayer();
        if (item == null || p == null || world != mod.level() || connection != p.connection || p.tickCount - observedAt > 1200)
            throw new IllegalArgumentException("item_ref 已过期，请重新 observe");
        var current = item.slot == 40 ? p.getOffhandItem() : p.getInventory().getItem(item.slot);
        if (!ItemStack.matches(current, item.stack)) throw new IllegalArgumentException("该栈已变化，请重新 observe");
        return MenuActions.detail(current);
    }

    public String observe() {
        items.clear(); targets.clear();
        world = mod.level(); connection = mod.getPlayer().connection;
        observedAt = mod.getPlayer().tickCount;
        String generation = UUID.randomUUID().toString().substring(0, 8);
        StringBuilder out = new StringBuilder("当前手中物品：main=" + describe(mod.getPlayer().getMainHandItem())
                + " off=" + describe(mod.getPlayer().getOffhandItem()) + "\n");
        for (var slot : java.util.List.of(net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
                net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET))
            out.append(slot.getName()).append("=").append(describe(mod.getPlayer().getItemBySlot(slot))).append("\n");
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mod.getPlayer().getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            String ref = generation + "-i" + i;
            items.put(ref, new ItemRef(i, stack.copy()));
            out.append(ref).append(i == mod.getPlayer().getInventory().getSelectedSlot() ? " 主手 " : " ").append(describe(stack)).append("\n");
        }
        ItemStack off = mod.getPlayer().getOffhandItem();
        if (!off.isEmpty()) {
            String ref = generation + "-off";
            items.put(ref, new ItemRef(40, off.copy()));
            out.append(ref).append(" ").append(describe(off)).append("\n");
        }
        BlockHitResult block = SceneObservation.nearLook(mod);
        if (block != null) {
            String ref = generation + "-block";
            targets.put(ref, new Target(null, block, mod.level().getBlockState(block.getBlockPos())));
            out.append(ref).append(" 方块 ").append(BuiltInRegistries.BLOCK.getKey(mod.level().getBlockState(block.getBlockPos()).getBlock()))
                    .append(" 位置=").append(block.getBlockPos().toShortString())
                    .append(" 面=").append(block.getDirection()).append(" 状态=")
                    .append(mod.level().getBlockState(block.getBlockPos())).append("\n");
        }
        out.append(visibleEntities(SceneObservation.NEAR, "", false)).append("\n");
        out.append(visibleInteractions("")).append("\n");
        SceneObservation.surroundings(mod);
        out.append(mod.playerRoutine().atlas.local).append("\n");
        return out.append("seen- 和 mob- 引用在本任务内可复用。其余引用属于当前观察；装备使用 item_ref，瞄准或目标交互使用 target_ref。").toString();
    }

    private boolean visible(Entity e) {
        return VisibleEntityTargets.visiblePoint(mod, e) != null;
    }

    private static String describe(ItemStack stack) {
        return stack.isEmpty() ? "空手" : BuiltInRegistries.ITEM.getKey(stack.getItem()) + " ×" + stack.getCount()
                + " " + stack.getHoverName().getString() + " 耐久损耗=" + stack.getDamageValue();
    }

    public ActionTask prepare(Request request) {
        ActionTask previous = actions.get(request.id());
        if (previous != null) {
            if (!previous.request.equals(request)) throw new IllegalArgumentException("action_id 已用于不同参数");
            return previous;
        }
        checkNewId(request.id());
        var task = createAction(request);
        actions.put(request.id(), task);
        return task;
    }

    ActionTask createAction(Request request) {
        boolean landmark = request.target().startsWith("seen-");
        boolean mobile = request.target().startsWith("mob-");
        if (!landmark && !mobile && !Set.of("press","hold").contains(request.action()) && (world != mod.level() || connection != mod.getPlayer().connection
                || mod.getPlayer().tickCount - observedAt > 1200)) throw new IllegalArgumentException("请先 observe 获取当前观察");
        ItemRef item = items.get(request.item());
        Target target = targets.get(request.target());
        VisibleEntityTargets.Sight sight = mobile ? entities.resolve(mod, request.target()) : null;
        if (sight != null) target = new Target(sight.entity(), null, null);
        if (landmark) {
            var hit = landmarks.reacquire(mod, request.target());
            if (request.action().equals("use_target") && mod.getPlayer().getEyePosition().distanceTo(hit.getLocation()) > mod.getPlayer().blockInteractionRange())
                throw new IllegalArgumentException("已找到目标 " + hit.getBlockPos().toShortString() + "，请先走到它旁边的可站位置");
            target = new Target(null, hit, mod.level().getBlockState(hit.getBlockPos()));
        }
        if (Set.of("wear","equip").contains(request.action()) && item == null) throw new IllegalArgumentException("item_ref 无效");
        if (!request.target().isEmpty() && target == null) throw new IllegalArgumentException("target_ref 无效");
        if (protectSheep && request.action().equals("attack") && target != null && target.entity instanceof net.minecraft.world.entity.animal.sheep.Sheep)
            throw new IllegalArgumentException("剪毛任务需要用持剪刀的手 use_target");
        if (request.action().equals("attack") && target != null && target.entity == null)
            throw new IllegalArgumentException("本批 attack 支持实体；挖方块使用现有 break");
        return new ActionTask(request, item, target, sight);
    }

    public final class ActionTask extends Task {
        private final Request request;
        private final ItemRef item;
        private final Target target;
        private final Object actionWorld = mod.level(), actionConnection = mod.getPlayer().connection;
        private ItemStack held;
        private int ticks, submittedAt;
        private boolean submitted, ownedUse, ownedUseKey, ownedSneak, started;
        private String result;
        private VisibleEntityTargets.Sight sight;
        private int unseenTicks, plannedTick, aimingTicks;
        private Task leg;
        private boolean approachRules, awaitingShear;
        private HitResult aimedHit;
        private net.minecraft.client.KeyMapping key;
        private String physicalKey;
        boolean scheduled;

        void scheduleHold() {
            scheduled = true;
            activeHold = this;
            mod.runUserTask(this);
        }

        private Task endKey(String reason) {
            if (key != null) RuntimeBindings.release(key);
            key = null;
            if (activeHold == this) activeHold = null;
            return finish(reason + "，实际保持 " + (submitted ? ticks - submittedAt : 0) + " tick");
        }

        // A map can pause the world on the same frame that consumes the key.
        public boolean finishAtBlockingScreen() {
            if (!request.action().equals("press") && !request.action().equals("hold")) return false;
            var mc = Minecraft.getInstance();
            if (mc.gui.screen() == null && mc.gui.overlay() == null) return false;
            if (!completed()) finishBlockedKey();
            return true;
        }

        private Task finishBlockedKey() {
            return endKey((submitted ? "observed: 已提交按键，随后观察到界面变化，" : "interrupted: 键位输入需要游戏画面，") + WorldInputScreen.describe());
        }

        private Task keyTick() {
            var mc = Minecraft.getInstance();
            if (mc.gui.overlay() != null || mc.gui.screen() != null || mod.getPlayer().containerMenu != mod.getPlayer().inventoryMenu)
                return finishBlockedKey();
            try {
                var current = RuntimeBindings.resolve(request.binding());
                if (!submitted) {
                    RuntimeBindings.press(current);
                    key = current;
                    physicalKey = current.saveString();
                    submitted = true;
                    submittedAt = ticks;
                } else if (current != key || !physicalKey.equals(current.saveString())) {
                    return endKey("interrupted: 绑定或输入状态变化");
                } else if (ticks - submittedAt >= request.ticks()) return endKey("submitted: 已释放，" + WorldInputScreen.describe());
                else if (!key.isDown()) {
                    if (mc.isWindowActive()) return endKey("interrupted: 输入状态变化");
                    key.setDown(true); // Focus changes can clear held state. Do not queue another click.
                }
            } catch (IllegalArgumentException e) { return endKey("failed: " + e.getMessage()); }
            return null;
        }

        ActionTask(Request request, ItemRef item, Target target, VisibleEntityTargets.Sight sight) {
            this.sight = sight;
            this.request = request; this.item = item; this.target = target;
        }
        public String result() { return result == null ? "running: " + request.id() : result; }
        public boolean completed() { return result != null; }
        ItemStack expectedEquipment() { return item.stack; }
        boolean stillPointingAtTarget() { return target != null && validTarget() && hitMatches(); }
        @Override public boolean isFinished(AltoClef ignored) { return completed(); }
        @Override protected void onStart(AltoClef ignored) {
            if (mod.getPlayer() == null) { finish("interrupted: 已离开世界"); return; }
            if (started) { result = "interrupted: 动作已执行过，需读取结果后重新计划"; return; }
            started = true;
            held = mod.getPlayer().getItemInHand(request.hand()).copy();
        }
        private Task finish(String value) { result = value + " action_id=" + request.id(); return null; }

        @Override protected Task onTick(AltoClef ignored) {
            if (result != null) return null;
            var player = mod.getPlayer();
            var mc = Minecraft.getInstance();
            ticks++;
            if (player == null || mod.level() != actionWorld || player.connection != actionConnection || !player.isAlive())
                return finish("interrupted: 世界、连接或玩家状态变化");
            if (request.action().equals("press") || request.action().equals("hold")) return keyTick();
            if (request.action().equals("wear")) return wear();
            if (ownedUse && player.isUsingItem()
                    && (!ItemStack.isSameItemSameComponents(held, player.getUseItem()) || player.getUsedItemHand() != request.hand())) {
                ownedUse = false;
                return finish("interrupted: 使用中的物品或手已被其他控制者改变");
            }
            if (!mc.isWindowActive() && mc.gui.screen() == null && mc.gui.overlay() == null) {
                if (ownedUseKey && player.isUsingItem() && !mc.options.keyUse.isDown())
                    mod.getInputControls().holdWithoutClick(Input.CLICK_RIGHT);
                if (ownedSneak && !mc.options.keyShift.isDown())
                    mod.getInputControls().holdWithoutClick(Input.SNEAK);
            }
            if (ownedUse && !player.isUsingItem()) {
                releaseUse();
                return finish("observed: 本次物品使用已结束；当前手=" + describe(player.getItemInHand(request.hand())));
            }
            if (submitted && !awaitingShear && !request.action().equals("equip") && !WorldInputScreen.allowsDirect())
                return finish("observed: 提交后当前界面已变化，窗口=" + player.containerMenu.containerId);
            if (submitted && request.action().equals("use_target") && target.block != null
                    && SceneObservation.visibleBlock(mod, target.block.getBlockPos())
                    && !mod.level().getBlockState(target.block.getBlockPos()).equals(target.state))
                return finish("observed: 目标方块当前状态=" + mod.level().getBlockState(target.block.getBlockPos())
                        + "；这是当前客户端观察");
            if (awaitingShear && submitted) {
                if (visible(target.entity) && ((net.minecraft.world.entity.animal.sheep.Sheep)target.entity).isSheared())
                    return finish("observed: 同一只羊已从有毛变为已剪毛，操作手=" + request.hand());
                if (ticks - submittedAt >= 30) return finish("unknown: 已用剪刀一次，未观察到同一只羊的剪毛状态变化");
                return null;
            }
            if (submitted && !request.action().equals("equip") && ticks - submittedAt >= request.ticks()) {
                releaseUse();
                return finish("submitted: 已执行一次并结束保持；当前手=" + describe(player.getItemInHand(request.hand()))
                        + "；需按后续目标变化判断效果");
            }
            if (!WorldInputScreen.allowsDirect()) return finish((submitted ? "unknown: " : "failed: ") + WorldInputScreen.describe());
            if (ticks > (sight != null && request.action().equals("use_target") ? 240 : 80)) return finish(submitted ? "unknown: 等待超时，勿盲目重试" : "failed: 无法对准或执行");
            if (request.action().equals("equip")) return equip();
            if (!submitted && !ItemStack.matches(held, player.getItemInHand(request.hand())))
                return finish("failed: 操作手物品已变化，请重新观察");
            if (!submitted && sight != null && (request.action().equals("use_target") || request.action().equals("aim"))) {
                var point = VisibleEntityTargets.visiblePoint(mod, target.entity);
                if (point == null) {
                    if (++unseenTicks > 40) return finish("failed: 最后目击处未重新看见同一实体，已停止");
                    LookHelper.lookAt(mod, sight.aim(), false);
                } else {
                    unseenTicks = 0;
                    sight = new VisibleEntityTargets.Sight(target.entity, target.entity.position(), point, player.tickCount);
                    entities.update(request.target(), sight);
                }
                if (request.action().equals("use_target") && sight.needsApproach(player.getEyePosition(), player.entityInteractionRange()))
                    return approach();
                if (leg != null) { leg = null; return null; }
                if (point == null) return null;
            }
            if (request.sneak() && !ownedSneak) {
                if (mod.getInputControls().isHeldDown(Input.SNEAK)) return finish("failed: 潜行已有控制者");
                mod.getInputControls().hold(Input.SNEAK); ownedSneak = true;
                return null;
            }
            if (target != null) {
                if (!validTarget()) return finish(submitted ? "unknown: 提交后目标失效" : "failed: 目标已变化或不可见");
                Vec3 aim = target.block != null ? target.block.getLocation()
                        : SceneObservation.visibleEntityPoint(mod, target.entity.getBoundingBox().getCenter())
                        ? target.entity.getBoundingBox().getCenter() : target.entity.getEyePosition();
                LookHelper.lookAt(mod, aim, false);
                if (player.getViewVector(1).dot(aim.subtract(player.getEyePosition()).normalize()) < .995) return null;
                if (!request.action().equals("use_item") && !request.action().equals("aim") && !hitMatches())
                    return ++aimingTicks > 30 ? finish("failed: 目标超距或准星受阻") : null;
            }
            if (request.action().equals("aim")) return finish("observed: 已对准当前可见目标");
            if (!submitted) {
                if (player.isUsingItem() || mod.getInputControls().isHeldDown(Input.CLICK_RIGHT))
                    return finish("failed: 已有物品使用动作或使用键控制者，等待其结束");
                awaitingShear = request.action().equals("use_target") && target.entity instanceof net.minecraft.world.entity.animal.sheep.Sheep
                        && held.is(net.minecraft.world.item.Items.SHEARS);
                if (awaitingShear && !((net.minecraft.world.entity.animal.sheep.Sheep)target.entity).readyForShearing())
                    return finish("failed: 这只羊当前不可剪毛");
                submitted = true; submittedAt = ticks;
                switch (request.action()) {
                    case "use_item" -> mod.getController().useItem(player, request.hand());
                    case "use_target" -> {
                        if (target.entity != null)
                            mod.getController().interact(player, target.entity, (EntityHitResult) aimedHit, request.hand());
                        else mod.getController().useItemOn(player, request.hand(), (BlockHitResult) aimedHit);
                    }
                    case "attack" -> mod.getControllerExtras().attack(target.entity);
                    default -> { return finish("failed: 未支持的动作"); }
                }
                ownedUse = player.isUsingItem() && player.getUsedItemHand() == request.hand();
                if (ownedUse) {
                    // Vanilla otherwise releases the item in handleKeybinds on the same tick.
                    // No queued click: a single-use action must not restart on completion.
                    mod.getInputControls().holdWithoutClick(Input.CLICK_RIGHT);
                    ownedUseKey = true;
                }
                return null;
            }
            return null;
        }

        private Task approach() {
            if (!approachRules) {
                mod.getBehaviour().push();
                mod.getBehaviour().avoidBlockBreaking(pos -> true);
                mod.getBehaviour().avoidBlockPlacing(pos -> true);
                approachRules = true;
            }
            if (leg == null || ticks - plannedTick >= 6) {
                var next = new adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask(BlockPos.containing(sight.feet()), 1, false);
                if (!next.equals(leg)) leg = next;
                plannedTick = ticks;
            }
            setDebugState(unseenTicks == 0 ? "接近同一可见实体" : "返回最后目击位置");
            return leg;
        }

        private Task wear() {
            var p = mod.getPlayer();
            if (!WorldInputScreen.allowsDirect())
                return finish("interrupted: " + WorldInputScreen.describe());
            int dest = switch (request.armor()) {
                case "head" -> 5; case "chest" -> 6; case "legs" -> 7; case "feet" -> 8;
                default -> throw new IllegalStateException();
            };
            var slot = p.inventoryMenu.getSlot(dest);
            if (submitted) {
                if (ItemStack.isSameItemSameComponents(slot.getItem(), item.stack))
                    return finish("observed: " + request.armor() + "=" + describe(slot.getItem()));
                return ticks-submittedAt >= 10 ? finish("unknown: 穿戴已提交，未观察到匹配装备") : null;
            }
            var source = p.getInventory().getItem(item.slot);
            if (!ItemStack.matches(source,item.stack) || !p.inventoryMenu.getCarried().isEmpty() || p.isUsingItem())
                return finish("failed: 背包栈、光标或使用状态已变化");
            if (!slot.isActive() || !slot.mayPlace(source) || !slot.mayPickup(p)) return finish("failed: 装备槽拒绝这次更换");
            if (item.slot >= 9 && item.slot != 40) return finish("failed: 先 equip 到主手，然后 observe 并用新引用 wear");
            if (source.getCount() > slot.getMaxStackSize(source) && slot.hasItem()) return finish("failed: 请先拆分物品栈以免替换时掉落");
            if (!mod.getSlotHandler().canDoSlotAction()) return ticks > 80 ? finish("failed: 等待装备槽超时") : null;
            mod.getSlotHandler().handleInventoryMouseClick(new PlayerSlot(dest), item.slot, ContainerInput.SWAP);
            submitted = true;
            submittedAt = ticks;
            return null;
        }

        private Task equip() {
            var p = mod.getPlayer();
            ItemStack actual = p.getItemInHand(request.hand());
            if (ItemStack.matches(actual, item.stack)
                    && (submitted || request.hand() == InteractionHand.MAIN_HAND && item.slot == p.getInventory().getSelectedSlot()
                    || request.hand() == InteractionHand.OFF_HAND && item.slot == 40))
                return finish("observed: 当前客户端操作手匹配所选物品栈");
            if (submitted) return ticks - submittedAt >= 10 ? finish("unknown: 装备提交后尚未观察到匹配，请重新观察") : null;
            ItemStack source = item.slot == 40 ? p.getOffhandItem() : p.getInventory().getItem(item.slot);
            if (!ItemStack.matches(source, item.stack)) return finish("failed: 所选背包栈已变化");
            if (!p.inventoryMenu.getCarried().isEmpty() || p.isUsingItem()) return finish("failed: 光标持物或正在使用物品");
            int selected = p.getInventory().getSelectedSlot();
            if (request.hand() == InteractionHand.MAIN_HAND && item.slot < 9) {
                p.getInventory().setSelectedSlot(item.slot);
            } else {
                if (!mod.getSlotHandler().canDoSlotAction()) return null;
                int windowSlot = item.slot == 40 ? 45 : item.slot < 9 ? item.slot + 36 : item.slot;
                mod.getSlotHandler().handleInventoryMouseClick(new PlayerSlot(windowSlot),
                        request.hand() == InteractionHand.OFF_HAND ? 40 : selected, ContainerInput.SWAP);
            }
            submitted = true; submittedAt = ticks;
            return null;
        }

        private boolean validTarget() {
            if (target.entity != null) return target.entity.level() == mod.level() && visible(target.entity);
            BlockPos pos = target.block.getBlockPos();
            return mod.level().hasChunkAt(pos) && mod.level().getBlockState(pos).equals(target.state)
                    && SceneObservation.surfaceHit(mod, pos, !request.target().startsWith("seen-")) != null;
        }
        private boolean hitMatches() {
            var hit = aimedHit = mod.getPlayer().raycastHitResult(1, mod.getPlayer());
            if (hit == null) return false;
            double reach = target.entity != null ? mod.getPlayer().entityInteractionRange() : mod.getPlayer().blockInteractionRange();
            if (hit.getLocation().distanceTo(mod.getPlayer().getEyePosition()) > reach) return false;
            if (target.entity != null) return hit instanceof EntityHitResult entity && entity.getEntity() == target.entity;
            return hit instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK
                    && block.getBlockPos().equals(target.block.getBlockPos()) && block.getDirection() == target.block.getDirection();
        }
        private void releaseUse() {
            if (ownedUseKey) mod.getInputControls().release(Input.CLICK_RIGHT);
            ownedUseKey = false;
            var p = mod.getPlayer();
            if (ownedUse && p != null && mod.level() == actionWorld && p.connection == actionConnection
                    && p.isUsingItem() && p.getUsedItemHand() == request.hand()
                    && ItemStack.isSameItemSameComponents(held, p.getUseItem())) mod.getController().releaseUsingItem(p);
            ownedUse = false;
        }
        @Override protected void onStop(AltoClef ignored, Task interruptTask) {
            if (key != null || activeHold == this) endKey("interrupted: 已终止输入");
            releaseUse();
            if (ownedSneak) mod.getInputControls().release(Input.SNEAK);
            ownedSneak = false;
            if (approachRules) { mod.getBehaviour().pop(); approachRules = false; }
            if (result == null) result = "interrupted: 已释放本动作输入；提交状态=" + submitted + " action_id=" + request.id();
        }
        @Override protected boolean isEqual(Task other) { return other == this; }
        @Override protected String toDebugString() { return "原子动作 " + request.action() + " " + request.id(); }
    }
}
