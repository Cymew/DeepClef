package adris.altoclef.util;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;

/** One user task owns the inputs throughout a bounded series of checked world interactions. */
public final class WorldActionSequence extends Task {
    static final List<String> ACTIONS = List.of("equip", "wear", "aim", "use_target", "use_item", "attack", "press");
    private static final Set<String> PREPARATION = Set.of("equip", "wear", "aim");
    static final int LIMIT = 4;

    public record Plan(String id, List<AtomicActions.Request> steps) {
        public static Plan parse(JsonNode args) {
            if (!args.isObject() || args.size() != 3 || !"sequence".equals(args.path("action").asText())
                    || !args.path("action_id").isTextual() || !args.path("steps").isArray())
                throw new IllegalArgumentException("sequence 需要 action_id 和 steps");
            String id = args.path("action_id").textValue();
            if (id.isBlank() || id.length() > 80) throw new IllegalArgumentException("序列 ID 长度应为 1..80");
            var steps = args.get("steps");
            if (steps.size() < 2 || steps.size() > LIMIT) throw new IllegalArgumentException("序列需要 2..4 步");
            var parsed = new ArrayList<AtomicActions.Request>();
            for (var step : steps) {
                if (!(step instanceof ObjectNode object) || object.has("action_id"))
                    throw new IllegalArgumentException("每步使用动作对象，action_id 仅填写在序列顶层");
                var action = object.path("action").asText();
                if (!ACTIONS.contains(action) || parsed.size() + 1 < steps.size() && !PREPARATION.contains(action))
                    throw new IllegalArgumentException("中间步骤限 equip/wear/aim；末步可增加 use_target/use_item/attack/press");
                parsed.add(AtomicActions.Request.parse(object.deepCopy().put("action_id", "step-" + (parsed.size() + 1))));
            }
            return new Plan(id, List.copyOf(parsed));
        }
    }

    final Plan plan;
    private final AtomicActions owner;
    private final Object level, player;
    private final List<String> receipts = new ArrayList<>();
    private final EnumMap<InteractionHand, ItemStack> equipment = new EnumMap<>(InteractionHand.class);
    private final EnumMap<EquipmentSlot, ItemStack> armor = new EnumMap<>(EquipmentSlot.class);
    private AtomicActions.ActionTask lastAim;
    private AtomicActions.ActionTask active;
    private String outcome;

    WorldActionSequence(AltoClef mod, AtomicActions owner, Plan plan) {
        this.owner = owner;
        this.plan = plan;
        level = mod.level();
        player = mod.getPlayer();
    }

    String result() {
        return (outcome == null ? "running" : outcome) + " sequence=" + plan.id()
                + " 已返回回执 " + receipts.size() + "/" + plan.steps().size()
                + (receipts.isEmpty() ? "" : "\n" + String.join("\n", receipts));
    }

    private void record(String receipt) {
        receipts.add((receipts.size() + 1) + ". " + plan.steps().get(receipts.size()).action() + " " + receipt);
    }

    private void acceptReceipt(AtomicActions.ActionTask completed) {
        var step = plan.steps().get(receipts.size());
        String receipt = completed.result();
        record(receipt);
        if (!receipt.startsWith("observed:") && !receipt.startsWith("submitted:")) {
            outcome = "序列停止";
        } else if (receipts.size() == plan.steps().size()) {
            outcome = "序列结束，以各步回执为准";
        } else if (!receipt.startsWith("observed:")) {
            outcome = "检查点未确认，后续动作未执行";
        } else if (step.action().equals("equip")) {
            equipment.put(step.hand(), completed.expectedEquipment());
        } else if (step.action().equals("wear")) {
            armor.put(EquipmentSlot.byName(step.armor()), completed.expectedEquipment());
        } else if (step.action().equals("aim")) {
            lastAim = completed;
        }
    }

    String onChainFinished(AltoClef mod) {
        if (outcome == null) onStop(mod, null);
        return result();
    }

    @Override protected void onStart(AltoClef mod) {}
    @Override protected Task onTick(AltoClef mod) {
        if (outcome != null) return null;
        var p = mod.getPlayer();
        if (p == null || p != player || mod.level() != level || !p.isAlive()) {
            onStop(mod, null);
            return null;
        }
        if (active != null) {
            if (!active.completed()) return active;
            acceptReceipt(active);
            active = null;
            // Returning null lets Task release the previous child's inputs before the next tick.
            return null;
        }
        try {
            if (!WorldInputScreen.allowsDirect()) throw new IllegalArgumentException(WorldInputScreen.describe());
            for (var entry : equipment.entrySet()) {
                if (!ItemStack.matches(entry.getValue(), p.getItemInHand(entry.getKey())))
                    throw new IllegalArgumentException("已装备的操作手发生变化");
            }
            for (var entry : armor.entrySet()) {
                if (!ItemStack.isSameItemSameComponents(entry.getValue(), p.getItemBySlot(entry.getKey())))
                    throw new IllegalArgumentException("已核验的盔甲发生变化");
            }
            var next = plan.steps().get(receipts.size());
            if (lastAim != null && next.action().equals("press") && !lastAim.stillPointingAtTarget())
                throw new IllegalArgumentException("准星已离开目标或目标超出触及范围");
            active = owner.createAction(next);
            return active;
        } catch (IllegalArgumentException e) {
            outcome = "第 " + (receipts.size() + 1) + " 步未开始：" + e.getMessage();
            return null;
        }
    }

    @Override protected void onStop(AltoClef mod, Task interruptTask) {
        if (outcome != null) return;
        if (active != null) {
            active.stop(mod, interruptTask);
            if (active.completed()) record(active.result());
            active = null;
        }
        outcome = "interrupted: 序列已停止";
    }

    @Override public boolean isFinished(AltoClef mod) { return outcome != null; }
    @Override protected boolean isEqual(Task other) { return other == this; }
    @Override protected String toDebugString() { return "交互序列 " + plan.id() + " " + receipts.size() + "/" + plan.steps().size(); }
}
