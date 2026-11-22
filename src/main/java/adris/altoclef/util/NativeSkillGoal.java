package adris.altoclef.util;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;
import java.util.regex.Pattern;

/** One session-local absolute objective. Never stores a task instance to replay. */
final class NativeSkillGoal {
    private static final Pattern GET = Pattern.compile("get\\s+([a-z0-9_]+)(?:\\s+([1-9][0-9]*))?");
    private static final Pattern GOTO = Pattern.compile("goto(?:\\s+-?[0-9]+){2,3}");
    final String id = UUID.randomUUID().toString();
    final String command;
    final ItemTarget item;
    boolean keepStoredOutput;
    private int delivered;
    private Delivery baseline;

    record Delivery(Object menu, int stored, int carried) { }

    private NativeSkillGoal(String command, ItemTarget item) {
        this.command = command;
        this.item = item;
    }

    static NativeSkillGoal parse(String command) {
        if (GOTO.matcher(command).matches()) {
            try {
                for (String axis : command.substring(4).strip().split("\\s+")) Integer.parseInt(axis);
                return new NativeSkillGoal(command, null);
            } catch (NumberFormatException ignored) { return null; }
        }
        var m = GET.matcher(command);
        if (!m.matches()) return null;
        try {
            int total = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            var item = new ItemTarget(m.group(1), total);
            return item.isEmpty() ? null : new NativeSkillGoal(command, item);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    boolean atDestination(net.minecraft.core.BlockPos pos) {
        String[] words = command.split("\\s+");
        return pos.getX() == Integer.parseInt(words[1]) && pos.getZ() == Integer.parseInt(words[words.length - 1])
                && (words.length == 3 || pos.getY() == Integer.parseInt(words[2]));
    }

    int count(Container container) {
        int sum = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && item.matches(stack.getItem())) sum += stack.getCount();
        }
        return sum;
    }

    int inventoryTarget() { return item.getTargetCount() - delivered; }
    boolean hasDelivery() { return baseline != null; }
    boolean awaitingRemainingDelivery() { return credited() && inventoryTarget() > 0; }
    boolean credited() { return delivered > 0; }

    String describe() {
        return "goal_id=" + id + " 原目标=" + command + (item == null ? "" :
                "，已交付=" + delivered + "，背包目标=" + inventoryTarget())
                + (baseline == null ? "" : "，当前交付窗口待核对");
    }

    void beginDelivery(Delivery now) {
        if (baseline == null) baseline = now;
        else if (baseline.menu != now.menu) throw new IllegalArgumentException("交付窗口已更换，无法核对原转移；核实容器后重新规划");
    }

    int verifyDelivery(Delivery now) {
        if (baseline == null || baseline.menu != now.menu)
            throw new IllegalArgumentException("需要同一已登记的交付窗口");
        int gain = now.stored - baseline.stored;
        if (gain <= 0 || gain != baseline.carried - now.carried || gain > inventoryTarget())
            throw new IllegalArgumentException("交付尚未核对：箱内净增 " + gain + "，背包净减 "
                    + (baseline.carried - now.carried) + "，余量 " + inventoryTarget());
        return gain;
    }

    void commitDelivery(Delivery now) {
        delivered += verifyDelivery(now);
        baseline = null;
    }
}
