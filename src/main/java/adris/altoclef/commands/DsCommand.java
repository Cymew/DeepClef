package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.ui.DeepSeekKeyScreen;
import adris.altoclef.util.DeepSeekAgent;
import net.minecraft.client.Minecraft;

/**
 * {@code @ds <自然语言指令>} — drives the bot with natural language through the
 * DeepSeek API. The model plans, executes DeepClef commands via function
 * calling, adjusts on feedback, and streams its deep thinking to the right-side
 * overlay.
 */
public class DsCommand extends Command {

    public DsCommand() throws CommandException {
        super("ds", "用自然语言控制机器人（DeepSeek）", new Arg(String.class, "自然语言指令"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String instruction = String.join(" ", parser.getArgUnits()).trim();

        String[] words = instruction.split("\\s+");
        if (words.length > 0 && words[0].equalsIgnoreCase("chat")) {
            if (mod.getChatService() == null) {
                mod.logWarning("多人聊天服务尚未初始化。");
            } else if (words.length == 1 || words.length == 2 && words[1].equalsIgnoreCase("status")) {
                mod.log(mod.getChatService().status());
            } else if (words.length == 2 && words[1].equalsIgnoreCase("on")) {
                mod.log(mod.getChatService().setAutomatic(true));
            } else if (words.length == 2 && words[1].equalsIgnoreCase("off")) {
                mod.log(mod.getChatService().setAutomatic(false));
            } else {
                mod.logWarning("用法: @ds chat on/off/status");
            }
            finish();
            return;
        }

        if (!mod.getModSettings().hasDeepSeekApiKey()) {
            mod.logWarning("尚未配置 DeepSeek API Key，请在弹出的界面中输入。");
            Minecraft.getInstance().execute(() -> Minecraft.getInstance().gui.setScreen(new DeepSeekKeyScreen(mod.getModSettings())));
            finish();
            return;
        }
        if (instruction.isEmpty()) {
            mod.logWarning("用法: @ds <自然语言指令>，例如 @ds 帮我挖64个铁锭");
            finish();
            return;
        }
        if (DeepSeekAgent.isBusy()) {
            if (!DeepSeekAgent.isSessionValid(mod)) {
                // The running agent belongs to another save; never answer with it.
                DeepSeekAgent.cancel();
                mod.logWarning("上一个存档的 @ds 任务已终止，请重新发送指令。");
                finish();
                return;
            }
            // Interjection: inject the new instruction into the running task.
            DeepSeekAgent.interject(instruction);
            mod.log("已插话给正在运行的 @ds 任务: " + instruction);
            finish();
            return;
        }
        DeepSeekAgent.start(mod, instruction, this::finish);
    }
}
