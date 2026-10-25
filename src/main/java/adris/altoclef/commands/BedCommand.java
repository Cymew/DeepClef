package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.tasks.misc.PlaceBedAndSetSpawnTask;

/**
 * {@code @bed} — finds a bed (or crafts/places one) and sets the spawn point.
 */
public class BedCommand extends Command {

    public BedCommand() {
        super("bed", "找到/放置一张床并设置重生点（睡觉）");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        mod.runUserTask(new PlaceBedAndSetSpawnTask(), this::finish);
    }
}
