package com.hailin.blogsystem.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.indices.AnalyzeResponse;
import co.elastic.clients.elasticsearch.indices.analyze.AnalyzeToken;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code HARD_NEGATIVE} 样例的离线术语核验：**只查 Elasticsearch，不调用 embedding**。
 *
 * <p><b>它解决什么问题</b>：难负例的价值取决于"库里确实没有答案"这个前提，而这个前提很容易标错。
 * 2026-10-03 就发生过一次——「Kafka 事务消息怎么实现」被标成难负例，实测 RocketMQ 文章里
 * "事务消息"出现了 5 次，于是 Gate 的正确放行被误判成"漏网"。人工逐条翻文章成本高，
 * 这里先把"术语在库内出现过"的样例圈出来，人工只需复核这份清单。
 *
 * <p><b>怎么读输出</b>：命中篇数少（1~3 篇）的术语最值得警惕——常见词（如"缓存"）会命中几十篇，
 * 而"事务消息"这种专有主题词命中很少却命中，通常就意味着文章确实覆盖了该主题。
 *
 * <p>默认跳过；显式传 {@code -DrunRagNegativeAudit=true} 才会连真实 ES 运行。
 */
@SpringBootTest(properties = {
        "blog.ai.rag.corpus.article-index-name=article_rag_active",
        "blog.ai.rag.corpus.memory-index-name=spring-ai-document-index",
        "blog.ai.rag.corpus.initialize-schema=false"
})
@EnabledIfSystemProperty(named = "runRagNegativeAudit", matches = "true")
class ArticleRagHardNegativeAuditTests {

    private static final String RESOURCE = "/rag/article-rag-eval-v1.jsonl";
    private static final int MAX_TERMS_PER_QUERY = 30;
    /**
     * 命中篇数不超过这个值的术语才算"专有词"，值得人工复核。
     *
     * <p>只有"低频命中"才有鉴别力：像"缓存"命中 37 篇、"Java"命中 51 篇，命中本身就是必然的，
     * 报出来只会把每条样例都标成"需复核"。真正可疑的是"哨兵""事务消息"这种——命中很少却命中。
     */
    private static final long COMMON_TERM_THRESHOLD = 5L;

    /** 疑问词、口语壳词、泛化词：抽成术语只会制造噪声。 */
    private static final Set<String> NOISE = Set.of(
            "怎么", "什么", "哪个", "哪些", "如何", "是否", "可以", "适合", "有没有", "为什么",
            "需要", "应该", "一个", "一次", "我们", "他们", "这个", "那个", "一下", "还有",
            "直接", "替代", "规划", "合适", "关于", "就是", "还是", "以及", "进行", "实现",
            "文章", "内容", "里面", "中的", "里有", "这里", "那里", "咱们"
    );

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @Value("${blog.ai.rag.corpus.article-index-name}")
    private String indexName;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void flagsHardNegativesWhoseTermsActuallyAppearInIndexedArticles() throws Exception {
        List<ArticleRagEvalCase> hardNegatives = loadCases().stream()
                .filter(testCase -> "HARD_NEGATIVE".equals(testCase.caseType()))
                .toList();
        assertThat(hardNegatives).isNotEmpty();

        StringBuilder report = new StringBuilder();
        int flagged = 0;
        for (ArticleRagEvalCase testCase : hardNegatives) {
            List<String> terms = extractTerms(testCase.query());
            List<String> suspicious = new ArrayList<>();
            int zeroHits = 0;
            int commonHits = 0;

            for (String term : terms) {
                TermHit hit = phraseHit(term);
                if (hit.total() == 0) {
                    zeroHits++;
                } else if (hit.total() <= COMMON_TERM_THRESHOLD) {
                    suspicious.add(String.format(Locale.ROOT, "%s -> %d 篇 %s", term, hit.total(), hit.titles()));
                } else {
                    commonHits++;
                }
            }

            report.append(String.format(Locale.ROOT,
                    "[NEG-AUDIT] %s | %s | 术语=%d 零命中=%d 高频常见词=%d 可疑=%d%n",
                    testCase.id(), testCase.query(), terms.size(), zeroHits, commonHits, suspicious.size()));

            if (suspicious.isEmpty()) {
                report.append("  → 没有「专有词低命中」信号：支持它作为纯负例\n");
                continue;
            }

            flagged++;
            suspicious.forEach(line -> report.append("  ⚠️ ").append(line).append('\n'));
            report.append("  → 请人工确认这些低命中专有词是否意味着文章其实部分覆盖\n");
        }

        report.append(String.format(Locale.ROOT, "[NEG-AUDIT-SUMMARY] hardNegatives=%d needHumanReview=%d%n",
                hardNegatives.size(), flagged));

        // 控制台在 Windows 上会按本地代码页输出中文，直接看会乱码；同时落一份 UTF-8 报告便于复核
        System.out.print(report);
        Path output = Path.of("target", "rag-negative-audit.txt");
        Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
        System.out.println("[NEG-AUDIT] 报告已写入 " + output.toAbsolutePath());
    }

    /**
     * 抽取候选术语。
     *
     * <p><b>用 IK 分词，不用中文 n-gram。</b>前两版试过 2~4 字窗口，噪声无法接受：
     * 2-gram 会切出"个更""更适""合规"，收紧到 3~4 字又留下"更适合""的内容""原理是"
     * —— 全是跨词边界的功能词组合，命中本身就是必然的，报出来只会把样例全部标成"需复核"
     * （第一版实测 13/13 全部误报）。项目本身装了 IK 插件（站内索引用它），直接借来分词即可。
     */
    List<String> extractTerms(String query) throws Exception {
        AnalyzeResponse response = elasticsearchClient.indices().analyze(a -> a
                .analyzer("ik_max_word")
                .text(query));

        return response.tokens().stream()
                .map(AnalyzeToken::token)
                .filter(token -> token.length() >= 2)
                .filter(token -> !NOISE.contains(token))
                .distinct()
                .limit(MAX_TERMS_PER_QUERY)
                .toList();
    }

    private TermHit phraseHit(String term) throws Exception {
        SearchResponse<JsonData> response = elasticsearchClient.search(s -> s
                        .index(indexName)
                        .size(3)
                        .source(src -> src.filter(filter -> filter.includes("metadata.title")))
                        .query(q -> q.bool(b -> b
                                .must(must -> must.matchPhrase(phrase -> phrase
                                        .field("content")
                                        .query(term)))
                                .filter(filter -> filter.term(t -> t
                                        .field("metadata.source.keyword")
                                        .value(FieldValue.of("article")))))),
                JsonData.class
        );

        long total = response.hits().total() == null ? 0L : response.hits().total().value();
        List<String> titles = response.hits().hits().stream()
                .map(hit -> hit.source() == null
                        ? ""
                        : hit.source().toJson().asJsonObject().getJsonObject("metadata").getString("title", ""))
                .filter(title -> !title.isBlank())
                .toList();

        return new TermHit(total, titles);
    }

    private List<ArticleRagEvalCase> loadCases() throws Exception {
        InputStream input = Objects.requireNonNull(
                ArticleRagHardNegativeAuditTests.class.getResourceAsStream(RESOURCE),
                "RAG 评测集资源必须存在"
        );
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return reader.lines()
                    .filter(line -> !line.isBlank())
                    .map(this::readCase)
                    .toList();
        }
    }

    private ArticleRagEvalCase readCase(String line) {
        try {
            return objectMapper.readValue(line, ArticleRagEvalCase.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("RAG 评测集 JSONL 解析失败: " + line, e);
        }
    }

    private record TermHit(long total, List<String> titles) {
    }
}
