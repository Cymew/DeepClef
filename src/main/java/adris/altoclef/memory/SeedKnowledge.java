package adris.altoclef.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;

/** Read-only reference material, independent of every world and role database. */
public final class SeedKnowledge {
    private static final String FILE = "/assets/deepclef/memory/player_seed_knowledge_java_26.3.json";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern WORD = Pattern.compile("[a-z0-9_.:/-]+|[\\p{IsHan}]+");
    private static final Set<String> COMMON = Set.of("怎么","什么","可以","一个","现在","这个","还是","时候","东西","需要","没有","如何","一下","应该","知道","哪里","在哪");
    public record Choice(String when, String consider) {}
    public record Detail(String situation, List<Choice> choices, String move_on, String limits) {}
    public record Card(String id, int revision, String title, String group, List<String> cues, List<String> advancement_ids,
                       String summary, Detail detail, List<String> related_ids, List<String> sources) {
        public String shortText() { return "[" + id + "] " + summary; }
        public String fullText() {
            var lines = new StringJoiner("\n").add("原始记忆 [" + id + " r" + revision + "] " + title)
                    .add(summary).add("适用情境：" + detail.situation);
            for (Choice choice : detail.choices) lines.add("• " + choice.when + "：" + choice.consider);
            lines.add("推进或改道：" + detail.move_on);
            if (!detail.limits.isBlank()) lines.add("适用限制：" + detail.limits);
            return lines.toString();
        }
    }
    record Pack(int schema_version, String edition, String target_version, List<Card> cards) {}
    private record Entry(Card card, Set<String> terms) {}
    private final Map<String,Entry> entries;

    private SeedKnowledge(Map<String,Entry> entries) { this.entries = Map.copyOf(entries); }
    private static final class Holder { static final SeedKnowledge BOOK = bundled(); }
    public static SeedKnowledge book() { return Holder.BOOK; }

    private static SeedKnowledge bundled() {
        try (var stream = SeedKnowledge.class.getResourceAsStream(FILE)) { return read(stream); }
        catch (IOException | RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(SeedKnowledge.class).error("Could not load bundled DeepClef knowledge", e);
            return new SeedKnowledge(Map.of());
        }
    }
    static SeedKnowledge read(InputStream stream) throws IOException {
        if (stream == null) throw new IOException("Missing " + FILE);
        var pack = JSON.readValue(stream, Pack.class);
        if (pack == null || pack.schema_version != 1 || !"java".equals(pack.edition) || !"26.3".equals(pack.target_version)
                || pack.cards == null || pack.cards.isEmpty()) throw new IOException("Unsupported knowledge pack");
        Map<String,Entry> entries = new LinkedHashMap<>();
        for (Card c : pack.cards) {
            if (c == null || c.id == null || !c.id.matches("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+") || c.revision < 1
                    || blank(c.title) || blank(c.group) || blank(c.summary) || c.cues == null || c.cues.isEmpty()
                    || c.cues.stream().anyMatch(SeedKnowledge::blank) || c.advancement_ids == null || c.sources == null
                    || c.related_ids == null || c.detail == null || blank(c.detail.situation) || blank(c.detail.move_on)
                    || c.detail.limits == null || c.detail.choices == null || c.detail.choices.isEmpty()
                    || c.detail.choices.stream().anyMatch(x -> x == null || blank(x.when) || blank(x.consider)))
                throw new IOException("Malformed knowledge card");
            var keywords = terms(c.title + " " + String.join(" ", c.cues) + " " + c.summary);
            if (entries.putIfAbsent(c.id, new Entry(c, keywords)) != null) throw new IOException("Duplicate card " + c.id);
        }
        for (Entry e : entries.values()) if (!entries.keySet().containsAll(e.card.related_ids))
            throw new IOException("Broken reference in " + e.card.id);
        return new SeedKnowledge(entries);
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static Set<String> terms(String text) {
        var set = new HashSet<String>();
        String normalized = text.toLowerCase(Locale.ROOT).replace("打龙", "打末影龙").replace("屠龙", "打末影龙")
                .replace("过夜", "天黑").replace("第一晚", "第一夜 天黑").replace("地狱", "下界")
                .replace("翅膀", "鞘翅");
        var words = WORD.matcher(normalized);
        while (words.find()) {
            String w = words.group();
            if (Character.UnicodeScript.of(w.codePointAt(0)) != Character.UnicodeScript.HAN) set.add(w);
            else for (int i = 1; i < w.length(); i++) set.add(w.substring(i - 1, i + 1));
        }
        set.removeAll(COMMON);
        return set;
    }

    public Optional<Card> find(String id) { return Optional.ofNullable(entries.get(id)).map(Entry::card); }
    public int size() { return entries.size(); }

    private static boolean localFact(String q) {
        return q.matches("(?s).*(我家|我放的|刚才|上次|那个|我的|我们的|这个存档|本档).*(哪儿|哪里|在哪|位置|坐标|几|还剩|多少).*")
                || q.matches("(?s).*(箱子|家|基地|营地|队友).*(具体坐标|坐标是多少).*");
    }

    public List<Card> search(String question, String scene, String advancement) {
        if (localFact(question)) return List.of();
        var focus = terms(question);
        var context = terms(scene);
        record Hit(Card card, int score) {}
        var hits = new ArrayList<Hit>();
        for (Entry e : entries.values()) {
            var c = e.card;
            int score = 0;
            for (String t : e.terms) {
                if (focus.contains(t)) score += 4;
                if (context.contains(t)) score++;
            }
            for (String cue : c.cues) {
                if (question.contains(cue)) score += 24;
                if (scene.contains(cue)) score += 8;
            }
            if (question.contains(c.id)) score += 100;
            if (!advancement.isEmpty() && c.advancement_ids.stream().anyMatch(id ->
                    id.replaceFirst("^minecraft:", "").equals(advancement.replaceFirst("^minecraft:", "")))) score += 4;
            if (score >= 4) hits.add(new Hit(c, score));
        }
        return hits.stream().sorted(Comparator.comparingInt(Hit::score).reversed().thenComparing(h -> h.card.id))
                .limit(3).map(Hit::card).toList();
    }

    public static String summaries(List<Card> cards) {
        if (cards.isEmpty()) return "";
        return "\n原始记忆（跨档通识，按现场取舍）：\n" + String.join("\n", cards.stream().map(Card::shortText).toList());
    }

    public String lookup(String keyword) {
        if (blank(keyword)) return "请输入想回忆的主题或知识卡 ID。";
        return find(keyword.strip()).map(Card::fullText).orElseGet(() -> {
            String result = summaries(search(keyword, "", ""));
            return result.isEmpty() ? "没有匹配的原始记忆。" : result;
        });
    }
}
