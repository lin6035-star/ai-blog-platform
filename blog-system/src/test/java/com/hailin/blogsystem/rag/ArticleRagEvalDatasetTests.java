package com.hailin.blogsystem.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ArticleRagEvalDatasetTests {

    private static final String RESOURCE = "/rag/article-rag-eval-v1.jsonl";
    private static final Set<String> ALLOWED_CASE_TYPES = Set.of(
            "TITLE_EXACT", "PARAPHRASE", "CODE_API", "HARD_NEGATIVE", "OUT_OF_DOMAIN"
    );
    private static final Set<String> ALLOWED_RETRIEVAL_MODES = Set.of("NONE", "ARTICLE_SEARCH");
    private static final String ARTICLE_SEARCH = "ARTICLE_SEARCH";
    private static final Map<String, String> EXACT_TITLE_SNAPSHOT = Map.ofEntries(
            Map.entry("title-001", "Redis 缓存工程化实践：从读写策略到一致性治理"),
            Map.entry("title-002", "Java中的@Builder注解详解：优雅创建对象的建造者模式"),
            Map.entry("title-003", "Redis 在电商秒杀系统中的实战落地"),
            Map.entry("title-004", "RAG 检索增强：从静态检索到上下文感知的实战演进"),
            Map.entry("title-005", "RAG 与 Memory 模块集成实践复盘"),
            Map.entry("title-006", "Kafka 消息队列：高吞吐异步解耦的工程落地实践"),
            Map.entry("title-007", "RocketMQ：高可用消息引擎的架构设计与电商核心链路实践"),
            Map.entry("title-008", "MySQL 核心机制与工程实战：面向进阶开发者的深度解析"),
            Map.entry("title-009", "Linux 系统诊断思维：从故障现象到内核原理的逆向求索"),
            Map.entry("title-010", "Java 语言全景：从运行原理到项目实践，为什么选择它"),
            Map.entry("title-011", "从 Copilot 到 Codex：重构 AI 辅助编程的思维范式"),
            Map.entry("title-012", "Java + AI后端面试突围"),
            Map.entry("title-013", "Vibe Coding 入门指南：让编程随感觉流动"),
            Map.entry("title-014", "Claude Code：当 Agent 遇上 Vibe Coding 的即兴编程新范式"),
            Map.entry("title-015", "Linux：从命令行到系统思维"),
            Map.entry("title-016", "Redis 缓存：从入门到高并发场景的实践"),
            Map.entry("title-017", "C语言入门：从零理解程序运行的本质"),
            Map.entry("title-019", "Vibe Coding：当编程成为情绪的即兴表达"),
            Map.entry("title-020", "普通人通过 AI/Agent 变现：从“会用工具”到“卖工作流”的工程化路径"),
            Map.entry("title-021", "Linux：Java 后端视角的底层基石"),
            Map.entry("title-022", "我的第一篇博客文章"),
            Map.entry("title-023", "这是我的第一个海林Blog")
    );

    @Test
    void draftDatasetHasStableSchemaAndCoversEachCaseType() throws Exception {
        List<ArticleRagEvalCase> cases = loadCases();

        assertThat(cases).hasSize(70);
        assertThat(cases).allSatisfy(testCase -> {
            assertThat(testCase.id()).isNotBlank();
            assertThat(testCase.query()).isNotBlank();
            assertThat(testCase.caseType()).isIn(ALLOWED_CASE_TYPES);
            assertThat(testCase.expectedArticleIds()).isNotNull();

            // 路由层两个粒度必须一致，避免两处标注各自漂移
            assertThat(testCase.expectedRetrievalMode()).isIn(ALLOWED_RETRIEVAL_MODES);
            assertThat(ARTICLE_SEARCH.equals(testCase.expectedRetrievalMode()))
                    .as("expectedRetrievalMode 必须与 shouldRouteToRag 一致: %s", testCase.id())
                    .isEqualTo(testCase.shouldRouteToRag());

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

        Map<String, Long> byType = countByCaseType(cases);
        ALLOWED_CASE_TYPES.forEach(type -> assertThat(byType.getOrDefault(type, 0L))
                .as("每个 caseType 至少要有 4 条样例: %s", type)
                .isGreaterThanOrEqualTo(4L));
    }

    @Test
    void datasetIsNotDilutedByEasyCases() {
        // TITLE_EXACT 只是"能召回"的底线检查——查询就是文章标题原文，向量相似度天然很高；
        // 真正的信息量在 PARAPHRASE（用户实际说法）与 HARD_NEGATIVE（阈值/Gate 能否拒绝）。
        // 这个守卫防止评测集再次被容易样例稀释，从而用漂亮的总体 Recall 掩盖真实问题。
        List<ArticleRagEvalCase> cases = assertDoesNotThrowLoad();
        Map<String, Long> byType = countByCaseType(cases);

        long easy = byType.getOrDefault("TITLE_EXACT", 0L);
        long informative = byType.getOrDefault("PARAPHRASE", 0L)
                + byType.getOrDefault("HARD_NEGATIVE", 0L);

        assertThat(informative)
                .as("PARAPHRASE + HARD_NEGATIVE 必须多于 TITLE_EXACT（当前 %d vs %d）", informative, easy)
                .isGreaterThan(easy);
    }

    @Test
    void titleExactCasesUseThePublishedTitleSnapshot() {
        List<ArticleRagEvalCase> exactCases = assertDoesNotThrowLoad().stream()
                .filter(testCase -> "TITLE_EXACT".equals(testCase.caseType()))
                .toList();

        assertThat(exactCases).hasSize(EXACT_TITLE_SNAPSHOT.size());
        assertThat(exactCases).allSatisfy(testCase ->
                assertThat(testCase.query())
                        .as("TITLE_EXACT 必须使用文章标题原文: %s", testCase.id())
                        .isEqualTo(EXACT_TITLE_SNAPSHOT.get(testCase.id()))
        );
    }

    private Map<String, Long> countByCaseType(List<ArticleRagEvalCase> cases) {
        return cases.stream()
                .collect(Collectors.groupingBy(ArticleRagEvalCase::caseType, Collectors.counting()));
    }

    private List<ArticleRagEvalCase> assertDoesNotThrowLoad() {
        try {
            return loadCases();
        } catch (Exception e) {
            throw new IllegalStateException("RAG 评测集加载失败", e);
        }
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
