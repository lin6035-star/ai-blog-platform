package com.hailin.blogsystem.ai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.hailin.blogsystem.ai.AiStageTimer;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.*;

@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class GteRerankV2ArticleRagRanker implements ArticleRagRanker{

    private final BlogAiProperties blogAiProperties;
    private final DefaultArticleRagRanker defaultArticleRagRanker;

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    private final RestClient restClient = RestClient.create();

    @Override
    public List<ArticleRagContext> rank(String question, List<ArticleRagContext> contexts) {
        return rankWithEvidence(question, contexts).contexts();
    }

    @Override
    public ArticleRagRankResult rankWithEvidence(String question, List<ArticleRagContext> contexts) {
        AiStageTimer timer = AiStageTimer.start();
        String outcome = "error";
        int candidateCount = contexts == null ? 0 : contexts.size();
        try {
            if (contexts == null || contexts.isEmpty()) {
                outcome = "empty";
                log.info("gte-rerank-v2 skip: empty contexts");
                return ArticleRagRankResult.empty();
            }

            BlogAiProperties.Rerank rerank = blogAiProperties.getRag().getRerank();
            if (rerank == null || !rerank.isEnabled()) {
                outcome = "fallback_disabled";
                log.info("gte-rerank-v2 disabled, fallback to default");
                return defaultArticleRagRanker.rankWithEvidence(question, contexts);
            }
            if (rerank.getWorkspaceId() == null || rerank.getWorkspaceId().isBlank()) {
                outcome = "fallback_config";
                log.info("gte-rerank-v2 workspaceId missing, fallback to default");
                return defaultArticleRagRanker.rankWithEvidence(question, contexts);
            }

            try {
                List<ArticleRagContext> limited = selectRerankCandidates(
                        contexts,
                        rerank.getMaxDocuments(),
                        rerank.getMaxChunksPerArticle()
                );

            List<String> documents = limited.stream()
                    .map(this::toDocumentText)
                    .toList();

            String url = "https://%s.cn-beijing.maas.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"
                    .formatted(rerank.getWorkspaceId().trim());

            Map<String, Object> input = Map.of(
                    "query", question,
                    "documents", documents
            );

            Map<String, Object> parameters = Map.of(
                    "return_documents", false,
                    "top_n", rerank.getTopN()
            );

            Map<String, Object> body = Map.of(
                    "model", rerank.getModel(),
                    "input", input,
                    "parameters", parameters
            );

            log.info("gte-rerank-v2 request: originalCandidate={}, candidate={}, maxChunksPerArticle={}, topN={}, model={}",
                    contexts.size(), limited.size(), rerank.getMaxChunksPerArticle(),
                    rerank.getTopN(), rerank.getModel());

            JsonNode response = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            log.info("gte-rerank-v2 response raw: hasOutput={}, hasResults={}",
                    response != null && response.has("output"),
                    response != null && response.path("output").has("results"));

            List<ArticleRagRankResult.Candidate> ranked = parseRanked(response, limited, rerank.getTopN());
            if (ranked.isEmpty() || ranked.stream().anyMatch(candidate -> candidate.relevanceScore() == null)) {
                outcome = "fallback_empty";
                log.info("gte-rerank-v2 empty results, fallback to default");
                return defaultArticleRagRanker.rankWithEvidence(question, contexts);
            }

                log.info("gte-rerank-v2 排序成功，candidate={}, ranked={}",
                        limited.size(), ranked.size());

                outcome = "applied";
                return new ArticleRagRankResult(ArticleRagRankResult.Status.MODEL_APPLIED, ranked);
            } catch (Exception e) {
                outcome = "fallback_error";
                log.warn("gte-rerank-v2 失败，回退规则 rerank: {}", e.getMessage());
                return defaultArticleRagRanker.rankWithEvidence(question, contexts);
            }
        } finally {
            log.info("[PERF-AI] stage=rerank durationMs={} outcome={} candidates={}",
                    timer.elapsedMillis(), outcome, candidateCount);
        }
    }

    private List<ArticleRagRankResult.Candidate> parseRanked(JsonNode response,
                                                            List<ArticleRagContext> contexts,
                                                            int topN) {
        JsonNode results = response == null ? null : response.path("output").path("results");
        if (results == null || !results.isArray()) {
            return List.of();
        }

        List<ArticleRagRankResult.Candidate> ranked = new ArrayList<>();
        Set<Long> seenArticleIds = new LinkedHashSet<>();

        for (JsonNode item : results) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= contexts.size()) {
                continue;
            }

            ArticleRagContext context = contexts.get(index);
            Long articleId = context.articleId();
            if (articleId != null && seenArticleIds.add(articleId)) {
                ranked.add(new ArticleRagRankResult.Candidate(
                        context,
                        item.path("relevance_score").isNumber()
                                ? item.path("relevance_score").asDouble()
                                : null,
                        ranked.size() + 1
                ));
            }

            if (ranked.size() >= Math.max(1, topN)) {
                break;
            }
        }

        return ranked;
    }

    private List<ArticleRagContext> selectRerankCandidates(List<ArticleRagContext> contexts,
                                                            int maxDocuments,
                                                            int maxChunksPerArticle) {
        int documentLimit = Math.max(1, maxDocuments);
        int perArticleLimit = Math.max(1, maxChunksPerArticle);
        List<ArticleRagContext> selected = new ArrayList<>(Math.min(documentLimit, contexts.size()));
        Map<Long, Integer> selectedPerArticle = new HashMap<>();

        for (ArticleRagContext context : contexts) {
            Long articleId = context.articleId();
            if (articleId != null) {
                int selectedCount = selectedPerArticle.getOrDefault(articleId, 0);
                if (selectedCount >= perArticleLimit) {
                    continue;
                }
                selectedPerArticle.put(articleId, selectedCount + 1);
            }

            selected.add(context);
            if (selected.size() >= documentLimit) {
                break;
            }
        }
        return List.copyOf(selected);
    }

    private String toDocumentText(ArticleRagContext context) {
        String title = context.title() == null ? "" : context.title();
        String content = context.content() == null ? "" : context.content();
        String text = "标题：" + title + "\n片段：" + content;
        return text.length() > 1200 ? text.substring(0, 1200) : text;
    }

}
