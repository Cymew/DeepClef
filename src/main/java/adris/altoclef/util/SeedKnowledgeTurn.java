package adris.altoclef.util;

import adris.altoclef.memory.SeedKnowledge;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** One decision, optionally refined once using the requested cards on the same scene. */
final class SeedKnowledgeTurn {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String system, input;
    private final List<SeedKnowledge.Card> offered;

    SeedKnowledgeTurn(String system, String scene, List<SeedKnowledge.Card> cards) {
        this.system = system;
        offered = cards;
        input = scene + SeedKnowledge.summaries(cards) + (cards.isEmpty() ? "" :
                "\n如需了解条件与取舍，可在完整的候选决定或回复 JSON 中附 knowledge_ids 数组，最多两个上述 ID；摘要已够用就直接作答。");
    }

    private String requested(String answer) {
        try {
            var node = JSON.readTree(answer);
            if (node == null || !node.path("knowledge_ids").isArray()) return "";
            var ids = new LinkedHashSet<String>();
            for (var id : node.path("knowledge_ids")) {
                if (id.isTextual() && offered.stream().anyMatch(c -> c.id().equals(id.textValue()))) ids.add(id.textValue());
                if (ids.size() == 2) break;
            }
            var body = new StringJoiner("\n");
            for (var card : offered) if (ids.contains(card.id())) body.add(card.fullText());
            return body.toString();
        } catch (java.io.IOException e) { return ""; }
    }

    CompletableFuture<DeepSeekApi.ChatResult> start(Executor client, BooleanSupplier current,
            IntFunction<PlayerDecisions.Charge> reserve, Function<String,CompletableFuture<DeepSeekApi.ChatResult>> send) {
        var charge = reserve.apply(bytes(input));
        if (charge == null) return null;
        var result = new CompletableFuture<DeepSeekApi.ChatResult>();
        CompletableFuture<DeepSeekApi.ChatResult> first;
        try { first = send.apply(input); }
        catch (RuntimeException e) { result.completeExceptionally(e); return result; }
        cancelWith(result, first);
        first.whenCompleteAsync((answer, error) -> {
            settle(charge, answer);
            if (result.isDone()) return;
            if (!current.getAsBoolean()) { result.cancel(false); return; }
            if (error != null) { result.completeExceptionally(error); return; }
            String details = answer != null && answer.ok() && !offered.isEmpty() ? requested(answer.answer()) : "";
            if (details.isEmpty()) { result.complete(answer); return; }
            String expanded = input + "\n已按需展开：\n" + details + "\n结合原现场直接提交最终决定或回复，本次读取已结束。";
            var extra = reserve.apply(bytes(expanded));
            if (extra == null) { result.complete(answer); return; }
            CompletableFuture<DeepSeekApi.ChatResult> second;
            try { second = send.apply(expanded); }
            catch (RuntimeException e) { result.complete(answer); return; }
            cancelWith(result, second);
            second.whenCompleteAsync((refined, failure) -> {
                settle(extra, refined);
                if (result.isDone()) return;
                if (!current.getAsBoolean()) { result.cancel(false); return; }
                result.complete(failure == null && usableRefinement(answer, refined) ? refined : answer);
            }, client);
        }, client);
        return result;
    }

    private static boolean usableRefinement(DeepSeekApi.ChatResult first, DeepSeekApi.ChatResult second) {
        if (second == null || !second.ok()) return false;
        try {
            var candidate = JSON.readTree(second.answer());
            var original = JSON.readTree(first.answer());
            if (candidate == null || !candidate.isObject()) return false;
            if (original.has("choice") && (!candidate.path("choice").isIntegralNumber() || !candidate.path("reason").isTextual())) return false;
            return !original.has("reply") || candidate.path("reply").isTextual();
        } catch (java.io.IOException e) { return false; }
    }

    private int bytes(String text) { return (system + text).getBytes(StandardCharsets.UTF_8).length; }
    private static void settle(PlayerDecisions.Charge charge, DeepSeekApi.ChatResult answer) {
        if (answer != null && answer.promptTokens() > 0) charge.tokens = answer.promptTokens() + answer.completionTokens();
    }
    private static void cancelWith(CompletableFuture<?> owner, CompletableFuture<?> active) {
        owner.whenComplete((answer, error) -> { if (owner.isCancelled()) active.cancel(true); });
    }
}
