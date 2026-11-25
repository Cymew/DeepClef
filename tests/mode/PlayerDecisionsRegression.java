package adris.altoclef.util;

import java.io.IOException;

/** Recorded decisions and late completions; no provider key or world is required. */
public final class PlayerDecisionsRegression {
    private static int checks;
    private static void check(boolean ok) { if (!ok) throw new AssertionError("player decision " + checks); checks++; }
    private static void multiplayerConversation() {
        var buffer = new MultiplayerChatBuffer();
        var chat = new PlayerConversation();
        var self = java.util.UUID.randomUUID();
        var alice = java.util.UUID.randomUUID();
        check(buffer.add(self, "Self", "嗨", "public", "", 0, self, false) == 0);
        for (int i = 0; i < 3; i++) {
            long id = buffer.add(alice, "Alice", "嗨", "public", "sig" + i, i, self, false);
            check(id > 0);
            chat.add(buffer.read(id - 1, 1).getFirst(), i == 0 ? 2 : 3);
        }
        check(buffer.add(alice, "Alice", "嗨", "public", "sig2", 4, self, false) == 0);
        var first = chat.ready(900);
        check(first.lines().size() == 3 && first.first().direct() && !first.lines().getLast().direct());
        check(first.first().recipient().isEmpty() && first.prompt("Self", "观察").contains("Alice"));
        // A message received during inference belongs to the following decision.
        long id = buffer.add(alice, "Alice", "秘密", "whisper", "w", 901, self, false);
        chat.add(buffer.read(id - 1, 1).getFirst(), 5);
        chat.considered(first, "你好");
        var privateBatch = chat.ready(1600);
        check(privateBatch.first().direct() && privateBatch.first().recipient().equals("Alice") && privateBatch.context().isEmpty());
        chat.considered(privateBatch, "悄悄回复");
        id = buffer.add(alice, "Alice", "今晚盖房", "public", "", 1700, self, false);
        chat.add(buffer.read(id - 1, 1).getFirst(), 4);
        var group = chat.ready(2500);
        check(!group.first().direct() && group.context().contains("我：你好") && !group.context().contains("秘密"));
        chat.considered(group, ""); // group silence consumes the batch without a send
        check(chat.count() == 0 && chat.ready(3000) == null);
        chat.clear();
        check(chat.lastExchange().isEmpty());
        var allowance = new PlayerDecisions();
        for (int i = 0; i < 24; i++) check(allowance.reserve(i, 100, true) != null);
        check(allowance.reserve(25, 100, true) != null && allowance.reserve(25, 100) != null);
    }

