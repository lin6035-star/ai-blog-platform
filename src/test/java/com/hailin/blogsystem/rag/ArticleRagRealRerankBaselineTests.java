package com.hailin.blogsystem.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.rag.ArticleRagRankResult;
import com.hailin.blogsystem.ai.rag.ArticleRagRanker;
import com.hailin.blogsystem.config.AiVectorStoreConfig;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 默认跳过；显式开启时调用真实 embedding、ES 和 rerank 服务采样 Gate 分布。 */
@SpringBootTest(properties = {
        "blog.ai.rag.corpus.article-index-name=article_rag_active",
        "blog.ai.rag.corpus.memory-index-name=spring-ai-document-index",
        "blog.ai.rag.corpus.initialize-schema=false",
        "blog.ai.rag.rerank.enabled=true"
})
@EnabledIfSystemProperty(named = "runRealRagRerankBaseline", matches = "true")
class ArticleRagRealRerankBaselineTests {

    private static final String RESOURCE = "/rag/article-rag-eval-v1.jsonl";

    @Autowired
    @Qualifier(AiVectorStoreConfig.ARTICLE_RAG_VECTOR_STORE)
    private VectorStore vectorStore;

    @Autowired
    private ArticleRagRanker ranker;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void measuresRerankScoreDistribution() throws Exception {
        List<Double> positiveBestScores = new ArrayList<>();
        List<Double> negativeTopScores = new ArrayList<>();
        int modelApplied = 0;

        for (ArticleRagEvalCase testCase : loadCases()) {
            List<ArticleRagContext> contexts = toContexts(vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(testCase.query())
                            .topK(10)
                            .similarityThreshold(0.0)
                            .filterExpression(articleSourceFilter())
                            .build()
            ));
            ArticleRagRankResult result = ranker.rankWithEvidence(testCase.query(), contexts);
            if (result.status() == ArticleRagRankResult.Status.MODEL_APPLIED) {
                modelApplied++;
            }

            Set<Long> expectedIds = new LinkedHashSet<>(testCase.expectedArticleIds());
            if (!expectedIds.isEmpty()) {
                result.candidates().stream()
                        .filter(candidate -> expectedIds.contains(candidate.context().articleId()))
                        .map(ArticleRagRankResult.Candidate::relevanceScore)
                        .filter(Objects::nonNull)
                        .max(Double::compareTo)
                        .ifPresent(positiveBestScores::add);
            } else if ("HARD_NEGATIVE".equals(testCase.caseType())
                    || "OUT_OF_DOMAIN".equals(testCase.caseType())) {
                result.candidates().stream()
                        .map(ArticleRagRankResult.Candidate::relevanceScore)
                        .filter(Objects::nonNull)
                        .max(Double::compareTo)
                        .ifPresent(negativeTopScores::add);
            }

            System.out.printf(
                    Locale.ROOT,
                    "[RAG-RERANK] id=%s type=%s status=%s expected=%s hits=%s%n",
                    testCase.id(),
                    testCase.caseType(),
                    result.status(),
                    expectedIds,
                    result.candidates().stream().limit(5).map(this::formatCandidate).toList()
            );
        }

        System.out.printf(
                Locale.ROOT,
                "[RAG-RERANK-SUMMARY] modelApplied=%d positiveBest=%s negativeTop=%s%n",
                modelApplied,
                distribution(positiveBestScores),
                distribution(negativeTopScores)
        );
        assertThat(modelApplied).isEqualTo(59);
        assertThat(positiveBestScores).hasSize(49);
        assertThat(negativeTopScores).hasSize(10);
    }

    private List<ArticleRagContext> toContexts(List<Document> documents) {
        if (documents == null) {
            return List.of();
        }
        return documents.stream().map(document -> {
            var metadata = document.getMetadata();
            Object rawContent = metadata.get("rawContent");
            return new ArticleRagContext(
                    toLong(metadata.get("articleId")),
                    Objects.toString(metadata.get("title"), ""),
                    toInteger(metadata.get("chunkIndex")),
                    rawContent == null ? document.getText() : rawContent.toString()
            );
        }).toList();
    }

    private Filter.Expression articleSourceFilter() {
        return new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key("source"),
                new Filter.Value("article")
        );
    }

    private String formatCandidate(ArticleRagRankResult.Candidate candidate) {
        return candidate.context().articleId() + ":"
                + String.format(Locale.ROOT, "%.4f", candidate.relevanceScore());
    }

    private String distribution(List<Double> values) {
        if (values.isEmpty()) {
            return "empty";
        }
        List<Double> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        return String.format(
                Locale.ROOT,
                "n=%d,min=%.4f,p25=%.4f,p50=%.4f,p75=%.4f,max=%.4f",
                sorted.size(), sorted.get(0), percentile(sorted, 0.25), percentile(sorted, 0.50),
                percentile(sorted, 0.75), sorted.get(sorted.size() - 1)
        );
    }

    private double percentile(List<Double> sorted, double percentile) {
        return sorted.get((int) Math.round((sorted.size() - 1) * percentile));
    }

    private Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : Long.valueOf(value.toString());
    }

    private Integer toInteger(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.valueOf(value.toString());
    }

    private List<ArticleRagEvalCase> loadCases() throws Exception {
        var input = Objects.requireNonNull(
                ArticleRagRealRerankBaselineTests.class.getResourceAsStream(RESOURCE),
                "RAG 评测集资源必须存在"
        );
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return reader.lines().filter(line -> !line.isBlank()).map(this::readCase).toList();
        }
    }

    private ArticleRagEvalCase readCase(String line) {
        try {
            return objectMapper.readValue(line, ArticleRagEvalCase.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("RAG 评测集 JSONL 解析失败", e);
        }
    }
}
