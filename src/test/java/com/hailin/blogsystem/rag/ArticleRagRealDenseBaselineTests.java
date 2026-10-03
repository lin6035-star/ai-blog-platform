package com.hailin.blogsystem.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.config.AiVectorStoreConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同源文章索引的无 Gate dense 基线。默认跳过，显式传
 * -DrunRealRagBaseline=true 才会调用真实 embedding 和 ES。
 */
@SpringBootTest(properties = {
        "blog.ai.rag.corpus.article-index-name=article_rag_active",
        "blog.ai.rag.corpus.memory-index-name=spring-ai-document-index",
        "blog.ai.rag.corpus.initialize-schema=false"
})
@EnabledIfSystemProperty(named = "runRealRagBaseline", matches = "true")
class ArticleRagRealDenseBaselineTests {

    private static final String RESOURCE = "/rag/article-rag-eval-v1.jsonl";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Qualifier(AiVectorStoreConfig.ARTICLE_RAG_VECTOR_STORE)
    @org.springframework.beans.factory.annotation.Autowired
    private VectorStore vectorStore;

    @Test
    void measuresQueryToDocumentScoreDistributionWithoutGate() throws Exception {
        List<ArticleRagEvalCase> cases = loadCases();
        List<Double> positiveBestScores = new ArrayList<>();
        List<Double> negativeTopScores = new ArrayList<>();
        int positiveCases = 0;
        int recallAt5Hits = 0;
        double reciprocalRankSum = 0D;

        for (ArticleRagEvalCase testCase : cases) {
            List<Document> documents = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(testCase.query())
                            .topK(10)
                            .similarityThreshold(0.0)
                            .filterExpression(articleSourceFilter())
                            .build()
            );
            List<Hit> hits = deduplicateByArticle(documents);
            Set<Long> expectedIds = new LinkedHashSet<>(testCase.expectedArticleIds());

            if (!expectedIds.isEmpty()) {
                positiveCases++;
                int expectedRank = firstExpectedRank(hits, expectedIds, 5);
                if (expectedRank > 0) {
                    recallAt5Hits++;
                    reciprocalRankSum += 1D / expectedRank;
                }
                hits.stream()
                        .filter(hit -> expectedIds.contains(hit.articleId()))
                        .map(Hit::score)
                        .max(Double::compareTo)
                        .ifPresent(positiveBestScores::add);
            } else if ("HARD_NEGATIVE".equals(testCase.caseType())
                    || "OUT_OF_DOMAIN".equals(testCase.caseType())) {
                hits.stream().findFirst().map(Hit::score).ifPresent(negativeTopScores::add);
            }

            System.out.printf(
                    Locale.ROOT,
                    "[RAG-BASELINE] id=%s type=%s expected=%s hits=%s%n",
                    testCase.id(),
                    testCase.caseType(),
                    expectedIds,
                    hits.stream().limit(5).toList()
            );
        }

        double recallAt5 = ratio(recallAt5Hits, positiveCases);
        double mrrAt5 = positiveCases == 0 ? 0D : reciprocalRankSum / positiveCases;
        System.out.printf(
                Locale.ROOT,
                "[RAG-BASELINE-SUMMARY] positives=%d recall@5=%d/%d(%.4f) mrr@5=%.4f positiveBest=%s negativeTop=%s%n",
                positiveCases,
                recallAt5Hits,
                positiveCases,
                recallAt5,
                mrrAt5,
                distribution(positiveBestScores),
                distribution(negativeTopScores)
        );

        assertThat(positiveBestScores).isNotEmpty();
        assertThat(negativeTopScores).isNotEmpty();
    }

    private Filter.Expression articleSourceFilter() {
        return new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key("source"),
                new Filter.Value("article")
        );
    }

    private List<Hit> deduplicateByArticle(List<Document> documents) {
        Map<Long, Hit> hits = new LinkedHashMap<>();
        for (Document document : documents == null ? List.<Document>of() : documents) {
            Long articleId = toLong(document.getMetadata().get("articleId"));
            if (articleId == null) {
                continue;
            }
            hits.putIfAbsent(articleId, new Hit(articleId, document.getScore()));
        }
        return List.copyOf(hits.values());
    }

    private int firstExpectedRank(List<Hit> hits, Set<Long> expectedIds, int limit) {
        for (int i = 0; i < Math.min(limit, hits.size()); i++) {
            if (expectedIds.contains(hits.get(i).articleId())) {
                return i + 1;
            }
        }
        return -1;
    }

    private String distribution(List<Double> values) {
        if (values.isEmpty()) {
            return "empty";
        }
        List<Double> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        return String.format(
                Locale.ROOT,
                "n=%d,min=%.4f,p25=%.4f,p50=%.4f,p75=%.4f,max=%.4f",
                sorted.size(),
                sorted.get(0),
                percentile(sorted, 0.25),
                percentile(sorted, 0.50),
                percentile(sorted, 0.75),
                sorted.get(sorted.size() - 1)
        );
    }

    private double percentile(List<Double> sorted, double percentile) {
        int index = (int) Math.round((sorted.size() - 1) * percentile);
        return sorted.get(index);
    }

    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0D : (double) numerator / denominator;
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }

    private List<ArticleRagEvalCase> loadCases() throws Exception {
        var input = Objects.requireNonNull(
                ArticleRagRealDenseBaselineTests.class.getResourceAsStream(RESOURCE),
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
            throw new IllegalArgumentException("RAG 评测集 JSONL 解析失败", e);
        }
    }

    private record Hit(Long articleId, double score) {
        @Override
        public String toString() {
            return articleId + ":" + String.format(Locale.ROOT, "%.4f", score);
        }
    }
}
