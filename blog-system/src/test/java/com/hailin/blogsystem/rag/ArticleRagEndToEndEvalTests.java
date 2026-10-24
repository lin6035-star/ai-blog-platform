package com.hailin.blogsystem.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.rag.ArticleRagRetrievalTrace;
import com.hailin.blogsystem.ai.rag.ArticleRagSearchService;
import com.hailin.blogsystem.ai.rag.ArticleRagTraceRecorder;
import com.hailin.blogsystem.ai.rag.index.ArticleRagIndexHealthService;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchResult;
import com.hailin.blogsystem.service.ArticlesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 端到端评测：跑**真实主链路**——Dense → keyword → bounded OR fallback → rerank → Gate，
 * 而不是像两个 baseline 那样把 Dense 与 Rerank 分开测。
 *
 * <p>它补上两个只有端到端才拿得到的指标：
 *
 * <ul>
 *   <li><b>Gate 拒绝原因分布</b>——靠收集 {@link ArticleRagRetrievalTrace#gateDecisions()}，
 *       否则只能知道"拒了几条"，无法回答"为什么拒"；</li>
 *   <li><b>fallback 恢复率</b>——keyword 零命中触发受限 OR fallback 后，有多少样例最终仍拿到候选。</li>
 * </ul>
 *
 * <p><b>范围</b>：只跑 {@code expectedRetrievalMode = ARTICLE_SEARCH} 的样例。
 * 路由层（"该不该进检索"）**不在本 runner 内**——那是意图分类器的职责
 * （见 {@code AiIntentClassifierIntegrationTests}），在这里重复判断只会把两类错误混在一起。
 *
 * <p>默认跳过；显式传 {@code -DrunRagEndToEndEval=true} 才会访问真实 ES / embedding / rerank。
 */
@SpringBootTest(properties = {
        "blog.ai.rag.corpus.article-index-name=${rag.eval.index:article_rag_active}",
        "blog.ai.rag.corpus.memory-index-name=spring-ai-document-index",
        "blog.ai.rag.corpus.initialize-schema=false"
})
@Import({
        ArticleRagEndToEndEvalTests.TraceCollectorConfig.class,
        ArticleRagEndToEndEvalTests.StubArticlesConfig.class
})
@EnabledIfSystemProperty(named = "runRagEndToEndEval", matches = "true")
class ArticleRagEndToEndEvalTests {

    private static final String RESOURCE = "/rag/article-rag-eval-v1.jsonl";
    private static final String ARTICLE_SEARCH = "ARTICLE_SEARCH";

    @Autowired
    private ArticleRagSearchService articleRagSearchService;

    @Autowired
    private ArticleRagIndexHealthService articleRagIndexHealthService;

    @Autowired
    private CollectingTraceRecorder traceCollector;

    @Value("${blog.ai.rag.corpus.article-index-name}")
    private String indexName;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void measuresEndToEndRecallNegativeRejectsGateReasonsAndFallbackRecovery() throws Exception {
        // @SpringBootTest 启动后 canary 巡检还没跑（initialDelay=1s），状态是 SUSPECT，
        // 而 dense 检索只在 HEALTHY 放行 —— 不等它就会静默退化成"只有 keyword 分支"的评测。
        articleRagIndexHealthService.inspect();
        assertThat(articleRagIndexHealthService.denseSearchAllowed())
                .as("dense 必须放行，否则本评测只跑到 keyword 分支")
                .isTrue();

        String requestedCases = System.getProperty("rag.eval.case", "").trim();
        Set<String> requestedCaseIds = requestedCases.isBlank()
                ? Set.of()
                : Arrays.stream(requestedCases.split(","))
                .map(String::trim)
                .filter(id -> !id.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // 按 caseType 缩样：TITLE_EXACT 天然容易、占比又最大，阈值类改动用
        // -Drag.eval.type=PARAPHRASE,HARD_NEGATIVE 跑"真实口语 + 阈值考验"这两类更有信息量。
        String requestedTypes = System.getProperty("rag.eval.type", "").trim();
        Set<String> requestedCaseTypes = requestedTypes.isBlank()
                ? Set.of()
                : Arrays.stream(requestedTypes.split(","))
                .map(String::trim)
                .filter(type -> !type.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<ArticleRagEvalCase> cases = loadCases().stream()
                .filter(testCase -> ARTICLE_SEARCH.equals(testCase.expectedRetrievalMode()))
                .filter(testCase -> requestedCaseIds.isEmpty() || requestedCaseIds.contains(testCase.id()))
                .filter(testCase -> requestedCaseTypes.isEmpty() || requestedCaseTypes.contains(testCase.caseType()))
                .toList();
        assertThat(cases).isNotEmpty();

        int positives = 0;
        int recallAt5Hits = 0;
        int negatives = 0;
        int negativeFalseAccepts = 0;
        int fallbackTriggered = 0;
        int fallbackRecovered = 0;
        Map<String, Integer> gateReasonCounts = new TreeMap<>();
        Map<String, Integer> strategyCounts = new TreeMap<>();
        List<Integer> mergedSizes = new ArrayList<>();
        List<Integer> mergedDistinctArticles = new ArrayList<>();
        List<Integer> mergedMaxPerArticle = new ArrayList<>();
        List<Integer> acceptedChunkLengths = new ArrayList<>();
        int acceptedChunks = 0;

        StringBuilder report = new StringBuilder();

        for (ArticleRagEvalCase testCase : cases) {
            AiIntent intent = new AiIntent();
            intent.setIntent(ARTICLE_SEARCH);
            intent.setKeyWord(testCase.query());

            traceCollector.clear();
            ArticleRagSearchResult result = articleRagSearchService.search(testCase.query(), intent);
            ArticleRagRetrievalTrace trace = traceCollector.latest(testCase.query());

            List<Long> returned = result.contexts().stream()
                    .map(ArticleRagContext::articleId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();

            // chunk 级观测：送进 prompt 的片段有几条、多长、还剩多少碎片。
            // 文章级 Recall 对 chunk 质量不敏感，这一行才是判断"结构化切块值不值"的补充口径。
            acceptedChunks += result.contexts().size();
            result.contexts().forEach(context -> acceptedChunkLengths.add(
                    context.content() == null ? 0 : context.content().length()));
            Set<Long> expected = new LinkedHashSet<>(testCase.expectedArticleIds());

            if (!expected.isEmpty()) {
                positives++;
                if (returned.stream().limit(5).anyMatch(expected::contains)) {
                    recallAt5Hits++;
                }
            } else if ("HARD_NEGATIVE".equals(testCase.caseType())
                    || "OUT_OF_DOMAIN".equals(testCase.caseType())) {
                negatives++;
                if (!returned.isEmpty()) {
                    negativeFalseAccepts++;
                }
            }

            boolean fallback = trace != null && trace.keywordFallbackTriggered();
            if (fallback) {
                fallbackTriggered++;
                if (!returned.isEmpty()) {
                    fallbackRecovered++;
                }
            }
            if (trace != null) {
                trace.gateDecisions().forEach(decision ->
                        gateReasonCounts.merge(String.valueOf(decision.reason()), 1, Integer::sum));

                // 文章级集中度：合并后的候选里，同一篇文章占掉了多少个名额。
                // 结构化切块（chunk-v2）让长文章有 40+ 个 chunk，而每块的 embeddingContent 都带完整标题，
                // 于是同一篇的 chunk 分数系统性接近，很容易把别的文章挤出候选。
                if (!trace.mergedCandidates().isEmpty()) {
                    Map<Long, Long> perArticle = trace.mergedCandidates().stream()
                            .filter(candidate -> candidate.articleId() != null)
                            .collect(Collectors.groupingBy(
                                    ArticleRagRetrievalTrace.Candidate::articleId, Collectors.counting()));
                    mergedSizes.add(trace.mergedCandidates().size());
                    mergedDistinctArticles.add(perArticle.size());
                    mergedMaxPerArticle.add(perArticle.values().stream()
                            .mapToInt(Long::intValue).max().orElse(0));
                }
            }
            strategyCounts.merge(String.valueOf(result.strategy()), 1, Integer::sum);

            report.append(String.format(
                    Locale.ROOT,
                    "[RAG-E2E] id=%s type=%s strategy=%s fallback=%s returned=%s expected=%s gate=%s%n",
                    testCase.id(),
                    testCase.caseType(),
                    result.strategy(),
                    fallback,
                    returned,
                    expected,
                    formatGateDecisions(trace)
            ));
        }

        report.append(String.format(
                Locale.ROOT,
                "[RAG-E2E-SUMMARY] cases=%d positives=%d recall@5=%d/%d(%.4f) "
                        + "negatives=%d falseAccepts=%d(%.4f)%n",
                cases.size(), positives, recallAt5Hits, positives, ratio(recallAt5Hits, positives),
                negatives, negativeFalseAccepts, ratio(negativeFalseAccepts, negatives)
        ));
        report.append(String.format(Locale.ROOT,
                "[RAG-E2E-FALLBACK] triggered=%d recovered=%d(%.4f)%n",
                fallbackTriggered, fallbackRecovered, ratio(fallbackRecovered, fallbackTriggered)));
        report.append("[RAG-E2E-STRATEGIES] ").append(strategyCounts).append('\n');
        report.append("[RAG-E2E-GATE-REASONS] ").append(gateReasonCounts).append('\n');
        report.append(String.format(
                Locale.ROOT,
                "[RAG-E2E-CONCENTRATION] mergedAvgSize=%.1f mergedAvgDistinctArticles=%.1f "
                        + "mergedAvgMaxPerArticle=%.1f mergedMaxPerArticleObserved=%d%n",
                average(mergedSizes), average(mergedDistinctArticles), average(mergedMaxPerArticle),
                mergedMaxPerArticle.stream().mapToInt(Integer::intValue).max().orElse(0)
        ));
        long tinyChunks = acceptedChunkLengths.stream().filter(length -> length < 100).count();
        report.append(String.format(
                Locale.ROOT,
                "[RAG-E2E-CHUNKS] acceptedChunks=%d avgPerCase=%.1f lenMin=%d lenP50=%.0f lenMax=%d "
                        + "tiny(<100char)=%d(%.3f)%n",
                acceptedChunks,
                ratio(acceptedChunks, cases.size()),
                acceptedChunkLengths.stream().mapToInt(Integer::intValue).min().orElse(0),
                median(acceptedChunkLengths),
                acceptedChunkLengths.stream().mapToInt(Integer::intValue).max().orElse(0),
                tinyChunks,
                (double) tinyChunks / Math.max(1, acceptedChunkLengths.size())
        ));
        report.append("[RAG-E2E-INDEX] ").append(indexName).append('\n');

        System.out.print(report);
        Path output = Path.of("target", "rag-end-to-end-eval-" + indexSuffix() + ".txt");
        Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
        System.out.println("[RAG-E2E] 报告已写入 " + output.toAbsolutePath());
    }

    private String indexSuffix() {
        String name = indexName == null ? "unknown" : indexName;
        int chunkAt = name.indexOf("-chunk-");
        return chunkAt < 0 ? name.replaceAll("[^A-Za-z0-9._-]", "_") : name.substring(chunkAt + 7);
    }

    private double average(List<Integer> values) {
        return values.isEmpty() ? 0D : values.stream().mapToInt(Integer::intValue).average().orElse(0D);
    }

    private double median(List<Integer> values) {
        if (values.isEmpty()) {
            return 0D;
        }
        List<Integer> sorted = values.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0D : (double) numerator / denominator;
    }

    private List<String> formatGateDecisions(ArticleRagRetrievalTrace trace) {
        if (trace == null) {
            return List.of();
        }
        return trace.gateDecisions().stream()
                .map(decision -> decision.articleId() + ":"
                        + (decision.rerankScore() == null
                        ? "null"
                        : String.format(Locale.ROOT, "%.4f", decision.rerankScore()))
                        + ":" + decision.reason())
                .toList();
    }

    private List<ArticleRagEvalCase> loadCases() throws Exception {
        InputStream input = Objects.requireNonNull(
                ArticleRagEndToEndEvalTests.class.getResourceAsStream(RESOURCE),
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

    /**
     * 用内存收集器替换线上日志实现，把每次检索的 trace 留下来供指标计算。
     * 标 {@code @Primary} 以覆盖 {@code LoggingArticleRagTraceRecorder}。
     */
    @TestConfiguration
    static class TraceCollectorConfig {

        @Bean
        @Primary
        CollectingTraceRecorder collectingTraceRecorder() {
            return new CollectingTraceRecorder();
        }
    }

    static class CollectingTraceRecorder implements ArticleRagTraceRecorder {

        private final Map<String, ArticleRagRetrievalTrace> byQuestion = new ConcurrentHashMap<>();

        @Override
        public void record(ArticleRagRetrievalTrace trace) {
            if (trace != null && trace.question() != null) {
                byQuestion.put(trace.question(), trace);
            }
        }

        ArticleRagRetrievalTrace latest(String question) {
            return byQuestion.get(question);
        }

        void clear() {
            byQuestion.clear();
        }
    }

    /**
     * 桩掉**数据源边界**，原因是两个数据源在本测试里不对齐：
     * RAG 索引在真实 ES 里，而 {@code @SpringBootTest} 跑在 H2 测试库上（里面没有文章）。
     *
     * <p>不桩会怎样：{@code ArticleRagSearchService.retainCurrentlyPublished()} 拿 ES 的 articleId 去查库，
     * H2 查不到就按 fail-closed 全部过滤 → **65 条样例全部变成 EMPTY**（实测踩过）。
     *
     * <p>这两条路径各有独立测试覆盖（可见性复核、MySQL LIKE 兜底），端到端 runner 关注的是
     * Dense → keyword → fallback → rerank → Gate 这条主链路。
     */
    @TestConfiguration
    static class StubArticlesConfig {

        @Bean
        @Primary
        ArticlesService stubArticlesService() {
            ArticlesService stub = Mockito.mock(ArticlesService.class);

            Mockito.when(stub.listByIds(Mockito.anyCollection())).thenAnswer(invocation -> {
                Collection<Long> ids = invocation.getArgument(0);
                return ids.stream().map(id -> {
                    Articles article = new Articles();
                    article.setId(id);
                    article.setStatus(1);
                    return article;
                }).toList();
            });

            // MySQL LIKE 兜底不参与：它是 ES 不可用时的降级路径，会掩盖真实检索结果
            Mockito.when(stub.getArticles(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                    .thenReturn(null);

            return stub;
        }
    }
}
