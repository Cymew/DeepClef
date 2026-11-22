package adris.altoclef.mode;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.*;
import adris.altoclef.util.DeepSeekApi;
import adris.altoclef.tasks.movement.GetToXZTask;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static adris.altoclef.mode.WorldMode.*;

public class WorldModeRegression {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("deepclef-test-mode-");
        try {
            session(root);
            storage(root);
            commands();
            arrival();
            responses();
            playerCommands();
            checks += adris.altoclef.util.PlayerDecisionsRegression.run();
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path owned : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(owned);
            }
        }
        System.out.println("P1 world mode checks passed: " + checks);
    }

    private static void check(boolean passed, String reason) {
        if (!passed) throw new AssertionError(reason);
        checks++;
    }

    private static void session(Path root) throws Exception {
        WorldModeSession session = new WorldModeSession();
        Object a = new Object(), b = new Object(), rejoin = new Object();
        var save = WorldModeStore.save(root.resolve("saveA"));
        var server = WorldModeStore.server(root, "Example.org");
        session.enter(a);
        check(session.needsIdentity() && !session.takePrompt(), "wait until identity exists");
        session.load(save);
        check(session.takePrompt() && !session.takePrompt(), "one choice screen per connection");
        check(!Files.exists(save.file()) && session.current(a) == null, "dismiss leaves no binding or active mode");
        session.enter(rejoin);
        session.load(save);
        check(session.takePrompt(), "rejoin after dismiss prompts again");
        check(session.select(a, PLAYER) == null, "stale screen cannot bind another connection");
        check(session.select(rejoin, ASSISTANT) == ASSISTANT, "first save binds");
        Files.writeString(save.file(), "{\"mode\":\"player\"}");
        session.enter(rejoin);
        check(!session.needsIdentity() && !session.takePrompt() && session.current(rejoin) == ASSISTANT,
                "respawn/dimension on same connection does not hot-reload an edit");
        session.enter(b);
        check(session.current(rejoin) == null && session.current(b) == null, "old connection no longer owns the controls");
        session.load(server);
        check(session.takePrompt() && session.select(b, PLAYER) == PLAYER, "independent first choice on server");
        session.enter(null);
        session.enter(a);
        session.load(save);
        check(!session.takePrompt() && session.current(a) == PLAYER, "manual edit becomes effective on rejoin");
        session.enter(rejoin);
        session.load(WorldModeStore.server(root, "EXAMPLE.org.:25565"));
        check(!session.takePrompt() && session.current(rejoin) == PLAYER, "equivalent address reconnects directly");
        check(WorldModeStore.save(root.resolve("saveB")).read() == null, "another save still needs a choice");
    }

    private static void storage(Path root) throws Exception {
        var local = WorldModeStore.save(root.resolve("invalid"));
        Files.createDirectories(local.file().getParent());
        for (String bad : List.of("", "[]", "{}", "{\"mode\":null}", "{\"mode\":\"typo\"}",
                "{\"mode\":\"player\",\"mode\":\"assistant\"}", "{\"mode\":\"player\"} true")) {
            Files.writeString(local.file(), bad);
            var session = new WorldModeSession();
            Object owner = new Object();
            session.enter(owner);
            try { session.load(local); throw new AssertionError("invalid binding accepted"); }
            catch (IOException expected) { check(session.current(owner) == null && !session.takePrompt(), "corrupt file leaves mod inactive"); }
            try { local.bind(PLAYER); throw new AssertionError("corrupt binding overwritten"); }
            catch (IOException expected) { check(Files.readString(local.file()).equals(bad), "preserve corrupt file verbatim"); }
        }
        var primary = WorldModeStore.server(root, "[2001:0db8:0000:0:0:0:0:1]");
        var secondary = WorldModeStore.server(root, "[2001:db8::1]:25566");
        check(primary.key().equals("[2001:db8::1]:25565") && !primary.key().equals(secondary.key()), "IPv6 and ports");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> primary.bind(ASSISTANT));
            var second = pool.submit(() -> secondary.bind(PLAYER));
            check(first.get() == ASSISTANT && second.get() == PLAYER, "simultaneous distinct bindings");
        }
        check(primary.bind(PLAYER) == ASSISTANT && secondary.read() == PLAYER, "first choice wins and other servers survive");
        var third = WorldModeStore.server(root, "other.example");
        check(third.read() == null, "missing server is unbound");
        Files.writeString(primary.file(), "{\"other.example:25565\":\"broken\"}");
        try { primary.bind(PLAYER); throw new AssertionError("damaged address book accepted"); }
        catch (IOException expected) { check(!Files.readString(primary.file()).contains("2001:"), "damaged server map preserved"); }
        var blocked = WorldModeStore.save(root.resolve("unwritable"));
        var pending = new WorldModeSession();
        Object connection = new Object();
        pending.enter(connection);
        pending.load(blocked);
        check(pending.takePrompt(), "missing file allows selection");
        Files.writeString(blocked.file().getParent().getParent(), "file instead of directory");
        try { pending.select(connection, PLAYER); throw new AssertionError("save unexpectedly succeeded"); }
        catch (IOException expected) { check(pending.current(connection) == null && !pending.takePrompt(), "write failure never activates"); }
    }

    private static final class TestClient extends AltoClef {
        WorldMode selected;
        @Override public WorldMode worldMode() { return selected; }
    }

    private static void commands() {
        TestClient client = new TestClient();
        CommandExecutor commands = new CommandExecutor(client);
        AtomicInteger moves = new AtomicInteger(), rejected = new AtomicInteger();
        Runnable[] delayed = new Runnable[1];
        commands.registerNewCommand(new Command("wait", "") {
            protected void call(AltoClef mod, ArgParser parser) { delayed[0] = completion(); }
        }, new Command("move", "") {
            protected void call(AltoClef mod, ArgParser parser) { moves.incrementAndGet(); finish(); }
        }, new Command("stop", "") {
            protected void call(AltoClef mod, ArgParser parser) { commands.cancelPending(); finish(); }
        });
        commands.execute("@move", e -> rejected.incrementAndGet());
        check(rejected.get() == 1 && moves.get() == 0, "unbound commands issue no actions");
        client.selected = ASSISTANT;
        check(!client.allowsBackgroundMovement(), "idle assistant cannot schedule movement");
        commands.execute("@wait;move");
        Runnable old = delayed[0];
        commands.execute("@stop;move");
        commands.execute("@wait;move");
        old.run();
        check(moves.get() == 0, "stop invalidates delayed actions and its own command suffix");
        delayed[0].run();
        check(moves.get() == 1, "new explicit action works after stop");
        client.selected = PLAYER;
        check(client.allowsBackgroundMovement(), "bound player can schedule background movement");
    }

    private static void playerCommands() throws Exception {
        class Local extends AltoClef {
            boolean paused;
            String last;
            final adris.altoclef.Settings settings = new adris.altoclef.Settings();
            final adris.altoclef.chains.UserTaskChain chain = new adris.altoclef.chains.UserTaskChain(
                    new adris.altoclef.tasksystem.TaskRunner(this));
            @Override public WorldMode worldMode() { return PLAYER; }
            @Override public adris.altoclef.Settings getModSettings() { return settings; }
            @Override public adris.altoclef.chains.UserTaskChain getUserTaskChain() { return chain; }
            @Override public void log(String s) { last = s; }
            @Override public boolean backgroundPaused() { return paused; }
            @Override public void stopAutomation() { paused = true; playerRoutine().interrupt(); }
            @Override public void resumeBackground() { paused = false; }
        }
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        Local mod = new Local();
        var commands = new CommandExecutor(mod);
        commands.registerNewCommand(new adris.altoclef.commands.DsCommand());
        check(mod.playerRoutine().permitsConfiguredIdle(mod), "configured fallback when no API key");
        mod.settings.setDeepSeekApiKey("offline-test-only");
        check(!mod.playerRoutine().permitsConfiguredIdle(mod), "configured idle cannot race normal decision");
        var body = new adris.altoclef.tasks.movement.IdleTask();
        mod.chain.setTask(body);
        commands.execute("@ds 帮我拿木头");
        commands.execute("@ds 你在做什么？");
        check(mod.last.contains("待看留言 1") && mod.chain.getCurrentTask() == body, "messages preserve current action");
        commands.execute("@ds pause");
        commands.execute("@ds 看看路标");
        commands.execute("@ds progress");
        check(mod.paused && mod.last.contains("已暂停") && mod.last.contains("待看留言 2"), "mail while paused");
        commands.execute("@ds resume");
        check(!mod.paused && mod.last.contains("待看留言 2"), "resume with original queue");
        mod.playerRoutine().leaveWorld(false);
        check(mod.playerRoutine().status(mod).contains("待看留言 2"), "dimension change retains same-connection mail");
        mod.playerRoutine().leaveWorld(true);
        check(mod.playerRoutine().status(mod).contains("待看留言 0"), "no inbox leak after changing saves");
    }

    private static void responses() {
        String tool = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"walk1\","
                + "\"function\":{\"name\":\"altoclef_walk\",\"arguments\":\"{}\"}}]}}]}";
        String finish = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}";
        String done = "data: [DONE]";
        for (List<String> frames : List.of(List.of(tool), List.of(tool, finish), List.of(tool, done),
                List.of(tool, finish.replace("tool_calls", "length"), done), List.of(tool, "data: {", finish, done),
                List.of(tool.replace("\"{}\"", "\"{\""), finish, done))) {
            var result = DeepSeekApi.parseStream(frames.stream(), s -> {}, s -> {});
            check(!result.ok() && result.toolCalls().isEmpty(), "incomplete response must never expose an executable call");
        }
        var complete = DeepSeekApi.parseStream(List.of(tool, finish, done).stream(), s -> {}, s -> {});
        check(complete.ok() && complete.toolCalls().size() == 1, "completed response retains its tool");
    }

    private static void arrival() {
        var actual = net.minecraft.core.BlockPos.containing(-139.3, 63, -471.7);
        String report = GetToXZTask.arrivalReport(-140, -472, actual);
        check(report.startsWith("已到达") && report.contains(actual.toShortString()),
                "negative world coordinates use the occupied block and report the sampled position");
        check(GetToXZTask.arrivalReport(-140, -472, actual.east()).startsWith("未到达"), "adjacent block cannot confirm arrival");
    }
}
