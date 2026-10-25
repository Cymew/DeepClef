package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.tasks.entity.PvpKillPlayerTask;
import adris.altoclef.util.helpers.CombatPreferences;

/**
 * {@code @punk <玩家名> [auto|sword|bow|spear]} — PvP 击杀指定玩家。
 * 可选的第二个参数指定攻击方式（不填则沿用当前设置，默认 auto）。
 */
public class PunkCommand extends Command {
    public PunkCommand() throws CommandException {
        super("punk", "PvP 击杀指定玩家（可指定 sword/bow/spear 攻击方式）", new Arg(String.class, "玩家名"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String playerName = parser.get(String.class);

        // Optional trailing style argument; tolerant to whichever tokens the
        // parser leaves in getArgUnits().
        StringBuilder style = new StringBuilder();
        for (String unit : parser.getArgUnits()) {
            String token = unit == null ? "" : unit.trim();
            if (token.isEmpty() || token.equalsIgnoreCase(playerName)) continue;
            if (style.length() > 0) style.append(' ');
            style.append(token);
        }
        if (style.length() > 0) {
            CombatPreferences.setStyle(CombatPreferences.parse(style.toString()));
        }

        mod.log("PvP 目标: " + playerName + "，攻击方式: " + CombatPreferences.describe());
        mod.runUserTask(new PvpKillPlayerTask(playerName), this::finish);
    }
}
