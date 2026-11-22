package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;

public class ResumeCommand extends Command {
    public ResumeCommand() { super("resume", "恢复当前模式的后台行为"); }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        mod.resumeBackground();
        mod.log(mod.worldMode().label + "已恢复。");
        finish();
    }
}
