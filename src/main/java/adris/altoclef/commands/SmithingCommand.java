package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.tasks.container.SmithingTask;
import adris.altoclef.tasks.resources.DuplicateSmithingTemplateTask;
import adris.altoclef.tasks.resources.GetSmithingTemplateTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.SmithingTemplateCatalogue;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** One parser and result contract for chat commands and the model's smithing tool. */
public final class SmithingCommand extends Command {
    public SmithingCommand() throws CommandException {
        super("smithing", "锻造：status | templates | source/get/duplicate <模板> [数量] | upgrade <钻石装备> [数量] | trim <盔甲> <模板> <材料> [数量]",
                new Arg<>(String.class, "action", "status", 0),
                new Arg<>(String.class, "item", "", 1, false),
                new Arg<>(String.class, "template", "", 2, false),
                new Arg<>(String.class, "material", "", 3, false),
                new Arg<>(String.class, "count", "", 4, false));
    }

    @Override protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        Task requested = parseTask(parser.getArgUnits());
        if (requested == null) { log(readOnlyText(mod, parser.getArgUnits())); finish(); return; }
        Task executing = forExecution(mod.getUserTaskChain().getCurrentTask(), requested);
        mod.runUserTask(executing, () -> { log(result(executing)); finish(); });
    }

    public static Task parseTask(String... arguments) throws CommandException {
        String[] values = normalized(arguments);
        String action = values.length == 0 ? "status" : values[0];
        try {
            return switch (action) {
                case "status", "templates" -> { arity(values, 0, 1, "smithing " + action); yield null; }
                case "source" -> { arity(values, 2, 2, "smithing source <模板>"); template(values[1]); yield null; }
                case "get", "duplicate" -> {
                    arity(values, 2, 3, "smithing " + action + " <模板> [数量]");
                    Item item = template(values[1]).item();
                    int count = values.length == 3 ? count(values[2]) : 1;
                    yield action.equals("get") ? new GetSmithingTemplateTask(item, count)
                            : GetSmithingTemplateTask.duplicate(item, count);
                }
                case "upgrade" -> {
                    arity(values, 2, 3, "smithing upgrade <钻石装备ID> [目标数量]");
                    yield SmithingTask.upgrade(item(values[1]), values.length == 3 ? count(values[2]) : 1);
                }
                case "trim" -> {
                    arity(values, 4, 5, "smithing trim <盔甲ID> <纹饰模板> <材料ID> [目标数量]");
                    var entry = template(values[2]);
                    if (entry.key().equals("netherite_upgrade")) throw new CommandException("下界合金升级模板不能用于盔甲纹饰。");
                    yield SmithingTask.trim(item(values[1]), entry.item(), item(values[3]), values.length == 5 ? count(values[4]) : 1);
                }
                default -> throw new CommandException("未知锻造操作；可用status、templates、source、get、duplicate、upgrade、trim。");
            };
        } catch (IllegalArgumentException ex) { throw new CommandException("锻造参数错误：" + ex.getMessage()); }
    }

    public static String readOnlyText(AltoClef mod, String... arguments) throws CommandException {
        if (parseTask(arguments) != null) throw new CommandException("该锻造操作需要执行任务。");
        String[] values = normalized(arguments);
        String action = values.length == 0 ? "status" : values[0];
        if (action.equals("status")) return SmithingTask.status(mod);
        if (action.equals("templates")) return "当前版本共" + SmithingTemplateCatalogue.entries().size() + "种锻造模板：\n"
                + SmithingTemplateCatalogue.entries().stream().map(entry -> entry.key() + "：" + entry.sourceDescription())
                .collect(Collectors.joining("\n"));
        var entry = template(values[1]);
        String materials = Arrays.stream(entry.duplicationMaterials()).map(value -> BuiltInRegistries.ITEM.getKey(value).toString())
                .collect(Collectors.joining(" 或 "));
        return entry.key() + "：" + entry.sourceDescription() + "\n复制：1份原模板 + 7钻石 + 1份(" + materials
                + ")，产出2份（净增1）；先有首份才能复制。";
    }

    public static Task forExecution(Task current, Task requested) {
        return current != null && current.equals(requested) ? current : requested;
    }

    public static String result(Task task) {
        if (task instanceof SmithingTask smithing) return smithing.getResult();
        if (task instanceof GetSmithingTemplateTask getting) return getting.getResult();
        if (task instanceof DuplicateSmithingTemplateTask duplicate) return duplicate.getResult();
        return "锻造操作已中断。";
    }

    private static SmithingTemplateCatalogue.Entry template(String value) throws CommandException {
        var entry = SmithingTemplateCatalogue.find(value);
        if (entry == null) throw new CommandException("未知锻造模板：" + value + "；使用smithing templates查看。");
        return entry;
    }

    private static Item item(String value) throws CommandException {
        Identifier id = Identifier.tryParse(value.contains(":") ? value : "minecraft:" + value);
        var found = id == null ? null : BuiltInRegistries.ITEM.get(id).orElse(null);
        if (found == null) throw new CommandException("未知物品ID：" + value);
        return found.value();
    }

    private static int count(String value) throws CommandException {
        try {
            int count = Integer.parseInt(value);
            if (count < 1 || count > 64) throw new NumberFormatException();
            return count;
        } catch (NumberFormatException ex) { throw new CommandException("锻造数量必须是1～64的整数。"); }
    }

    private static void arity(String[] values, int min, int max, String usage) throws CommandException {
        if (values.length < min || values.length > max) throw new CommandException("用法：" + usage);
    }

    private static String[] normalized(String[] arguments) throws CommandException {
        Arg<String> token = new Arg<>(String.class, "smithing");
        String[] values = new String[arguments.length];
        for (int index = 0; index < values.length; index++)
            values[index] = ((String)token.parseUnit(arguments[index], arguments)).trim().toLowerCase(Locale.ROOT);
        return values;
    }
}
