package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.commandsystem.*;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.ui.MessagePriority;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.SmithingTaskOutcome;

public class GetCommand extends Command {
    private long smithingFailureRevision;
    private long harvestRevision;
    private String harvestReceipt;
    public long harvestRevision() { return harvestRevision; }
    public String harvestReceipt() { return harvestReceipt; }
    private String smithingFailure;

    public long getSmithingFailureRevision() { return smithingFailureRevision; }
    public String getSmithingFailure() { return smithingFailure; }

    public GetCommand() throws CommandException {
        super("get", "Get an item/resource",
                new Arg(ItemList.class, "items"));
    }

    private static void OnResourceDoesNotExist(AltoClef mod, String resource) {
        mod.log("\"" + resource + "\" is not a catalogued resource. Can't get it yet, sorry! If it's a generic block try using baritone.", MessagePriority.OPTIONAL);
        mod.log("Use @list to get a list of available resources.", MessagePriority.OPTIONAL);
    }

    private void GetItems(AltoClef mod, ItemTarget... items) {
        Task targetTask;
        if (items == null || items.length == 0) {
            mod.log("You must specify at least one item!");
            finish();
            return;
        }
        if (items.length == 1) {
            targetTask = TaskCatalogue.getItemTask(items[0]);
        } else {
            targetTask = TaskCatalogue.getSquashedItemTask(items);
        }
        if (targetTask != null) {
            Task executing = SmithingCommand.forExecution(mod.getUserTaskChain().getCurrentTask(), targetTask);
            Runnable done = completion();
            mod.runUserTask(executing, () -> {
                String failure = SmithingTaskOutcome.failure(executing, mod);
                if (failure != null) {
                    smithingFailure = failure;
                    smithingFailureRevision++;
                    mod.log(failure);
                }
                done.run();
            });
        } else {
            finish();
        }
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String[] args = parser.getArgUnits();
        if (args.length > 0 && args[0].equals("harvest")) {
            if (args.length != 4 && args.length != 5) throw new CommandException("用法：get harvest cherry_log 128 48 [距家最少格数]");
            int radius, count, distance;
            try { radius = Integer.parseInt(args[3]); count = Integer.parseInt(args[2]); distance = args.length == 5 ? Integer.parseInt(args[4]) : 0; }
            catch (NumberFormatException ex) { throw new CommandException("数量和半径须为整数"); }
            var id = net.minecraft.resources.Identifier.tryParse(args[1]);
            var item = id == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            if (distance < 0 || radius < 1 || radius > 128 || count < 1 || item == null
                    || !adris.altoclef.tasks.resources.LocalTimber.rawLog(item))
                throw new CommandException("请指定一种天然原木、正数量及 1 至 128 格半径");
            var home = adris.altoclef.util.HomeArea.current();
            if (distance > 0 && (home == null || !home.atLeast(mod.getPlayer().blockPosition(),distance)))
                throw new CommandException("需先登记家园并抵达指定距离外的林地");
            var harvest = new adris.altoclef.tasks.resources.LocalTimber(item,count,radius,distance);
            var done = completion();
            mod.runUserTask(harvest, () -> {
                harvestReceipt = harvest.receipt();
                harvestRevision++;
                done.run();
            });
            return;
        }
        ItemList items = parser.get(ItemList.class);
        GetItems(mod, items.items);
    }
}
