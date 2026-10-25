package adris.altoclef.commandsystem;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds a Brigadier mirror of altoclef's {@code @} commands so that the vanilla
 * chat suggestion UI (the same one {@code /} commands use) can auto-complete them:
 * typing {@code @} pops up every command, {@code @ge} suggests {@code get}/{@code give}/...,
 * arguments get suggestions (catalogued resources, enum values, coordinates...),
 * tab fills, arrows cycle and mouse clicks work — all through the vanilla UI.
 * <p>
 * The tree mirrors vanilla {@code /} handling: commands hang directly off the
 * dispatcher root, and the {@code @} prefix is skipped in the reader before parsing
 * (exactly like vanilla skips the {@code /}).
 */
public final class CommandAutoCompleter {

    private static CommandDispatcher<ClientSuggestionProvider> _dispatcher = null;
    private static int _revision = -1;

    private CommandAutoCompleter() {
    }

    /**
     * The altoclef command prefix (from settings, defaults to {@code "@"}).
     * Never returns null or an empty string.
     */
    public static String getPrefix() {
        try {
            CommandExecutor executor = AltoClef.getCommandExecutor();
            if (executor != null) {
                String prefix = executor.getCommandPrefix();
                if (prefix != null && !prefix.isEmpty()) {
                    return prefix;
                }
            }
        } catch (Throwable ignored) {
        }
        return "@";
    }

    /**
     * A dispatcher with one literal subtree per altoclef command hanging directly off
     * the root (like vanilla {@code /} commands). The command prefix is handled by the
     * caller, which skips it in the reader before parsing. Rebuilt whenever new commands
     * are registered.
     */
    public static CommandDispatcher<ClientSuggestionProvider> getDispatcher() {
        try {
            CommandExecutor executor = AltoClef.getCommandExecutor();
            if (executor != null && (_dispatcher == null || _revision != executor.getRevision())) {
                _revision = executor.getRevision();
                _dispatcher = build(executor);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return _dispatcher;
    }

    private static CommandDispatcher<ClientSuggestionProvider> build(CommandExecutor executor) {
        CommandDispatcher<ClientSuggestionProvider> dispatcher = new CommandDispatcher<>();
        for (Command command : executor.allCommands()) {
            String name = command.getName();
            if (name == null || name.isEmpty()) {
                continue;
            }
            LiteralArgumentBuilder<ClientSuggestionProvider> literal = LiteralArgumentBuilder.literal(name);
            ArgBase[] args = command.getArgList();
            ArgumentBuilder<ClientSuggestionProvider, ?> current = literal;
            int index = 0;
            for (ArgBase arg : args) {
                ArgumentBuilder<ClientSuggestionProvider, ?> argNode = buildArgNode(arg, index);
                current.then(argNode);
                current = argNode;
                ++index;
            }
            dispatcher.register(literal);
        }
        return dispatcher;
    }

    private static ArgumentBuilder<ClientSuggestionProvider, ?> buildArgNode(ArgBase arg, int index) {
        Class<?> type = arg.getType();
        String name = arg.getName();
        if (name == null || name.isEmpty()) {
            name = "arg" + index;
        }
        RequiredArgumentBuilder<ClientSuggestionProvider, ?> builder;
        if (type == Integer.class) {
            builder = RequiredArgumentBuilder.argument(name, IntegerArgumentType.integer());
        } else if (type == Long.class) {
            builder = RequiredArgumentBuilder.argument(name, LongArgumentType.longArg());
        } else if (type == Float.class) {
            builder = RequiredArgumentBuilder.argument(name, FloatArgumentType.floatArg());
        } else if (type == Double.class) {
            builder = RequiredArgumentBuilder.argument(name, DoubleArgumentType.doubleArg());
        } else if (type == ItemList.class) {
            builder = RequiredArgumentBuilder.argument(name, StringArgumentType.greedyString());
            builder.suggests(itemSuggestions());
        } else if (type == GotoTarget.class) {
            builder = RequiredArgumentBuilder.argument(name, StringArgumentType.greedyString());
            builder.suggests(gotoSuggestions());
        } else if (type != null && type.isEnum()) {
            builder = RequiredArgumentBuilder.argument(name, StringArgumentType.word());
            builder.suggests(enumSuggestions(type));
        } else {
            builder = RequiredArgumentBuilder.argument(name, StringArgumentType.word());
        }
        // Optional arguments: the parser is allowed to stop here.
        if (arg.hasDefault()) {
            builder.executes(ctx -> 0);
        }
        return builder;
    }

    private static SuggestionProvider<ClientSuggestionProvider> itemSuggestions() {
        return (ctx, builder) -> {
            List<String> names = new ArrayList<>();
            try {
                names.addAll(TaskCatalogue.resourceNames());
            } catch (Throwable ignored) {
            }
            return SharedSuggestionProvider.suggest(names, builder);
        };
    }

    private static SuggestionProvider<ClientSuggestionProvider> gotoSuggestions() {
        return (ctx, builder) -> {
            List<String> names = new ArrayList<>();
            try {
                LocalPlayer player = Minecraft.getInstance().player;
                if (player != null) {
                    names.add(((int) player.getX()) + " " + ((int) player.getY()) + " " + ((int) player.getZ()));
                }
            } catch (Throwable ignored) {
            }
            names.add("overworld");
            names.add("nether");
            names.add("end");
            return SharedSuggestionProvider.suggest(names, builder);
        };
    }

    private static SuggestionProvider<ClientSuggestionProvider> enumSuggestions(Class<?> enumType) {
        return (ctx, builder) -> {
            List<String> names = new ArrayList<>();
            for (Object constant : enumType.getEnumConstants()) {
                names.add(constant.toString().toLowerCase(Locale.ROOT));
            }
            return SharedSuggestionProvider.suggest(names, builder);
        };
    }
}
