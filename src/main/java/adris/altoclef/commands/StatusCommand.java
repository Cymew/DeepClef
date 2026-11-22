package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.util.DeepSeekAgent;

public class StatusCommand extends Command {
    public StatusCommand() {
        super("status", "Get status of currently executing command");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        var task = mod.getUserTaskChain().getCurrentTask();
        mod.log(mod.worldMode().label + (mod.backgroundPaused() ? "，后台已暂停（@resume 恢复）。" : "。"));
        mod.log("当前方块坐标 " + mod.getPlayer().blockPosition().toShortString());
        mod.log(mod.scene().describe(mod));
        if (mod.worldMode() == adris.altoclef.mode.WorldMode.PLAYER) {
            mod.log(mod.playerRoutine().status(mod));
        } else if (DeepSeekAgent.isManuallyPaused()) {
            mod.log("模型任务已暂停，可用 @ds resume 续做。");
        } else if (task == null) {
            mod.log(DeepSeekAgent.isBusy() ? "模型会话运行中，当前没有移动或操作任务。" : "当前没有任务。");
        } else {
            mod.log("当前任务 " + task);
        }
        finish();
    }
}
