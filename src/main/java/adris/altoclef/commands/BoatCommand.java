package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.tasks.movement.BoatControlTask;

import java.util.Locale;

/** Explicit boat operations; an unspecified vessel can also be launched from inventory. */
public final class BoatCommand extends Command {
    public BoatCommand() throws CommandException {
        super("boat", "船操作：status | board [实体ID] | goto <x> <z> | dismount",
                new Arg<>(String.class, "action", "status", 0),
                new Arg<>(String.class, "entityId或x", "", 1, false),
                new Arg<>(String.class, "z", "", 2, false));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        BoatControlTask requested = parseTask(parser.getArgUnits());
        if (requested == null) {
            log(BoatControlTask.status(mod));
            finish();
            return;
        }
        BoatControlTask task = BoatControlTask.forExecution(mod.getUserTaskChain().getCurrentTask(), requested);
        mod.runUserTask(task, () -> {
            log(task.getResult());
            finish();
        });
    }

    /** Shared by the registered command and model macros. Null denotes read-only status. */
    public static BoatControlTask parseTask(String... arguments) throws CommandException {
        int count = arguments.length;
        Arg<String> token = new Arg<>(String.class, "boat");
        String[] values = new String[count];
        for (int index = 0; index < count; index++)
            values[index] = (String) token.parseUnit(arguments[index], arguments);
        String action = count == 0 ? "status" : values[0].toLowerCase(Locale.ROOT);
        try {
            return switch (action) {
                case "status" -> {
                    if (count > 1) throw new CommandException("用法：boat status");
                    yield null;
                }
                case "board" -> {
                    if (count > 2) throw new CommandException("用法：boat board [实体ID]");
                    yield BoatControlTask.board(count == 1 ? null : Integer.valueOf(values[1]));
                }
                case "goto" -> {
                    if (count != 3) throw new CommandException("用法：boat goto <x> <z>（只填 XZ）");
                    int x = Integer.parseInt(values[1]), z = Integer.parseInt(values[2]);
                    if (Math.abs((long) x) > 29_999_984 || Math.abs((long) z) > 29_999_984)
                        throw new CommandException("船目的地超出世界坐标范围。");
                    yield BoatControlTask.travel(x, z, null);
                }
                case "dismount" -> {
                    if (count != 1) throw new CommandException("用法：boat dismount");
                    yield BoatControlTask.dismount();
                }
                default -> throw new CommandException("未知船操作；可用 status、board、goto、dismount。");
            };
        } catch (NumberFormatException ex) {
            throw new CommandException("船实体 ID 和 XZ 坐标必须是整数。");
        }
    }
}
