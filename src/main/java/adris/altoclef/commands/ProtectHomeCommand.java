package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.*;

public final class ProtectHomeCommand extends Command {
    public ProtectHomeCommand() throws CommandException {
        super("protecthome", "保存本维度家园禁拆范围（含边界，全高度）", new Arg<>(Integer.class,"x1"),
                new Arg<>(Integer.class,"z1"), new Arg<>(Integer.class,"x2"), new Arg<>(Integer.class,"z2"));
    }
    @Override protected void call(AltoClef mod, ArgParser p) throws CommandException {
        var area = new adris.altoclef.util.HomeArea(p.get(Integer.class), p.get(Integer.class), p.get(Integer.class), p.get(Integer.class));
        try { area.save(mod); } catch (java.io.IOException e) { throw new CommandException("保存禁拆范围失败：" + e.getMessage()); }
        mod.log("已保存本维度家园禁拆范围：" + area);
        finish();
    }
}
