package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.tasks.construction.compound.ConstructNetherPortalBucketTask;
import adris.altoclef.tasks.movement.EnterNetherPortalTask;
import adris.altoclef.util.Dimension;
import adris.altoclef.util.helpers.WorldHelper;

import java.util.Locale;

/**
 * {@code @portal} — go to a nether portal, building one by bucket casting when
 * none exists. {@code @portal cast} forces the casting build at the current spot.
 *
 * <p>Casting needs a water bucket and lava nearby (the bot also gathers flint and
 * steel, buckets and lava itself). A diamond pickaxe is only needed for the
 * obsidian-mining route used when water/lava are not available.
 */
public class PortalCommand extends Command {

    public PortalCommand() {
        super("portal", "浇筑/前往下界传送门（水桶+岩浆，不需要钻石镐；cast 就地浇筑）");
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) {
        String mode = String.join(" ", parser.getArgUnits()).trim().toLowerCase(Locale.ROOT);
        if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
            mod.logWarning("@portal 只能在主世界浇筑下界传送门（当前维度: "
                    + WorldHelper.getCurrentDimension() + "）。");
            finish();
            return;
        }
        if (mode.contains("cast") || mode.contains("浇筑") || mode.contains("build")) {
            mod.log("开始浇筑下界传送门：需要水桶 + 附近岩浆，会自动搭浇筑框、舀岩浆和水、点火。");
            mod.runUserTask(new ConstructNetherPortalBucketTask(), this::finish);
            return;
        }
        mod.log("前往下界：优先使用已有传送门，没有就用水桶 + 岩浆浇筑一个（不需要钻石镐）。");
        mod.runUserTask(new EnterNetherPortalTask(Dimension.NETHER), this::finish);
    }
}
