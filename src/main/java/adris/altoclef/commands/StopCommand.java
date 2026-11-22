package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;

public class StopCommand extends Command {

    public StopCommand() {
        super("stop", "Stop task runner (stops all automation)");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        mod.stopAutomation();
        mod.log("已停止。可继续手动游玩、下达新任务，或用 @resume 恢复后台行为。");
        finish();
    }
}
