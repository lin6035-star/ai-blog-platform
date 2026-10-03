package com.hailin.blogsystem.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ArticleRagEvalDatasetTests {

    private static final String RESOURCE = "/rag/article-rag-eval-v1.jsonl";
    private static final Set<String> ALLOWED_CASE_TYPES = Set.of(
            "TITLE_EXACT", "PARAPHRASE", "CODE_API", "HARD_NEGATIVE", "OUT_OF_DOMAIN"
    );

    @Test
    void draftDatasetHasStableSchemaAndCoversEachCaseType() throws Exception {
        List<ArticleRagEvalCase> cases = loadCases();

        assertThat(cases).hasSize(59);
        assertThat(cases).allSatisfy(testCase -> {
            assertThat(testCase.id()).isNotBlank();
            assertThat(testCase.query()).isNotBlank();
            assertThat(testCase.caseType()).isIn(ALLOWED_CASE_TYPES);
            assertThat(testCase.expectedArticleIds()).isNotNull();
            if (testCase.shouldRouteToRag()) {
                if ("HARD_NEGATIVE".equals(testCase.caseType())) {
                    assertThat(testCase.expectedArticleIds()).isEmpty();
                } else if ("OUT_OF_DOMAIN".equals(testCase.caseType())) {
                    throw new AssertionError("OUT_OF_DOMAIN 不能标记为应进入 RAG: " + testCase.id());
                }
            } else {
                assertThat(testCase.expectedArticleIds()).isEmpty();
            }
        });

        Set<String> ids = cases.stream()
                .map(ArticleRagEvalCase::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> caseTypes = cases.stream()
                .map(ArticleRagEvalCase::caseType)
                .collect(Collectors.toSet());
        assertThat(ids).hasSize(cases.size());
        assertThat(caseTypes)
                .containsExactlyInAnyOrderElementsOf(ALLOWED_CASE_TYPES);
    }

    private List<ArticleRagEvalCase> loadCases() throws Exception {
        InputStream input = Objects.requireNonNull(
                ArticleRagEvalDatasetTests.class.getResourceAsStream(RESOURCE),
                "RAG 评测集资源必须存在"
        );
        ObjectMapper mapper = new ObjectMapper();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return reader.lines()
                    .filter(line -> !line.isBlank())
                    .map(line -> read(mapper, line))
                    .toList();
        }
    }

    private ArticleRagEvalCase read(ObjectMapper mapper, String line) {
        try {
            return mapper.readValue(line, ArticleRagEvalCase.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("RAG 评测集 JSONL 解析失败: " + line, e);
        }
    }
}
