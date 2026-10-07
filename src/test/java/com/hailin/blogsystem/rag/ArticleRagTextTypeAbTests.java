package com.hailin.blogsystem.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `text_type=document` 的**单变量离线代理实验**。
 *
 * <p><b>为什么先做这个而不是直接建旁路索引</b>：完整 A/B 要新建物理索引 + 重新向量化 136 个 chunk +
 * 跑三套评测，成本不低；而"值不值得做"其实可以先便宜地回答——保持查询侧不变（默认=query 模式），
 * 只把**文档侧**换成 `text_type=document`，看同一批候选的语义分数是否系统性变好。
 * 这里没收益，就没必要重建索引。
 *
 * <p><b>对照的干净程度</b>：ES 索引里的 `content` 就是写入时被 embed 的同一段文本
 * （索引构建走 `text(String)`，已实测 content 与该文本逐字相同），所以：
 *
 * <ul>
 *   <li><b>基线</b> = 索引里的向量（默认模式下 embed 的）与查询的 cosine，取自 ES 的 knn 打分；</li>
 *   <li><b>实验</b> = 现场用 `text_type=document` 重新 embed 同一段文本，再与同一个查询向量算 cosine。</li>
 * </ul>
 *
 * 两者唯一的差别就是文档侧的 text_type —— 单变量成立。
 *
 * <p>默认跳过；显式传 {@code -DrunTextTypeAb=true} 才会访问真实 ES 与 embedding。
 */
@SpringBootTest(properties = {
        "blog.ai.rag.corpus.article-index-name=article_rag_active",
        "blog.ai.rag.corpus.memory-index-name=spring-ai-document-index",
        "blog.ai.rag.corpus.initialize-schema=false"
})
@EnabledIfSystemProperty(named = "runTextTypeAb", matches = "true")
class ArticleRagTextTypeAbTests {

    private static final String RESOURCE = "/rag/article-rag-eval-v1.jsonl";
    private static final int CANDIDATES_PER_CASE = 3;
    private static final String DOCUMENT_TEXT_TYPE = "document";

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${blog.ai.rag.corpus.article-index-name}")
    private String indexName;

    @Value("${blog.ai.rag.corpus.embedding-model}")
    private String embeddingModel;

    @Value("${blog.ai.rag.corpus.dimensions}")
    private int dimensions;

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    private final RestClient restClient = RestClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void comparesDocumentSideTextTypeOnTheSameCandidates() throws Exception {
        List<ArticleRagEvalCase> cases = loadCases().stream()
                .filter(testCase -> "ARTICLE_SEARCH".equals(testCase.expectedRetrievalMode()))
                .toList();
        assertThat(cases).isNotEmpty();

        List<Double> positiveBaseline = new ArrayList<>();
        List<Double> positiveDocument = new ArrayList<>();
        List<Double> negativeBaseline = new ArrayList<>();
        List<Double> negativeDocument = new ArrayList<>();

        StringBuilder report = new StringBuilder();

        for (ArticleRagEvalCase testCase : cases) {
            float[] queryVector = embed(null, testCase.query());
            List<ScoredChunk> candidates = knn(queryVector, CANDIDATES_PER_CASE);
            if (candidates.isEmpty()) {
                continue;
            }

            Set<Long> expected = new LinkedHashSet<>(testCase.expectedArticleIds());

            // 对**每个候选**都用 document 模式重新 embed，而不是拿 top1 的偏移量去近似——
            // 正例要看的是"期望文章"那一条的精确变化，top1 未必就是它。
            List<Double> documentCosines = new ArrayList<>(candidates.size());
            for (ScoredChunk candidate : candidates) {
                documentCosines.add(cosine(queryVector, embed(DOCUMENT_TEXT_TYPE, candidate.content())));
            }

            if (!expected.isEmpty()) {
                for (int i = 0; i < candidates.size(); i++) {
                    if (expected.contains(candidates.get(i).articleId())) {
                        positiveBaseline.add(candidates.get(i).cosine());
                        positiveDocument.add(documentCosines.get(i));
                        break;
                    }
                }
            } else if ("HARD_NEGATIVE".equals(testCase.caseType())) {
                negativeBaseline.add(candidates.get(0).cosine());
                negativeDocument.add(documentCosines.get(0));
            }

            report.append(String.format(
                    Locale.ROOT,
                    "[TEXTTYPE-AB] id=%s type=%s top1Article=%s baselineTop1=%.4f documentTop1=%.4f delta=%+.4f%n",
                    testCase.id(), testCase.caseType(), candidates.get(0).articleId(),
                    candidates.get(0).cosine(), documentCosines.get(0),
                    documentCosines.get(0) - candidates.get(0).cosine()
            ));
        }

        report.append(String.format(Locale.ROOT, "[TEXTTYPE-AB-SUMMARY] candidatesPerCase=%d%n", CANDIDATES_PER_CASE));
        report.append(describe("POSITIVE", positiveBaseline, positiveDocument));
        report.append(describe("HARD_NEGATIVE", negativeBaseline, negativeDocument));

        System.out.print(report);
        Path output = Path.of("target", "rag-text-type-ab.txt");
        Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
        System.out.println("[TEXTTYPE-AB] 报告已写入 " + output.toAbsolutePath());
    }