    private static void vanillaJourney() {
        check(!PlayerRoutine.needsRecovery(0,true));
        check(!PlayerRoutine.needsRecovery(1,false));
        check(PlayerRoutine.needsRecovery(1,true));
        check(PlayerRoutine.needsRecovery(2,false));
        check(!NightSleep.preparationTime(10999) && NightSleep.preparationTime(11000));
        check(NightSleep.preparationTime(24000+13000) && !NightSleep.preparationTime(23000));
        var story = new PlayerJourney();
        var iron = java.util.Map.of("minecraft:iron_ingot", 1, "minecraft:flint", 1);
        var empty = java.util.Map.<String,Integer>of();
        check(story.next() == PlayerJourney.Chapter.IRON);
        story.observe(java.util.Set.of(), iron, false, false, false);
        check(story.next() == PlayerJourney.Chapter.NETHER && story.preparation(iron).item().equals("minecraft:iron_pickaxe"));
        var gear = new java.util.HashMap<String,Integer>();
        gear.put("minecraft:diamond_pickaxe", 1);
        check(story.preparation(gear).item().equals("minecraft:iron_sword"));
        gear.put("minecraft:iron_sword", 1);
        check(story.preparation(gear).item().equals("minecraft:bucket"));
        gear.put("minecraft:water_bucket", 1);
        check(story.preparation(gear).item().equals("minecraft:flint_and_steel"));
        gear.put("minecraft:flint_and_steel", 1);
        check(story.preparation(gear) == null);
        story.observe(java.util.Set.of("minecraft:story/enter_the_nether"), empty, true, false, false);
        check(story.next() == PlayerJourney.Chapter.END);
        var eyes = new java.util.HashMap<String,Integer>();
        eyes.put("minecraft:ender_eye", 3);
        eyes.put("minecraft:blaze_powder", 1);
        check(story.preparation(eyes).equals(new PlayerProject.Milestone("minecraft:blaze_rod",5)));
        eyes.put("minecraft:blaze_rod", 5);
        check(story.preparation(eyes).equals(new PlayerProject.Milestone("minecraft:ender_pearl",11)));
        eyes.put("minecraft:ender_pearl", 11);
        check(story.preparation(eyes) == null);
        story.observe(java.util.Set.of(), eyes, false, false, false);
        check(story.preparation(eyes).equals(new PlayerProject.Milestone("minecraft:ender_eye",14)));
        story.observe(java.util.Set.of(), java.util.Map.of("minecraft:ender_eye", 14), false, false, false);
        var interrupted = new PlayerJourney();
        interrupted.restore(story.history());
        check(interrupted.preparation(java.util.Map.of("minecraft:ender_eye", 10)) == null);
        check(interrupted.preparation(empty).item().equals("minecraft:blaze_rod"));
        story.observe(java.util.Set.of(), empty, false, true, false);
        check(story.next() == PlayerJourney.Chapter.DRAGON);
        story.observe(java.util.Set.of("minecraft:end/find_end_city"), empty, false, false, true);
        check(story.next() == PlayerJourney.Chapter.DRAGON && story.has("WINGS"));
        story.observe(java.util.Set.of("minecraft:end/kill_dragon"), empty, false, false, false);
        check(story.next() == PlayerJourney.Chapter.BEACON);
        story.mark("site:trial:one");
        var restored = new PlayerJourney();
        restored.restore(story.history());
        restored.observe(java.util.Set.of("minecraft:nether/create_beacon"), empty, false, false, false);
        check(restored.has("site:trial:one") && !restored.has("site:trial:two") && restored.next() == null);
    }

    private static void projectProgress() throws IOException {
        var logs = java.util.Map.of(PlayerProject.LOGS, 27);
        var boards = java.util.Map.of(PlayerProject.LOGS, 26, PlayerProject.PLANKS, 4);
        var bench = java.util.Map.of(PlayerProject.LOGS, 26, PlayerProject.TABLE, 1);
        var ready = java.util.Map.of(PlayerProject.TABLE, 1, PlayerProject.STICKS, 4);
        var plan = new PlayerProject();
        var directory = java.nio.file.Files.createTempDirectory("deepclef-project");
        try {
            plan.bind(directory);
            plan.initialize(logs, false);
            check(!plan.allowMoreLogs(logs) && plan.nextProduct(logs).equals(PlayerProject.PLANKS));
            plan.afterAction(logs, boards);
            check(plan.nextProduct(boards).equals(PlayerProject.TABLE));
            plan.afterAction(boards, bench);
            check(plan.nextProduct(bench).equals(PlayerProject.PLANKS));
            plan.save();
            var restored = new PlayerProject();
            restored.bind(directory);
            restored.initialize(bench, false);
            check(restored.describe(bench).contains(PlayerProject.STICKS));
            restored.afterAction(bench, ready);
            check(restored.nextProduct(ready).isEmpty() && restored.describe(ready).contains("已完成"));
            check(restored.start("探路", java.util.List.of(new PlayerProject.Milestone("explore", 2)), ready));
            check(restored.acceptExcursion() && restored.nextProduct(ready).equals("explore"));
            check(restored.acceptExcursion() && restored.nextProduct(ready).isEmpty());
            check(restored.start("照明", java.util.List.of(new PlayerProject.Milestone("minecraft:torch", 4)), ready));
            for (int i = 0; i < 3; i++) restored.afterAction(ready, ready);
            check(restored.nextProduct(ready).isEmpty() && restored.describe(ready).contains("暂缓"));
            var torch = new PlayerProject.Milestone("minecraft:torch", 4);
            check(!restored.start("照明", java.util.List.of(torch), ready));
            restored.save();
            var resumed = new PlayerProject();
            resumed.bind(directory);
            check(!resumed.start("照明", java.util.List.of(torch), ready));
            check(resumed.start("照明", java.util.List.of(torch), java.util.Map.of("minecraft:coal", 1)));
            var batch = new PlayerProject();
            var pick = new PlayerProject.Milestone("minecraft:stone_pickaxe", 1);
            String stone = "minecraft:cobblestone";
            var partial = java.util.Map.of(stone, 1, PlayerProject.STICKS, 2);
            check(batch.start("做镐", java.util.List.of(pick), partial));
            check(batch.order(partial, false).equals(new PlayerProject.Milestone(stone, 3)));
            check(batch.order(java.util.Map.of(stone, 21, PlayerProject.STICKS, 2), false).equals(pick));
            check(batch.order(java.util.Map.of(stone, 21), false).equals(new PlayerProject.Milestone(PlayerProject.STICKS, 2)));
            batch.refresh(java.util.Map.of(pick.item(), 1));
            check(batch.order(partial, false) == null);
            check(batch.start("两把镐", java.util.List.of(new PlayerProject.Milestone(pick.item(), 2)), partial));
            check(batch.order(java.util.Map.of(PlayerProject.STICKS, 4), false).equals(new PlayerProject.Milestone(stone, 6)));
            check(!restored.start("再囤原木", java.util.List.of(new PlayerProject.Milestone("logs", 32)), logs));
            var answer = PlayerDecisions.parse("""
                    {"choice":0,"reason":"勘察","project":"外出勘察","milestones":[{"item":"explore","count":2}]}
                    """, 1, false);
            check(answer.milestones().size() == 1 && answer.project().equals("外出勘察"));
        } finally {
            java.nio.file.Files.deleteIfExists(directory.resolve("player_project.json"));
            java.nio.file.Files.deleteIfExists(directory);
        }
    }

