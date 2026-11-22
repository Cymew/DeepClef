package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.tasks.movement.OpenDoorTask;

public class OpenDoorCommand extends Command {
    public OpenDoorCommand() { super("opendoor","走近视野内的门并打开"); }

    @Override protected void call(AltoClef mod,ArgParser parser) {
        var task=new OpenDoorTask();
        Runnable done=completion();
        mod.runUserTask(task,()->{ mod.log(task.result()); done.run(); });
    }
}
