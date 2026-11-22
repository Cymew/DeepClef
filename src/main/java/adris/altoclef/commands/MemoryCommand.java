package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.*;

import adris.altoclef.util.DeepSeekAgent;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class MemoryCommand extends Command {
    public MemoryCommand() throws CommandException {
        super("memory","查询本世界记录或角色经验",new Arg<>(String.class,"操作与查询","",0));
    }
    @Override protected void call(AltoClef mod,ArgParser parser) {
        Runnable done=completion();
        Consumer<String> show=s->{adris.altoclef.Debug.logMessage(s);done.run();};
        if(mod.getButler().hasCurrentUser()) {show.accept("记忆命令仅支持本地使用。");return;}
        String[] words=String.join(" ",parser.getArgUnits()).strip().split("\\s+",2);
        String value=words.length==2?words[1].strip():"";
        if (words[0].equalsIgnoreCase("seed")) {
            show.accept(adris.altoclef.memory.SeedKnowledge.book().lookup(value));
            return;
        }
        var memory=mod.memory();
        if(memory.scope()==null) {show.accept("当前世界的角色身份尚未就绪。");return;}
        if(value.length()>160) {show.accept("查询或标记过长。");return;}
        Consumer<List<String>> lines=r->show.accept(r.isEmpty()?"没有匹配记录。":String.join("\n",r));
        switch(words[0].toLowerCase(Locale.ROOT)) {
            case "map" -> {
                String dim=mod.level().dimension().identifier().toString();
                memory.query(db->db.mapContext(dim,value),"地图不可用。",s->show.accept(s.isEmpty()?"本维度暂无匹配地点。":s));
            }
            case "world" -> memory.query(db->db.world(value,5),List.<String>of("世界记录不可用。"),lines);
            case "recall" -> memory.query(db->db.recall(value,5),List.<String>of("角色记忆不可用。"),lines);
            case "status" -> memory.query(db->db.status(),"记忆状态不可用。",show);
            case "note" -> {
                if(value.isEmpty()) show.accept("用法 @memory note <本世界的项目进度>");
                else memory.note(value,show);
            }
            case "mark" -> {
                if(value.isEmpty() || value.length()>40) show.accept("用法 @memory mark <1–40字的地标名>");
                else memory.mark(value,show);
            }
            case "forget" -> {
                String id=value.toUpperCase(Locale.ROOT);
                memory.query(db->{db.forget(id);return "已遗忘 "+id;},"请填写 @memory recall 显示的记忆 ID。",show);
            }
            case "import-legacy" -> DeepSeekAgent.importLegacy(mod,show);
            default -> show.accept("@memory seed <主题或卡片ID> / map [地标名] / world [关键词] / recall [关键词] / mark <地标> / note <进度> / status / forget <ID> / import-legacy");
        }
    }
}
