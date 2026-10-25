package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.Settings;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.util.helpers.ConfigHelper;

/**
 * {@code @dssize <缩放>} — adjusts the font size of the {@code @ds} thinking
 * overlay (0.4 ~ 2.0, default 0.75). Persisted to settings, applied immediately.
 */
public class DssizeCommand extends Command {

    public DssizeCommand() throws CommandException {
        super("dssize", "设置 @ds 思考叠加层的字号（0.4~2.0，默认 0.75）", new Arg<>(Float.class, "scale"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        float requested = parser.get(Float.class);
        mod.getModSettings().setDeepSeekOverlayScale(requested);
        ConfigHelper.saveConfig(Settings.SETTINGS_PATH, mod.getModSettings());
        mod.log("思考叠加层字号已设为 " + mod.getModSettings().getDeepSeekOverlayScale() + "（立即生效）");
        finish();
    }
}
