package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.*;
import adris.altoclef.tasks.movement.SwimTask;
import adris.altoclef.util.helpers.SwimNavigation;
import net.minecraft.core.BlockPos;
import java.util.Locale;

public final class SwimCommand extends Command {
    public SwimCommand() throws CommandException {
        super("swim","游泳: status | goto x y z | surface x z | ascend",
                new Arg<>(String.class,"action","status",0), new Arg<>(String.class,"x","",1,false),
                new Arg<>(String.class,"y/z","",2,false),new Arg<>(String.class,"z","",3,false));
    }
    @Override protected void call(AltoClef mod,ArgParser parser) throws CommandException {
        SwimTask task=parseTask(parser.getArgUnits());
        if(task==null) {log(SwimNavigation.status(mod));finish();return;}
        SwimTask executing=SwimTask.forExecution(mod.getUserTaskChain().getCurrentTask(),task);
        mod.runUserTask(executing,()->{log(executing.getResult());finish();});
    }
    public static SwimTask parseTask(String... args) throws CommandException {
        Arg<String> token=new Arg<>(String.class,"coordinate");
        String[] a=new String[args.length];for(int i=0;i<a.length;i++)a[i]=(String)token.parseUnit(args[i],args);
        String action=a.length==0?"status":a[0].toLowerCase(Locale.ROOT);
        try {
            if(action.equals("status")&&a.length<=1)return null;
            if(action.equals("ascend")&&a.length==1)return SwimTask.ascend();
            if(action.equals("surface")&&a.length==3)return SwimTask.surface(coordinate(a[1]),coordinate(a[2]));
            if(action.equals("goto")&&a.length==4)return SwimTask.travel(new BlockPos(coordinate(a[1]),coordinate(a[2]),coordinate(a[3])));
        } catch(NumberFormatException e) {throw new CommandException("swim 坐标必须是世界范围内的整数。");}
        throw new CommandException("用法: swim status | goto x y z | surface x z | ascend");
    }
    private static int coordinate(String s) {int v=Integer.parseInt(s);if(Math.abs((long)v)>29_999_984)throw new NumberFormatException();return v;}
}