    /** 逐条对比同一批候选在两种文档侧 text_type 下的分数。 */
    private String describe(String label, List<Double> baseline, List<Double> document) {
        if (baseline.isEmpty()) {
            return String.format(Locale.ROOT, "[TEXTTYPE-AB-%s] n=0%n", label);
        }
        double baseAvg = baseline.stream().mapToDouble(Double::doubleValue).average().orElse(0D);
        double docAvg = document.stream().mapToDouble(Double::doubleValue).average().orElse(0D);
        double baseMin = baseline.stream().mapToDouble(Double::doubleValue).min().orElse(0D);
        double docMin = document.stream().mapToDouble(Double::doubleValue).min().orElse(0D);
        long improved = 0;
        for (int i = 0; i < baseline.size(); i++) {
            if (document.get(i) > baseline.get(i)) {
                improved++;
            }
        }
        return String.format(
                Locale.ROOT,
                "[TEXTTYPE-AB-%s] n=%d avgBaseline=%.4f avgDocument=%.4f deltaAvg=%+.4f "
                        + "minBaseline=%.4f minDocument=%.4f improved=%d/%d%n",
                label, baseline.size(), baseAvg, docAvg, docAvg - baseAvg,
                baseMin, docMin, improved, baseline.size()
        );
    }

    private float[] embed(String textType, String text) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", embeddingModel);
        body.put("input", text);
        body.put("dimensions", dimensions);
        if (textType != null) {
            body.put("text_type", textType);
        }

        JsonNode response = restClient.post()
                .uri(baseUrl + "/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        JsonNode embedding = response == null ? null : response.path("data").path(0).path("embedding");
        if (embedding == null || !embedding.isArray()) {
            throw new IllegalStateException("embedding 响应缺少向量: textType=" + textType);
        }

        float[] vector = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            vector[i] = (float) embedding.get(i).asDouble();
        }
        return vector;
    }

    private List<ScoredChunk> knn(float[] queryVector, int k) throws Exception {
        List<Float> vector = new ArrayList<>(queryVector.length);
        for (float value : queryVector) {
            vector.add(value);
        }

        SearchResponse<JsonData> response = elasticsearchClient.search(s -> s
                        .index(indexName)
                        .size(k)
                        .source(src -> src.filter(filter -> filter.includes("content", "metadata")))
                        .knn(knn -> knn
                                .field("embedding")
                                .queryVector(vector)
                                .k(k)
                                .numCandidates(100)
                                .filter(filter -> filter.term(t -> t
                                        .field("metadata.source.keyword")
                                        .value(FieldValue.of("article"))))),
                JsonData.class
        );

        List<ScoredChunk> chunks = new ArrayList<>();
        response.hits().hits().forEach(hit -> {
            JsonData source = hit.source();
            if (source == null || hit.score() == null) {
                return;
            }
            var json = source.toJson().asJsonObject();
            var metadata = json.getJsonObject("metadata");
            chunks.add(new ScoredChunk(
                    metadata == null ? null : metadata.getJsonNumber("articleId").longValue(),
                    json.getString("content", ""),
                    // ES 对 cosine 返回的 _score 是 (1 + cos) / 2，这里还原成原始 cosine
                    2 * hit.score() - 1
            ));
        });
        return chunks;
    }

    private double cosine(float[] left, float[] right) {
        double dot = 0D;
        double leftNorm = 0D;
        double rightNorm = 0D;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private List<ArticleRagEvalCase> loadCases() throws Exception {
        InputStream input = Objects.requireNonNull(
                ArticleRagTextTypeAbTests.class.getResourceAsStream(RESOURCE),
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

    private record ScoredChunk(Long articleId, String content, double cosine) {
    }
}
