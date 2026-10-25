package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.util.DeepSeekAgent;

public class StopCommand extends Command {

    public StopCommand() {
        super("stop", "Stop task runner (stops all automation)");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        DeepSeekAgent.cancel();
        if (mod.getChatService() != null) mod.getChatService().pauseAfterStop();
        mod.getUserTaskChain().cancel(mod);
        finish();
    }
}
