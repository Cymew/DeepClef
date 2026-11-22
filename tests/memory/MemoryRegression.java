package adris.altoclef.memory;

import adris.altoclef.memory.Experience.Signal;
import adris.altoclef.util.DeepSeekApi;
import adris.altoclef.util.DshContextStore;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.io.IOException;
import java.util.stream.Stream;

/** No Minecraft instance, user files, remote network or key. Real JDBC, FTS, SSE and JSON. */
public final class MemoryRegression {
    static int checks;
    static void check(boolean ok,String name) {checks++;if(!ok) throw new AssertionError(name);}
    static void reject(Checked op,String name) throws Exception {
        try {op.run();} catch(IOException|SQLException|IllegalArgumentException expected) {checks++;return;}
        throw new AssertionError(name);
    }
    interface Checked {void run() throws Exception;}
    static MemoryStore.Fact fact(String state,String name,String detail,Signal signal) {
        return new MemoryStore.Fact(UUID.randomUUID(),state,name,"minecraft:overworld","8471, 63, -387",detail,signal);
    }
    static boolean has(List<String> rows,String text) {return rows.stream().anyMatch(s->s.contains(text));}
    public static void main(String[] args) throws Exception {
        var fence=new MemoryReview.Fence();
        Object old=fence.open();check(fence.open()==null,"only one in-flight batch");fence.cancel();
        Object next=fence.open();check(!fence.finish(old) && fence.owns(next),"stop/switch rejects late completion");
        check(fence.finish(next) && !fence.finish(next),"published at most once");
        if(Boolean.getBoolean("memory.packaged")) check(org.sqlite.JDBC.class.getProtectionDomain().getCodeSource().getLocation()
                .equals(MemoryStore.class.getProtectionDomain().getCodeSource().getLocation()),"SQLite and mod loaded from same mod jar");
        var book = SeedKnowledge.book();
        check(book.size() == 13, "all delivered seed cards loaded");
        check(book.search("矿洞里的高炉用完要带走吗", "", "").getFirst().id().equals("survival.workstations"), "portable workstation advice");
        var night = book.search("没有床，天黑以后怎么办", "", "");
        check(!night.isEmpty() && night.getFirst().id().equals("survival.first_night"), "first night cue");
        var dragon = book.search("打末影龙", "", "minecraft:end/kill_dragon");
        check(book.search("打龙", "", "").getFirst().id().equals("end.dragon_fight"), "short colloquial query");
        check(dragon.getFirst().id().equals("end.dragon_fight") && dragon.size() <= 3, "quest and advancement select dragon");
        check(!SeedKnowledge.summaries(dragon).contains("适用情境：")
                && book.lookup("end.dragon_fight").contains(dragon.getFirst().detail().move_on()), "summaries and full details");
        check(book.search("我家的箱子在哪里", "没有床", "story/smelt_iron").isEmpty(), "local fact never uses seed fallback");
        check(book.search("你好", "", "").isEmpty() && !book.lookup("missing.id").contains("适用情境："), "unrelated or absent card");
        Path temp=Files.createTempDirectory("deepclef-memory-regression-");
        try {run(temp);} finally {
            try(var paths=Files.walk(temp)) {
                for(Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
        System.out.println("Memory regression passed: "+checks+" checks");
    }
    static void run(Path root) throws Exception {
        var actor=UUID.randomUUID();
        var save=Files.createDirectory(root.resolve("World A"));
        var a=MemoryScope.resolve(root,save,null,actor);
        var b=MemoryScope.resolve(root,null,"Example.COM.",actor);
        var other=MemoryScope.resolve(root,save,null,UUID.randomUUID());
        check(b.equals(MemoryScope.resolve(root,null,"example.com:25565",actor)),"stable server address");
        check(!b.world().equals(MemoryScope.resolve(root,null,"example.com:25566",actor).world()),"distinct port");
        check(!other.world().equals(a.world()) && !other.role().equals(a.role()),"actor isolated");
        var start=fact("pending","目标","XZ",Signal.NONE);
        var arrival=new MemoryStore.Fact(start.id(),"confirmed","到达路标",start.dimension(),start.position(),"XZ 核实",Signal.ARRIVED);
        try(var db=new MemoryStore(a)) {
            db.observeMap("surface",-3,2,"-42,64,33","钟",true,Map.of("村庄线索","-42,64,33"));
            var cell=db.pendingCell();
            db.observeMap("surface",-3,2,"-41,64,33","钟与床",false,Map.of());
            db.summarize(cell,"旧摘要");
            check(db.pendingCell()!=null && !db.mapContext("surface").contains("旧摘要"),"late map review cannot replace newer observations");
            db.summarize(db.pendingCell(),"附近有床");
            check(db.pendingCell()==null && db.mapContext("surface").contains("附近有床") && db.mapContext("surface").contains("村庄线索"),"review and pin persisted");
            check(db.nearbyVisits("surface",-3,2).containsValue(1) && db.mapContext("nether").isEmpty(),"stationary readings and dimension isolation");
            db.record(start);
            check(db.recall("路标",3).isEmpty() && has(db.world("目标",3),"待核对"),"pending is not arrival");
            db.record(arrival);db.record(arrival);db.record(start);
            check(has(db.recall("寻路",3),"支持 1"),"idempotent receipt");
            check(db.world("到达",5).size()==1 && db.world("目标",5).isEmpty(),"pending replaced in short search and FTS");
            db.record(fact("unfinished","行走未完成","停止",Signal.NONE));
            reject(()->db.record(fact("observed","目标","模型自述",Signal.ARRIVED)),"unverified evidence");
            db.record(fact("marked","沉船","用户地标",Signal.NONE));
            db.record(fact("observed","箱子","minecraft:iron_ingot=3",Signal.NONE));
            db.record(fact("confirmed","背包增加","minecraft:iron_ingot=2",Signal.CHECKED_INVENTORY));
            db.record(fact("confirmed","缺氧后换气","潜水回执",Signal.RECOVERED_AIR));
            var dive=fact("confirmed","水下搜箱后换气","打开后恢复呼吸",Signal.UNDERWATER_CONTAINER);
            var trace=List.of(DiveTrace.SUBMERGED,DiveTrace.OPEN_CONTAINER,DiveTrace.LOW_AIR,DiveTrace.CLOSE_CONTAINER,
                    DiveTrace.BREATHABLE,DiveTrace.AIR_RESTORED);
            db.record(dive,trace);
            db.record(dive,trace);
            check(has(db.world("沉船",8),"iron_ingot=3") && has(db.world("沉船",8),"iron_ingot=2"),"seen and acquired remain separate");
            check(has(db.world("iron_ingot",8),"箱子"),"real trigram FTS");
            var batch=db.pending();
            var prompt=MemoryReview.prompt(batch,db.recall("",3)).toString();
            check(!prompt.contains("8471") && !prompt.contains("iron_ingot") && !prompt.contains("World A"),"generalized worker input");
            var json=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            for(var e:batch) {
                var out=json.putArray(e.id().toString()); for(var i:e.signal().candidates()) out.add(i.name());
            }
            reject(()->MemoryReview.parse(batch,json+"{}"),"trailing JSON");
            reject(()->MemoryReview.parse(batch,"{}"),"missing evidence");
            var wrong=json.deepCopy();wrong.putArray(batch.getFirst().id().toString()).add("8471");
            reject(()->MemoryReview.parse(batch,wrong.toString()),"foreign concept");
            var truncated=DeepSeekApi.parseStream(Stream.of("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}"),s->{},s->{});
            check(!truncated.ok(),"incomplete stream");
            var selected=json.putObject(dive.id().toString());
            selected.putArray("keep").add("WRECK_STORY").add("DIVE_SEARCH");
            var steps=selected.putArray("lesson");
            steps.add("AIR_RESTORED").add("LOW_AIR").add("BREATHABLE");
            reject(()->MemoryReview.parse(batch,json.toString()),"fabricated order");
            steps.removeAll();steps.add("OPEN_CONTAINER").add("LOW_AIR").add("BREATHABLE").add("AIR_RESTORED");
            var allowed=MemoryReview.parse(batch,json.toString());
            db.review(batch,allowed);db.review(batch,allowed);
            check(db.pending().isEmpty() && has(db.recall("船舱",5),"WRECK_STORY"),"approved anecdote");
            check(has(db.recall("潜水",20),"DIVE-OPEN_CONTAINER-LOW_AIR-BREATHABLE-AIR_RESTORED"),"new condensed technique from actual sequence");
            check(has(db.recall("舱室",5),"DIVE_SEARCH"),"conditional skill");
            check(db.recall("偏好",3).isEmpty(),"instructed actions never shape personality");
            var choice=fact("confirmed","自主选择","可继续时提早换气",Signal.CHOSE_EARLY_RETURN);
            db.record(choice);db.record(choice);db.record(fact("confirmed","自主选择","独立回合",Signal.CHOSE_EARLY_RETURN));
            check(db.recall("性格",3).isEmpty(),"two independent choices insufficient");
            db.record(fact("confirmed","自主选择","独立回合",Signal.CHOSE_EARLY_RETURN));
            check(has(db.recall("性格",3),"CAUTIOUS") && has(db.recall("怎么想",3),"CHECK_EXIT"),"independent choices shape habits and preferences");
            exerciseQuestions(db);
            db.record(fact("confirmed","自主选择","选择继续",Signal.CHOSE_CONTINUE));
            check(db.recall("性格",3).isEmpty(),"counterevidence changes preference");
            for(int i=0;i<4;i++) check(db.reserveRequest(100+i)>0,"budget ticket");
            check(db.reserveRequest(250)==0,"budget exhausted");
            check(db.context("这个世界的沉船在哪").contains("8471"),"world A location");
            var delayed=db.pending();
            db.forget(Experience.CHECK_EXIT);
            var picks=new HashMap<UUID,MemoryStore.Selection>();
            for(var e:delayed) picks.put(e.id(),new MemoryStore.Selection(e.signal().candidates(),List.of()));
            db.review(delayed,picks);
            check(!has(db.recall("",20),"CHECK_EXIT"),"late review cannot revive tombstone");
        }
        try(var db=new MemoryStore(b)) {
            check(db.mapContext("surface").isEmpty(),"world atlas is isolated");
            check(db.world("",10).isEmpty() && db.context("本档沉船在哪").contains("暂无匹配"),"world B unknown location");
            check(!db.context("本档沉船在哪").contains("角色"),"location question isolated");
            var memory=db.context("以前探索过沉船吗");
            check(memory.contains("WRECK_STORY") && !memory.contains("8471") && !memory.contains("iron_ingot"),"generalized memory across worlds");
            check(db.reserveRequest(300)==0 && db.reserveRequest(3601000)>0,"role budget persists across reconnect");
        }
        try(var db=new MemoryStore(other)) {check(db.world("",10).isEmpty() && db.recall("",10).isEmpty(),"second account empty");}
        try(var db=new MemoryStore(a)) {
            check(has(db.world("到达",3),"8471") && has(db.recall("寻路",3),"支持 1"),"restart restores evidence");
            db.maintain(100000000);
            check(db.recall("",10).isEmpty() && has(db.world("到达",3),"8471"),"playtime decay preserves local history");
            db.record(fact("confirmed","到达路标","再一次",Signal.ARRIVED));
            check(has(db.recall("路标",3),"支持 2"),"real validation reinforces archived item");
        }
        byte[] raw=Files.readAllBytes(a.role().resolve("memory.sqlite"));
        String inspect=new String(raw,StandardCharsets.ISO_8859_1);
        check(!inspect.contains("iron_ingot") && !inspect.contains("minecraft:overworld") && !inspect.contains("8471, 63"),"role DB no raw facts");
        Path season=b.world().getParent().getParent().getParent().getParent().resolve("season.txt");
        Files.writeString(season,"2");
        var next=MemoryScope.resolve(root,null,"example.com",actor);
        check(!next.world().equals(b.world()) && next.role().equals(b.role()),"new server season");
        Files.writeString(season,"../outside");
        reject(()->MemoryScope.resolve(root,null,"example.com",actor),"malformed season");
        testLegacy(a.world(),root);
        measure(root,actor);
        var disposable=Files.createDirectory(root.resolve("removed"));
        var deleted=MemoryScope.resolve(root,disposable,null,actor);
        Files.delete(disposable);
        reject(()->{try(var ignored=new MemoryStore(deleted)) {}}, "removed save rejected");
        check(Files.notExists(disposable),"old callback cannot recreate deleted save");
        testTransport();
    }

    static void testTransport() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var settings=new adris.altoclef.Settings();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var body=new java.util.concurrent.atomic.AtomicReference<String>();
        var status=new java.util.concurrent.atomic.AtomicInteger(200);
        server.createContext("/chat/completions",exchange->{
            try(exchange) {
                body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                String answer=status.get()==429?"{}":"data: {\"choices\":[{\"delta\":{\"content\":\"{}\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":13,\"completion_tokens\":2}}\n\ndata: [DONE]\n\n";
                byte[] bytes=answer.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status.get(),bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try {
            settings.setDeepSeekApiKey("local-fixture");
            settings.setDeepSeekBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
            var prompt=MemoryReview.prompt(List.of(),List.of());
            var ok=DeepSeekApi.reviewMemory(settings,prompt).get(10,java.util.concurrent.TimeUnit.SECONDS);
            check(ok.ok() && ok.promptTokens()==13 && ok.answer().equals("{}"),"loopback worker transport");
            var sent=new com.fasterxml.jackson.databind.ObjectMapper().readTree(body.get());
            check(!sent.has("tools") && sent.at("/response_format/type").asText().equals("json_object")
                    && sent.at("/thinking/type").asText().equals("disabled") && sent.path("max_tokens").asInt()==768,
                    "actual worker request has bounded text-only JSON contract");
            status.set(429);
            var limited=DeepSeekApi.reviewMemory(settings,prompt).get(10,java.util.concurrent.TimeUnit.SECONDS);
            check(!limited.ok() && limited.error().contains("429"),"HTTP 429 leaves work uncommitted");
        } finally {server.stop(0);}
    }

    static void exerciseQuestions(MemoryStore db) throws SQLException {
        String[][] cases={
                {"走路到了怎么核对","ARRIVAL"},{"怎样判断到达","ARRIVAL"},{"跑图怎么确认","ARRIVAL"},{"导航后如何复核","ARRIVAL"},
                {"寻路结果怎么查","ARRIVAL"},{"目的地怎么验证","ARRIVAL"},{"怎么判断就位","ARRIVAL"},
                {"缺氧了怎么办","BREATH"},{"需要喘气时怎么办","BREATH"},{"能说说呼吸技巧吗","BREATH"},{"氧气不够怎么办","BREATH"},
                {"怎么安排上浮","BREATH"},{"如何换气","BREATH"},{"以前呛水过吗","DIVE_STORY"},{"脱险的回忆","DIVE_STORY"},
                {"有紧张的往事吗","DIVE_STORY"},{"以前遇到过失事船只吗","WRECK_STORY"},{"还记得船舱吗","WRECK_STORY"},
                {"以前有过探船经历吗","WRECK_STORY"},{"水下搜箱要注意什么","DIVE_SEARCH"},{"舱室搜索技巧","DIVE_SEARCH"},
                {"怎么在海底搜刮","DIVE_SEARCH"},{"怎样盘点物资","CONTAINER"},{"背包增量怎么查","CONTAINER"},
                {"箱内有哪些东西算收获","CONTAINER"},{"核对库存的办法","CONTAINER"},{"性格如何","CAUTIOUS"},
                {"你比较谨慎吗","CAUTIOUS"},{"对风险是什么偏好","CAUTIOUS"},{"你会怎么想","CHECK_EXIT"},{"你的计划习惯","CHECK_EXIT"}};
        int correct=0;
        for(var q:cases) if(db.recall(q[0],3).stream().anyMatch(s->s.startsWith("[") && s.substring(0,s.indexOf(']')).endsWith(" "+q[1]))) correct++;
        System.out.println("Chinese retrieval top-3: "+correct+"/"+cases.length);
        check(correct>=.8*cases.length,"retrieval benchmark");
    }

    static void testLegacy(Path dir,Path root) throws Exception {
        Path shots=Files.createDirectory(root.resolve("old-shots"));
        Path old=root.resolve("old.json");
        var ctx=new DshContextStore.Context();
        ctx.turns=2;ctx.messages.add(new DshContextStore.StoredMessage("user","历史",null,null,"file:shots/a01.png"));
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(old.toFile(),ctx);
        var legacy=new DshContextStore.Legacy(old,shots);
        reject(()->DshContextStore.importLegacy(dir,legacy),"missing screenshot");
        check(Files.notExists(dir.resolve("dsh_context.json")),"partial import invisible");
        Files.write(shots.resolve("a01.png"),new byte[]{0,1,2});
        var fresh=new DshContextStore.Context();fresh.turns=1;
        DshContextStore.save(dir,fresh);
        DshContextStore.importLegacy(dir,legacy);DshContextStore.importLegacy(dir,legacy);
        var result=new com.fasterxml.jackson.databind.ObjectMapper().readValue(dir.resolve("dsh_context.json").toFile(),DshContextStore.Context.class);
        check(result.turns==3 && result.messages.size()==1 && result.legacyImported,"grouped import and merge idempotent");
        check(Files.exists(old) && Files.mismatch(shots.resolve("a01.png"),dir.resolve("dsh_shots/a01.png"))==-1,"source and sidecar preserved");
        reject(()->DshContextStore.saveImage(dir,"../x",new byte[1]),"sidecar name validation");
    }

    static void measure(Path root,UUID actor) throws Exception {
        Path save=Files.createDirectory(root.resolve("large"));
        var scope=MemoryScope.resolve(root,save,null,actor);
        try(var db=new MemoryStore(scope)) {db.record(fact("confirmed","到达路标","基线",Signal.ARRIVED));}
        // Seed 10k independent evidence rows efficiently in a single transaction, outside the timed section.
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+scope.role().resolve("memory.sqlite"));var s=c.createStatement()) {
            s.executeUpdate("WITH RECURSIVE n(x) AS(VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<10000) INSERT INTO source SELECT 'fixture-'||x,'ARRIVAL',1,0 FROM n");
            s.executeUpdate("UPDATE entry SET supports=supports+10000 WHERE item='ARRIVAL'");
        }
        for(int i=0;i<2;i++) {
            long start=System.nanoTime();
            try(var db=new MemoryStore(scope)) {
                long opened=System.nanoTime();
                check(has(db.recall("目的地",3),"支持 1000"),"bulk recall");
                System.out.printf(Locale.ROOT,"10k evidence reopen %d: %.2f ms, lookup %.2f ms%n",i+1,
                        (opened-start)/1e6,(System.nanoTime()-opened)/1e6);
            }
        }
    }
}
