package adris.altoclef.memory;

import adris.altoclef.util.DeepSeekApi;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.util.*;

/** Reviewers only select evidence-backed concepts. Model-written prose never crosses into the role database. */
public final class MemoryReview {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private MemoryReview() {}

    /** One immutable batch lease per session. A stop or replacement revokes the old right to publish. */
    static final class Fence {
        private final java.util.concurrent.atomic.AtomicReference<Object> owner=new java.util.concurrent.atomic.AtomicReference<>();
        Object open() {Object ticket=new Object();return owner.compareAndSet(null,ticket)?ticket:null;}
        boolean owns(Object ticket) {return ticket!=null && owner.get()==ticket;}
        boolean finish(Object ticket) {return ticket!=null && owner.compareAndSet(ticket,null);}
        void cancel() {owner.set(null);}
    }

    public static List<DeepSeekApi.ChatMessage> prompt(List<MemoryStore.Evidence> batch,List<String> previous) {
        var data=JSON.createObjectNode();
        var events=data.putArray("events");
        for(var evidence:batch) {
            var node=events.addObject();
            node.put("id",evidence.id().toString());
            node.put("signal",evidence.signal().name());
            var trace=node.putArray("trace");
            for(var observation:evidence.trace()) trace.add(observation.name());
            var options=node.putObject("options");
            for(var item:evidence.signal().candidates()) options.put(item.name(),item.body);
        }
        var known=data.putArray("published");
        previous.forEach(known::add);
        return List.of(DeepSeekApi.ChatMessage.system("根据已确认事件选择值得长期保留的经验。每个事件只选其 options 中有依据的项目，"+
                "输出 JSON 对象，各键对应一个输入 id。值如 {\"keep\":[\"ARRIVAL\"],\"lesson\":[]}。keep 可为空。"+
                "可从同一条 trace 提炼较短的经历顺序写入 lesson，仅保留原顺序出现的步骤，含 LOW_AIR、BREATHABLE 和终点 AIR_RESTORED。无法支持则给空数组。"),
                DeepSeekApi.ChatMessage.user(data.toString()));
    }

    public static Map<UUID,MemoryStore.Selection> parse(List<MemoryStore.Evidence> batch,String answer) throws IOException {
        JsonNode root=JSON.readTree(answer);
        if(root==null || !root.isObject() || root.size()!=batch.size()) throw new IOException("整理结果不完整");
        Map<UUID,MemoryStore.Selection> result=new HashMap<>();
        for(var e:batch) {
            var proposal=root.get(e.id().toString());
            List<DiveTrace> lesson=new ArrayList<>();
            if(proposal==null) throw new IOException("缺少事件");
            if(proposal.isObject()) {
                if(proposal.size()!=2 || !proposal.path("lesson").isArray() || !proposal.has("keep"))
                    throw new IOException("未知的提案字段");
                try {for(var step:proposal.path("lesson")) lesson.add(DiveTrace.valueOf(step.asText()));}
                catch(IllegalArgumentException invalid) {throw new IOException("未知的潜水观察");}
                if(!lesson.isEmpty() && !DiveTrace.supported(e.trace(),lesson)) throw new IOException("提案步骤与观察不符");
            }
            var items=proposal.isObject()?proposal.get("keep"):proposal;
            if(items==null || !items.isArray() || items.size()>e.signal().candidates().size()) throw new IOException("无效的整理范围");
            List<Experience> chosen=new ArrayList<>();
            for(var node:items) {
                var value=e.signal().candidates().stream().filter(i->i.name().equals(node.textValue())).findFirst();
                if(value.isEmpty() || chosen.contains(value.get())) throw new IOException("不受证据支持的提案");
                chosen.add(value.get());
            }
            result.put(e.id(),new MemoryStore.Selection(chosen,lesson));
        }
        return Map.copyOf(result);
    }
}
