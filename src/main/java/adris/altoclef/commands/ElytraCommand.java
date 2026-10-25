package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.tasks.movement.ElytraFlightTask;

import java.util.Locale;

/** Explicit bounded elytra travel and landing, sharing parsing with the model tool. */
public final class ElytraCommand extends Command {
    public ElytraCommand() throws CommandException {
        super("elytra", "鞘翅：status | goto <x> <z> | land",
                new Arg<>(String.class, "action", "status", 0),
                new Arg<>(String.class, "x", "", 1, false), new Arg<>(String.class, "z", "", 2, false));
    }

    @Override protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        ElytraFlightTask requested = parseTask(parser.getArgUnits());
        if (requested == null) { log(ElytraFlightTask.status(mod)); finish(); return; }
        ElytraFlightTask task = ElytraFlightTask.forExecution(mod.getUserTaskChain().getCurrentTask(), requested);
        mod.runUserTask(task, () -> { log(task.getResult()); finish(); });
    }

    public static ElytraFlightTask parseTask(String... arguments) throws CommandException {
        Arg<String> token = new Arg<>(String.class, "elytra");
        String[] values = new String[arguments.length];
        for (int index = 0; index < values.length; index++) values[index] = (String) token.parseUnit(arguments[index], arguments);
        String action = values.length == 0 ? "status" : values[0].toLowerCase(Locale.ROOT);
        try {
            return switch (action) {
                case "status" -> {
                    if (values.length > 1) throw new CommandException("用法：elytra status");
                    yield null;
                }
                case "goto" -> {
                    if (values.length != 3) throw new CommandException("用法：elytra goto <x> <z>");
                    int x = Integer.parseInt(values[1]), z = Integer.parseInt(values[2]);
                    if (Math.abs((long) x) > 29_999_984 || Math.abs((long) z) > 29_999_984)
                        throw new CommandException("鞘翅目的地超出世界坐标范围。");
                    yield ElytraFlightTask.travel(x, z);
                }
                case "land" -> {
                    if (values.length != 1) throw new CommandException("用法：elytra land");
                    yield ElytraFlightTask.land();
                }
                default -> throw new CommandException("未知鞘翅操作；可用 status、goto、land。");
            };
        } catch (NumberFormatException ex) { throw new CommandException("鞘翅 XZ 坐标必须是整数。"); }
    }
}