    private static void seedDecision() {
        var cards = adris.altoclef.memory.SeedKnowledge.book().search("天要黑了，没有床", "", "");
        var turn = new SeedKnowledgeTurn("system", "现场", cards);
        String initial = "{\"choice\":0,\"reason\":\"准备落脚\",\"knowledge_ids\":[\"survival.first_night\",\"missing.id\"]}";
        var requests = new java.util.ArrayList<String>();
        var reserves = new PlayerDecisions();
        var answer = turn.start(Runnable::run, () -> true, bytes -> reserves.reserve(1, bytes), input -> {
            requests.add(input);
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new DeepSeekApi.ChatResult(true, "", initial, 5, 4, "stop", java.util.List.of()));
        }).join();
        check(requests.size() == 2 && answer.answer().equals(initial) && reserves.tokens() == 18);
        check(!requests.getFirst().contains("适用情境：") && requests.getLast().contains("适用情境："));
        var pending = new java.util.concurrent.CompletableFuture<DeepSeekApi.ChatResult>();
        var current = new java.util.concurrent.atomic.AtomicBoolean(true);
        var stale = turn.start(Runnable::run, current::get, bytes -> reserves.reserve(1, bytes), input -> pending);
        current.set(false);
        pending.complete(new DeepSeekApi.ChatResult(true, "", initial, 1, 1, "stop", java.util.List.of()));
        check(stale.isCancelled());
        int[] calls = {0};
        var fallback = turn.start(Runnable::run, () -> true, bytes -> ++calls[0] == 1 ? reserves.reserve(1, bytes) : null,
                input -> pending).join();
        check(calls[0] == 2 && fallback.answer().equals(initial));
        var direct = turn.start(Runnable::run, () -> true, bytes -> reserves.reserve(1, bytes), input -> {
            requests.add(input);
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new DeepSeekApi.ChatResult(true, "", "{\"choice\":0}", 2, 1, "stop", java.util.List.of()));
        });
        check(direct.join().ok() && requests.size() == 3);
        var second = new java.util.concurrent.CompletableFuture<DeepSeekApi.ChatResult>();
        var round = new java.util.concurrent.atomic.AtomicInteger();
        current.set(true);
        var revise = turn.start(Runnable::run, current::get, bytes -> reserves.reserve(1, bytes),
                input -> round.incrementAndGet() == 1 ? pending : second);
        current.set(false);
        second.complete(answer);
        check(round.get() == 2 && revise.isCancelled());
        var cancelledHttp = new java.util.concurrent.CompletableFuture<DeepSeekApi.ChatResult>();
        var stopped = turn.start(Runnable::run, () -> true, bytes -> reserves.reserve(1, bytes), input -> cancelledHttp);
        stopped.cancel(true);
        check(cancelledHttp.isCancelled());
    }

    public static int run() throws IOException {
        seedDecision();
        projectProgress();
        vanillaJourney();
        var turns = new PlayerDecisions();
        long first = turns.begin();
        turns.offer("看看前面的路标");
        turns.offer("看看前面的路标");
        check(turns.pending() == 1 && turns.owns(first));
        turns.release();
        long next = turns.begin();
        check(!turns.owns(first) && turns.owns(next));
        turns.offer("能帮忙吗");
        String read = turns.first();
        String json = "{\"choice\":1,\"reason\":\"想去路标附近看看\",\"reply\":\"我先过去看看\"}";
        check(PlayerDecisions.parse(json, 2, true).choice() == 1);
        check(PlayerDecisions.parse(json, 2, true).target().isEmpty());
        var survey = PlayerDecisions.parse("{\"choice\":0,\"reason\":\"找树\",\"target\":\"tree\",\"survey_only\":true}", 1, false);
        check(!survey.surveyOnly() && survey.target().isEmpty() && survey.choice() == 0);
        var direction = PlayerDecisions.parse("{\"choice\":0,\"reason\":\"找树\",\"reply\":\"我看看\",\"target\":\"tree\",\"survey_only\":true}", 1, true);
        check(direction.surveyOnly() && direction.target().equals("tree"));
        var seek = PlayerDecisions.parse("{\"choice\":0,\"reason\":\"找树\",\"target\":\"tree\"}", 1, false);
        check(!seek.surveyOnly() && seek.target().equals("tree"));
        for (String bad : new String[]{json.replace(":1",":2"), json.replace(":1",":1.9"),
                json + " {}", "{}", "{\"choice\":0,\"reason\":\"看看\"}", "{\"choice\":\"0\",\"reason\":\"看看\"}"}) {
            try { PlayerDecisions.parse(bad, 2, true); throw new AssertionError("accepted " + bad); }
            catch (IOException expected) { check(turns.pending() == 2); }
        }
        turns.acknowledge(read);
        turns.acknowledge(read);
        check(turns.pending() == 1 && turns.first().equals("能帮忙吗"));
        turns.release();
        turns.clearMail();
        check(turns.pending() == 0 && !turns.owns(next));
        for (int i = 0; i < 10; i++) turns.offer("请求" + i);
        check(turns.pending() == 8 && turns.first().equals("请求0"));
        for (int i = 0; i < 30; i++) check(turns.reserve(i, 100) != null);
        check(turns.reserve(30, 100) != null && turns.retryBudgetAt(30) == 3_600_000);
        check(turns.reserve(3_600_000, 100) != null && turns.requests(3_600_000) == 31);
        var charge = turns.reserve(7_200_000, 1_000_000 - 128 - PlayerDecisions.OUTPUT_LIMIT);
        check(charge != null && turns.tokens() == 1_000_000 && turns.reserve(7_200_001, 0) == null);
        charge.tokens = 12;
        check(turns.reserve(7_200_002, 60_001) != null);
        var shared = new PlayerDecisions();
        check(shared.reserve(0, 800_000 - 128 - PlayerDecisions.OUTPUT_LIMIT, true) != null);
        check(shared.reserve(1, 0, true) == null);
        check(shared.reserve(2, 200_000 - 128 - PlayerDecisions.OUTPUT_LIMIT) != null && shared.tokens() == 1_000_000);
        check(shared.reserve(3, 0) == null && shared.reserve(3_600_000, 100, true) != null);
        var area = new HomeArea(176,208,130,172);
        var edge = new net.minecraft.core.BlockPos(130,-20,208);
        check(area.atLeast(edge.west(500),500) && !area.atLeast(edge.west(499),500));
        check(area.contains(edge) && !area.contains(edge.west()));
        check(adris.altoclef.tasks.resources.LocalTimber.within(edge,edge.east(48).above(12),48));
        check(!adris.altoclef.tasks.resources.LocalTimber.within(edge,edge.east(48).north(),48));
        check(DeepSeekAgent.freshTimber("去五百格外砍128个樱花木"));
        check(DeepSeekAgent.plainTimberGet("get cherry_log 128"));
        check(!DeepSeekAgent.plainTimberGet("get harvest cherry_log 128 48"));
        check(!DeepSeekAgent.freshTimber("从箱子里取木头"));
        multiplayerConversation();
        return checks;
    }
}
